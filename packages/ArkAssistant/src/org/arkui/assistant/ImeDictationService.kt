/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import android.view.WindowManager
import com.android.internal.inputmethod.ImeVoiceProtocol as Protocol

/** Microphone access stays in ArkAI; the IME receives text only for its current input session. */
class ImeDictationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private var client: Messenger? = null
    private var clientUid = -1
    private var session = 0
    private var cancellationToken = ""
    private var activation: PendingIntent? = null
    private var speech: SpeechInput? = null
    private var death: IBinder.DeathRecipient? = null

    override fun onBind(intent: Intent): IBinder = incoming.binder
    override fun onCreate() { super.onCreate(); active = this }

    private fun isCurrentIme(uid: Int): Boolean = runCatching {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = ComponentName.unflattenFromString(id ?: return false) ?: return false
        uid == packageManager.getApplicationInfo(component.packageName, 0).uid
    }.getOrDefault(false)

    private fun permitted(): Boolean = isCurrentIme(clientUid) &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        Settings.Secure.getInt(contentResolver, "arkui_assistant_enabled", 0) == 1

    private fun handle(message: Message) {
        val reply = message.replyTo ?: return
        if (message.what == Protocol.START) {
            if (!isCurrentIme(message.sendingUid)) return
            end()
            client = reply
            clientUid = message.sendingUid
            session = message.arg1
            cancellationToken = java.util.UUID.randomUUID().toString()
            if (!permitted()) { error(getString(R.string.ime_voice_disabled)); return }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                error(getString(R.string.ime_voice_permission)); return
            }
            val activeClient = reply.binder
            death = IBinder.DeathRecipient { main.post { if (client?.binder == activeClient) end() } }
            try {
                activeClient.linkToDeath(death!!, 0)
                // The selected IME sends this one-shot activity intent for the user's click.
                // Its non-focusable window leaves the editor and keyboard in place.
                activation = PendingIntent.getActivity(this, 0,
                    Intent(this, ImeDictationActivity::class.java).putExtra("token", cancellationToken)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
                send(Protocol.ACTIVATE, Bundle().apply { putParcelable(Protocol.ACTIVATION, activation) })
                val pending = activation
                main.postDelayed({ if (pending != null && activation === pending) error(getString(R.string.voice_failed)) }, 5000)
                main.postDelayed(watchSession, 1000)
            } catch (e: Exception) {
                error(getString(R.string.voice_failed))
            }
        } else if (message.sendingUid == clientUid && reply.binder == client?.binder && message.arg1 == session) {
            when (message.what) {
                Protocol.FINISH -> if (permitted()) speech?.finish() else end()
                Protocol.CANCEL -> end()
            }
        }
    }

    internal fun activate(token: String?) {
        if (activation == null || token != cancellationToken || !permitted()) return
        activation = null
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.voice_input), NotificationManager.IMPORTANCE_LOW))
            val cancel = PendingIntent.getService(this, 0, Intent(this, ImeDictationService::class.java)
                .setAction(CANCEL_ACTION).putExtra("token", cancellationToken),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_assistant).setContentTitle(getString(R.string.voice_input))
                .setContentText(getString(R.string.ime_voice_listening)).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(Notification.Action.Builder(null, getString(android.R.string.cancel), cancel).build())
                .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            speech = SpeechInput(this, { text ->
                if (permitted()) send(Protocol.RESULT, Bundle().apply { putString(Protocol.TEXT, text) })
                end()
            }, { stopForeground(STOP_FOREGROUND_REMOVE) }, { state ->
                if (state.error.isNotEmpty()) {
                    val token = session
                    val recipient = client?.binder
                    main.post { if (token == session && recipient != null && client?.binder == recipient) error(state.error) }
                } else if (state.active) {
                    send(Protocol.STATE, Bundle().apply {
                        putString(Protocol.TEXT, state.text)
                        putFloat(Protocol.LEVEL, state.level)
                        putBoolean(Protocol.TRANSCRIBING, state.transcribing)
                    })
                }
            })
            speech!!.start()
        } catch (e: Exception) {
            android.util.Log.w("ArkAIDictation", "Unable to start keyboard dictation", e)
            error(getString(R.string.voice_failed))
        }
    }

    private val watchSession = object : Runnable {
        override fun run() {
            if (client == null) return
            if (!permitted()) { error(getString(R.string.ime_voice_disabled)); return }
            main.postDelayed(this, 1000)
        }
    }

    private fun send(type: Int, data: Bundle) {
        runCatching { client?.send(Message.obtain(null, type).apply { this.data = data; arg1 = session }) }
            .onFailure { main.post { end() } }
    }

    private fun error(text: String) {
        send(Protocol.ERROR, Bundle().apply { putString(Protocol.TEXT, text) })
        end()
    }

    private fun end() {
        main.removeCallbacks(watchSession)
        death?.let { recipient -> runCatching { client?.binder?.unlinkToDeath(recipient, 0) } }
        death = null
        client = null
        clientUid = -1
        activation?.cancel(); activation = null
        cancellationToken = ""
        ++session
        speech?.destroy()
        speech = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (client != null && intent?.action == CANCEL_ACTION &&
            intent.getStringExtra("token") == cancellationToken) {
            send(Protocol.ERROR, Bundle().apply { putString(Protocol.TEXT, getString(R.string.ime_voice_cancelled)) })
            end()
        }
        if (client == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent): Boolean { end(); return false }
    override fun onDestroy() { if (active === this) active = null; end(); super.onDestroy() }

    companion object {
        internal var active: ImeDictationService? = null
            private set
        private const val CHANNEL = "ime_dictation"
        private const val NOTIFICATION = 4103
        private const val CANCEL_ACTION = "org.arkui.assistant.CANCEL_DICTATION"
    }
}

/** A transient, non-focusable foreground handoff, never an assistant/settings task. */
class ImeDictationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onPostResume() {
        super.onPostResume()
        ImeDictationService.active?.activate(intent.getStringExtra("token"))
        finishAndRemoveTask()
    }
}
