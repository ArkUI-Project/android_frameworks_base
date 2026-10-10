/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.arkui.assistant.protocol.AssistantProtocol

data class AssistantConfig(
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val key: String = "",
    val control: Boolean = true,
    val steps: Int = 12,
    val background: Boolean = false,
    val speechEndpoint: String = "",
    val speechModel: String = "whisper-1",
    val speechKey: String = "",
) {
    fun valid(): Boolean = runCatching {
        val uri = URI(endpoint.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && model.isNotBlank() &&
            key.isNotBlank() && !key.any { it.isWhitespace() } && steps in 4..30
    }.getOrDefault(false)

    fun completionsUrl(): String {
        val base = endpoint.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun speechValid(): Boolean = runCatching {
        val uri = URI(speechEndpoint.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
            && uri.query == null && uri.fragment == null && speechModel.isNotBlank()
            && speechModel.length <= 200 && !speechModel.any { it == '\r' || it == '\n' }
            && speechKey.isNotBlank() && !speechKey.any { it.isWhitespace() }
    }.getOrDefault(false)
}

class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    val defaultBackground: Boolean get() = prefs.getBoolean("background", false)
    val enabled: Boolean
        get() = Settings.Secure.getInt(context.contentResolver, AssistantProtocol.ENABLED, 0) == 1

    fun setEnabled(value: Boolean) {
        Settings.Secure.putInt(context.contentResolver, AssistantProtocol.ENABLED, if (value) 1 else 0)
    }

    fun read(): AssistantConfig = AssistantConfig(
        endpoint = prefs.getString("endpoint", "https://api.deepseek.com")!!,
        model = prefs.getString("model", "deepseek-flash")!!,
        key = runCatching { decrypt(prefs.getString("key", "")!!) }.getOrDefault(""),
        control = prefs.getBoolean("control", true),
        steps = prefs.getInt("steps", 12).coerceIn(4, 30),
        background = defaultBackground,
        speechEndpoint = prefs.getString("speech_endpoint", "")!!,
        speechModel = prefs.getString("speech_model", "whisper-1")!!,
        speechKey = runCatching { decrypt(prefs.getString("speech_key", "")!!) }.getOrDefault(""),
    )

    fun save(config: AssistantConfig, requireValid: Boolean = true) {
        if (requireValid) require(config.valid())
        check(prefs.edit().putString("endpoint", config.endpoint.trim().trimEnd('/'))
            .putString("model", config.model.trim()).putString("key", encrypt(config.key.trim()))
            .putBoolean("control", config.control).putInt("steps", config.steps)
            .putBoolean("background", config.background)
            .putString("speech_endpoint", config.speechEndpoint.trim().trimEnd('/'))
            .putString("speech_model", config.speechModel.trim())
            .putString("speech_key", encrypt(config.speechKey.trim())).commit())
    }

    fun blockedApps(): Set<String> = runCatching {
        val list = JSONArray(Settings.Secure.getString(context.contentResolver, AssistantProtocol.BLOCKED_APPS) ?: "[]")
        (0 until list.length()).map { list.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setBlockedApps(apps: Set<String>) {
        require(apps.all { it.matches(Regex("[a-zA-Z0-9_.]+")) } && apps.size <= 500)
        Settings.Secure.putString(context.contentResolver, AssistantProtocol.BLOCKED_APPS,
            JSONArray(apps.sorted()).toString())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("arkai_config", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("arkai_config",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
