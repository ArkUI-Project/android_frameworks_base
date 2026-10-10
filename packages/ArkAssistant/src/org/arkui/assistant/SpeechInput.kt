/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject
import kotlin.math.sqrt

internal data class VoiceSnapshot(val active: Boolean = false, val transcribing: Boolean = false,
    val text: String = "", val level: Float = 0f, val error: String = "")

/** Audio stays in memory and is discarded on cancellation. Only the configured service sees it. */
internal class SpeechInput(private val context: Context, private val onText: (String) -> Unit,
    private val onInactive: () -> Unit) {
    var state by mutableStateOf(VoiceSnapshot())
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var generation = 0
    @Volatile private var ending = false

    fun start() {
        cancel()
        val token = generation
        val config = ConfigStore(context).read()
        if (config.speechEndpoint.isNotBlank()) {
            if (!config.speechValid()) { fail(token, context.getString(R.string.voice_configure)); return }
            state = VoiceSnapshot(active = true)
            worker.submit { recordAndTranscribe(config, token) }
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            state = VoiceSnapshot(active = true)
            recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { }
                override fun onBeginningOfSpeech() { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEvent(type: Int, params: Bundle?) { }
                override fun onRmsChanged(rms: Float) { if (token == generation) state = state.copy(level = ((rms + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onEndOfSpeech() { if (token == generation) state = state.copy(transcribing = true, level = 0f) }
                override fun onPartialResults(results: Bundle?) {
                    if (token == generation) state = state.copy(text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onResults(results: Bundle?) {
                    complete(token, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(code: Int) { fail(token, context.getString(R.string.voice_failed)) }
            })
            runCatching { recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)) }
                .onFailure { fail(token, context.getString(R.string.voice_failed)) }
        } else fail(token, context.getString(R.string.voice_configure))
        main.postDelayed({ if (token == generation && state.active) finish() }, 30_000)
        main.postDelayed({ if (token == generation && state.active) {
            cancel(); state = VoiceSnapshot(error = context.getString(R.string.voice_failed))
        } }, 90_000)
    }

    fun finish() {
        ending = true
        recognizer?.stopListening()
    }

    private fun recordAndTranscribe(config: AssistantConfig, token: Int) {
        var audio: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            recorder = audio
            audio.startRecording()
            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(3200)
            val began = SystemClock.uptimeMillis()
            var lastSpeech = began
            var heard = false
            while (token == generation && !ending && SystemClock.uptimeMillis() - began < 30_000) {
                val count = audio.read(buffer, 0, buffer.size)
                check(count > 0)
                pcm.write(buffer, 0, count)
                var sum = 0.0
                val samples = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (samples.hasRemaining()) { val value = samples.get().toDouble() / 32768; sum += value * value }
                val rms = sqrt(sum / (count / 2)).toFloat()
                if (rms > 0.014f) { heard = true; lastSpeech = SystemClock.uptimeMillis() }
                main.post { if (token == generation) state = state.copy(level = (rms * 12f).coerceIn(0f, 1f)) }
                if (heard && SystemClock.uptimeMillis() - lastSpeech > 1400) break
                if (!heard && SystemClock.uptimeMillis() - began > 6000) break
            }
            audio.stop(); audio.release(); audio = null; recorder = null
            if (token != generation) return
            check(heard) { context.getString(R.string.voice_no_speech) }
            main.post { if (token == generation) state = state.copy(transcribing = true, level = 0f) }
            val bytes = pcm.toByteArray()
            val wav = ByteBuffer.allocate(44 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + bytes.size); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(bytes.size); put(bytes)
            }.array()
            val base = config.speechEndpoint.trim().trimEnd('/')
            val url = if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
            val boundary = "arkai_${java.util.UUID.randomUUID()}"
            val http = URL(url).openConnection() as HttpURLConnection
            connection = http
            if (token != generation) return
            http.apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 45000
                setRequestProperty("Authorization", "Bearer ${config.speechKey}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            http.outputStream.use { output ->
                output.write(("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${config.speechModel}\r\n" +
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray())
                output.write(wav); output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            check(http.responseCode in 200..299) { context.getString(R.string.voice_failed) }
            val body = http.inputStream.use { it.readNBytes(65537) }
            check(body.size <= 65536)
            val text = JSONObject(String(body, Charsets.UTF_8)).getString("text").trim().take(6000)
            main.post { complete(token, text) }
        } catch (e: Exception) {
            main.post { fail(token, if (e.message == context.getString(R.string.voice_no_speech)) e.message!! else context.getString(R.string.voice_failed)) }
        } finally {
            runCatching { audio?.stop() }; audio?.release()
            if (recorder === audio) recorder = null
            connection?.disconnect(); connection = null
        }
    }

    private fun complete(token: Int, text: String) {
        if (token != generation) return
        if (text.isBlank()) { fail(token, context.getString(R.string.voice_no_speech)); return }
        state = VoiceSnapshot(text = text)
        recognizer?.destroy(); recognizer = null
        onInactive()
        onText(text)
    }

    private fun fail(token: Int, message: String) {
        if (token != generation) return
        val wasActive = state.active
        state = VoiceSnapshot(error = message)
        recognizer?.destroy(); recognizer = null
        if (wasActive) onInactive()
    }

    fun cancel() {
        val wasActive = state.active
        ++generation; ending = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        runCatching { recorder?.stop() }
        connection?.disconnect()
        state = VoiceSnapshot()
        if (wasActive) onInactive()
    }

    fun destroy() { cancel(); worker.shutdownNow() }
}
