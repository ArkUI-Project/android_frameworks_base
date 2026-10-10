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
import org.json.JSONObject
import org.arkui.assistant.protocol.AssistantProtocol

data class AiProvider(
    val id: String = "default",
    val name: String = "DeepSeek",
    val endpoint: String = "https://api.deepseek.com",
    val key: String = "",
    val models: List<String> = listOf("deepseek-flash"),
    val model: String = "deepseek-flash",
) {
    fun valid(): Boolean = runCatching {
        val uri = URI(endpoint.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && model.isNotBlank() &&
            model in models && key.isNotBlank() && !key.any { it.isWhitespace() }
    }.getOrDefault(false)
}

data class AssistantConfig(
    val providers: List<AiProvider> = listOf(AiProvider()),
    val providerId: String = "default",
    val control: Boolean = true,
    val steps: Int = 0,
    val speechEndpoint: String = "",
    val speechModel: String = "whisper-1",
    val speechKey: String = "",
) {
    val provider: AiProvider get() = providers.firstOrNull { it.id == providerId } ?: AiProvider()
    val endpoint: String get() = provider.endpoint
    val model: String get() = provider.model
    val key: String get() = provider.key

    fun valid(): Boolean = providers.any { it.id == providerId } && provider.valid() &&
        (steps == 0 || steps in 4..30)

    fun withProvider(value: AiProvider): AssistantConfig =
        copy(providers = providers.map { if (it.id == value.id) value else it })

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
    val enabled: Boolean
        get() = Settings.Secure.getInt(context.contentResolver, AssistantProtocol.ENABLED, 0) == 1

    fun setEnabled(value: Boolean) {
        Settings.Secure.putInt(context.contentResolver, AssistantProtocol.ENABLED, if (value) 1 else 0)
    }

    fun read(): AssistantConfig {
        val providers = readProviders()
        val selected = prefs.getString("active_provider", "default")
        return AssistantConfig(
            providers = providers,
            providerId = providers.firstOrNull { it.id == selected }?.id ?: providers.first().id,
            control = prefs.getBoolean("control", true),
            steps = prefs.getInt("steps", 0).let { if (it == 0) 0 else it.coerceIn(4, 30) },
            speechEndpoint = prefs.getString("speech_endpoint", "")!!,
            speechModel = prefs.getString("speech_model", "whisper-1")!!,
            speechKey = runCatching { decrypt(prefs.getString("speech_key", "")!!) }.getOrDefault(""),
        )
    }

    private fun readProviders(): List<AiProvider> {
        val saved = runCatching {
            val array = JSONArray(prefs.getString("providers", "[]") ?: "[]")
            (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val models = value.getJSONArray("models")
                AiProvider(
                    id = value.getString("id"), name = value.getString("name"),
                    endpoint = value.getString("endpoint"),
                    key = runCatching { decrypt(value.getString("key")) }.getOrDefault(""),
                    models = (0 until models.length()).map { models.getString(it) },
                    model = value.optString("model"),
                )
            }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) return saved

        // Read the previous single-service format without changing its credentials or model.
        val endpoint = prefs.getString("endpoint", "https://api.deepseek.com")!!
        val model = prefs.getString("model", "deepseek-flash")!!
        val host = runCatching { URI(endpoint).host }.getOrNull()
        return listOf(AiProvider(
            name = if (host == "api.deepseek.com") "DeepSeek" else host ?: context.getString(R.string.provider),
            endpoint = endpoint, model = model, models = listOf(model),
            key = runCatching { decrypt(prefs.getString("key", "")!!) }.getOrDefault(""),
        ))
    }

    fun save(config: AssistantConfig, requireValid: Boolean = true) {
        if (requireValid) require(config.valid())
        require(config.providers.isNotEmpty() && config.providers.any { it.id == config.providerId })
        require(config.providers.map { it.id }.distinct().size == config.providers.size)
        require(config.providers.all { it.id.isNotBlank() && it.name.isNotBlank() })
        val providers = JSONArray()
        config.providers.forEach { provider ->
            providers.put(JSONObject().put("id", provider.id).put("name", provider.name.trim())
                .put("endpoint", provider.endpoint.trim().trimEnd('/'))
                .put("key", encrypt(provider.key.trim())).put("models", JSONArray(provider.models))
                .put("model", provider.model))
        }
        check(prefs.edit().putString("providers", providers.toString())
            .putString("active_provider", config.providerId)
            .putBoolean("control", config.control).putInt("steps", config.steps)
            .remove("endpoint").remove("model").remove("key").remove("background").commit())
    }

    fun saveSpeech(config: AssistantConfig) {
        check(prefs.edit()
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
