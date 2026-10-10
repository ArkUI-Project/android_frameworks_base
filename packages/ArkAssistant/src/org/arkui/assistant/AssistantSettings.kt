/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.arkui.assistant

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.IPackageDeleteObserver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.settingsBackground
import com.android.settingslib.spa.widget.preference.CheckboxPreference
import com.android.settingslib.spa.widget.preference.CheckboxPreferenceModel
import com.android.settingslib.spa.widget.preference.MainSwitchPreference
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.Footer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.arkui.assistant.protocol.AssistantInvocation

class AssistantSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !resources.configuration.isNightModeActive
            isAppearanceLightNavigationBars = !resources.configuration.isNightModeActive
        }
        setContent {
            SettingsTheme {
                val type = MaterialTheme.typography
                MaterialExpressiveTheme(colorScheme = MaterialTheme.colorScheme,
                    typography = type.copy(
                        titleLarge = type.titleLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
                        titleMedium = type.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
                        titleSmall = type.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                        bodyLarge = type.bodyLarge.copy(letterSpacing = 0.sp),
                        bodyMedium = type.bodyMedium.copy(letterSpacing = 0.sp),
                        bodySmall = type.bodySmall.copy(letterSpacing = 0.sp),
                        labelLarge = type.labelLarge.copy(letterSpacing = 0.sp)),
                    motionScheme = MotionScheme.expressive()) {
                    ConfigScreen(this, intent.action == "org.arkui.assistant.REMOVE")
                }
            }
        }
    }

    internal fun removeAssistant() {
        val store = ConfigStore(this)
        val previouslyEnabled = store.enabled
        store.setEnabled(false)
        stopService(Intent(this, AssistantService::class.java))
        val observer = object : IPackageDeleteObserver.Stub() {
            override fun packageDeleted(name: String?, code: Int) {
                if (code != PackageManager.DELETE_SUCCEEDED) runOnUiThread {
                    store.setEnabled(previouslyEnabled)
                    Toast.makeText(this@AssistantSettingsActivity, getString(R.string.remove_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        runCatching { packageManager.deletePackageAsUser(packageName, observer, PackageManager.DELETE_SYSTEM_APP, Process.myUserHandle().identifier) }
            .onFailure {
                store.setEnabled(previouslyEnabled)
                Toast.makeText(this, getString(R.string.remove_failed), Toast.LENGTH_LONG).show()
            }
    }
}

@Composable
private fun ConfigScreen(activity: AssistantSettingsActivity, requestRemove: Boolean) {
    val store = remember { ConfigStore(activity) }
    var config by remember { mutableStateOf(store.read()) }
    var enabled by remember { mutableStateOf(store.enabled) }
    var page by remember { mutableStateOf("main") }
    var editing by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(requestRemove) }
    var blocked by remember { mutableStateOf(store.blockedApps()) }
    val shortcutTitles = remember { linkedMapOf(
        AssistantInvocation.POWER to R.string.invoke_power,
        AssistantInvocation.DOUBLE_TAP to R.string.invoke_double_tap,
        AssistantInvocation.LONG_PRESS to R.string.invoke_long_press) }
    val shortcutActions = remember { linkedMapOf(
        AssistantInvocation.VOICE to R.string.invoke_voice,
        AssistantInvocation.TEXT to R.string.invoke_text,
        AssistantInvocation.SCREEN to R.string.invoke_screen,
        AssistantInvocation.NONE to R.string.invoke_disabled) }
    var shortcuts by remember { mutableStateOf(shortcutTitles.keys.associateWith {
        AssistantInvocation.action(activity, it, Process.myUserHandle().identifier) }) }
    var shortcutEditing by remember { mutableStateOf<String?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    var testClient by remember { mutableStateOf<AiClient?>(null) }
    DisposableEffect(Unit) { onDispose { testClient?.cancel(); executor.shutdownNow(); main.removeCallbacksAndMessages(null) } }
    DisposableEffect(editing) {
        if (editing == "key" || editing == "speech_key") activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler(page != "main") { page = "main" }

    fun save(next: AssistantConfig) {
        runCatching {
            store.save(next, requireValid = false); config = next
            if (!next.valid()) { store.setEnabled(false); enabled = false }
            if (TaskState.current.running) activity.stopService(Intent(activity, AssistantService::class.java))
        }.onFailure { message = activity.getString(R.string.invalid_config) }
    }
    fun edit(field: String, current: String) { value = current; editing = field }
    fun test() {
        if (!config.valid()) { message = activity.getString(R.string.invalid_config); return }
        testing = true; message = activity.getString(R.string.testing)
        val client = AiClient(config); testClient = client
        executor.submit {
            val result = runCatching { testConnection(client); activity.getString(R.string.connection_ok) }
                .getOrElse { it.message?.take(120) ?: activity.getString(R.string.network_error) }
            main.post { testing = false; message = result; testClient = null }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.settingsBackground,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp)
                .padding(horizontal = SettingsSpace.small1), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { if (page == "main") activity.finish() else page = "main" },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.close))
                }
                Text(stringResource(when (page) { "apps" -> R.string.blocked_apps; "speech" -> R.string.voice_input; else -> R.string.settings }),
                    Modifier.weight(1f).padding(start = SettingsSpace.small1), style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        AnimatedContent(page, transitionSpec = {
            val direction = if (targetState == "main") -1 else 1
            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { direction * it / 8 }) togetherWith
                (fadeOut(tween(100)) + slideOutHorizontally(tween(180)) { -direction * it / 12 })
        }, label = "settings page") { selected ->
            if (selected == "apps") BlockedAppsScreen(activity, blocked, padding) { apps ->
                blocked = apps; store.setBlockedApps(apps)
                if (TaskState.current.running) activity.stopService(Intent(activity, AssistantService::class.java))
            } else key(selected) { Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding()
                .padding(bottom = 32.dp)) {
                if (selected == "main") {
                    MainSwitchPreference(object : SwitchPreferenceModel {
                        override val title = activity.getString(R.string.enabled)
                        override val checked = { enabled }
                        override val onCheckedChange: (Boolean) -> Unit = { checked ->
                                if (!checked || config.valid()) {
                                    enabled = checked; store.setEnabled(checked)
                                    if (!checked) activity.stopService(Intent(activity, AssistantService::class.java))
                                    else if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                                } else message = activity.getString(R.string.invalid_config)
                        }
                    })
                    Category(stringResource(R.string.provider)) {
                        SettingsCell(R.string.endpoint, config.endpoint) { edit("endpoint", config.endpoint) }
                        SettingsCell(R.string.model, config.model) { edit("model", config.model) }
                        SettingsCell(R.string.api_key, activity.getString(if (config.key.isBlank()) R.string.not_configured else R.string.key_saved)) { edit("key", config.key) }
                        SettingsCell(R.string.test_connection, message, enabled = !testing) { test() }
                    }
                    Category(stringResource(R.string.assistant_behavior)) {
                        SwitchPreference(object : SwitchPreferenceModel {
                            override val title = activity.getString(R.string.control)
                            override val summary = { activity.getString(R.string.control_summary) }
                            override val checked = { config.control }
                            override val onCheckedChange: (Boolean) -> Unit = { save(config.copy(control = it)) }
                        })
                        SettingsCell(R.string.blocked_apps, activity.getString(R.string.blocked_count, blocked.size)) { page = "apps" }
                        SettingsCell(R.string.voice_input, activity.getString(if (config.speechEndpoint.isNotBlank()) R.string.voice_custom else R.string.voice_system)) { page = "speech" }
                    }
                    Category(stringResource(R.string.invoke_methods)) {
                        shortcutTitles.forEach { (entry, title) ->
                            SettingsCell(title, stringResource(shortcutActions.getValue(shortcuts.getValue(entry)))) {
                                shortcutEditing = entry
                            }
                        }
                    }
                    Category(stringResource(R.string.advanced)) {
                        SettingsCell(R.string.step_limit, if (config.steps == 0) activity.getString(R.string.unlimited_steps) else config.steps.toString()) { editing = "steps" }
                        SettingsCell(R.string.default_assistant, "") {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                        SettingsCell(R.string.add_tile, "") {
                            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(activity, AssistantTileService::class.java),
                                activity.getString(R.string.app_name), android.graphics.drawable.Icon.createWithResource(activity, R.drawable.ic_assistant), activity.mainExecutor) { }
                        }
                    }
                    Footer(stringResource(R.string.screen_disclosure))
                } else {
                    Category(stringResource(R.string.voice_system)) {
                        SettingsCell(R.string.microphone_permission, "") {
                            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 42)
                        }
                        SettingsCell(R.string.voice_system,
                            activity.getString(if (SpeechRecognizer.isRecognitionAvailable(activity)) R.string.available else R.string.not_available)) {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }
                    }
                    Category(stringResource(R.string.voice_custom)) {
                        SettingsCell(R.string.speech_endpoint, config.speechEndpoint.ifBlank { activity.getString(R.string.not_configured) }) { edit("speech_endpoint", config.speechEndpoint) }
                        SettingsCell(R.string.model, config.speechModel) { edit("speech_model", config.speechModel) }
                        SettingsCell(R.string.api_key, activity.getString(if (config.speechKey.isBlank()) R.string.not_configured else R.string.key_saved)) { edit("speech_key", config.speechKey) }
                    }
                    Footer(stringResource(R.string.voice_disclosure))
                }
            } }
        }
    }
    shortcutEditing?.let { entry ->
        AlertDialog(onDismissRequest = { shortcutEditing = null },
            title = { Text(stringResource(shortcutTitles.getValue(entry))) },
            text = { Column {
                shortcutActions.forEach { (action, title) ->
                    androidx.compose.material3.ListItem(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = shortcuts[entry] == action,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = {
                                android.provider.Settings.Secure.putInt(activity.contentResolver, entry, action)
                                shortcuts = shortcuts + (entry to action)
                                shortcutEditing = null
                            }),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        headlineContent = { Text(stringResource(title)) },
                        leadingContent = { RadioButton(selected = shortcuts[entry] == action, onClick = null) })
                }
            } },
            confirmButton = { TextButton(onClick = { shortcutEditing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (editing == "steps") {
        var unlimited by remember { mutableStateOf(config.steps == 0) }
        var steps by remember { mutableStateOf(if (config.steps == 0) 12f else config.steps.toFloat()) }
        AlertDialog(onDismissRequest = { editing = "" }, icon = { Icon(Icons.Rounded.Route, null) }, title = { Text(stringResource(R.string.step_limit)) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.unlimited_steps), Modifier.weight(1f))
                    Switch(unlimited, { unlimited = it })
                }
                if (!unlimited) {
                    Text(steps.toInt().toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Slider(steps, { steps = it }, valueRange = 4f..30f, steps = 25)
                }
            } }, confirmButton = { TextButton(onClick = { save(config.copy(steps = if (unlimited) 0 else steps.toInt())); editing = "" }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.cancel)) } })
    } else if (editing.isNotBlank()) {
        val secret = editing == "key" || editing == "speech_key"
        var reveal by remember(editing) { mutableStateOf(false) }
        val label = when (editing) { "endpoint" -> R.string.endpoint; "speech_endpoint" -> R.string.speech_endpoint;
            "model", "speech_model" -> R.string.model; else -> R.string.api_key }
        AlertDialog(onDismissRequest = { editing = "" }, icon = { Icon(if (secret) Icons.Rounded.Key else Icons.Rounded.Tune, null) },
            properties = DialogProperties(securePolicy = if (secret) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
            title = { Text(stringResource(label)) }, text = {
                OutlinedTextField(value, { value = it.take(4096) }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (editing.endsWith("endpoint")) KeyboardType.Uri else KeyboardType.Text),
                    trailingIcon = { if (secret) IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.show_key)) } })
            }, confirmButton = { TextButton(onClick = {
                save(when (editing) { "endpoint" -> config.copy(endpoint = value.trim()); "model" -> config.copy(model = value.trim());
                    "key" -> config.copy(key = value.trim()); "speech_endpoint" -> config.copy(speechEndpoint = value.trim());
                    "speech_model" -> config.copy(speechModel = value.trim()); else -> config.copy(speechKey = value.trim()) })
                editing = ""
            }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.cancel)) } })
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text(stringResource(R.string.remove)) }, text = { Text(stringResource(R.string.remove_message)) },
        confirmButton = { TextButton(onClick = { activity.removeAssistant() }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun SettingsCell(title: Int, subtitle: String, enabled: Boolean = true, action: () -> Unit) {
    val label = stringResource(title)
    Preference(object : PreferenceModel {
        override val title = label
        override val summary = { subtitle }
        override val enabled = { enabled }
        override val onClick = action
    })
}

@Composable
private fun BlockedAppsScreen(activity: AssistantSettingsActivity, blocked: Set<String>, padding: PaddingValues, change: (Set<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val launchable = activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet() + blocked
        launchable.filter { it != activity.packageName }.map { name -> name to runCatching {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(name, 0)).toString()
        }.getOrDefault(name) }.sortedBy { it.second }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = SettingsSpace.small1, vertical = SettingsSpace.extraSmall4), placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp), singleLine = true,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        Text(stringResource(R.string.blocked_summary), Modifier.padding(horizontal = SettingsSpace.small4, vertical = SettingsSpace.extraSmall4),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { Category {
            val filtered = apps.filter { it.second.contains(query, true) || it.first.contains(query, true) }
            filtered.forEach { (name, label) ->
                CheckboxPreference(object : CheckboxPreferenceModel {
                    override val title = label
                    override val summary = { name }
                    override val icon: @Composable () -> Unit = { AppIcon(name, Modifier.size(32.dp)) }
                    override val checked = { name in blocked }
                    override val onCheckedChange: (Boolean) -> Unit = { checked -> change(if (checked) blocked + name else blocked - name) }
                })
            }
        } }
    }
}

private fun testConnection(client: AiClient) {
    val bitmap = Bitmap.createBitmap(220, 100, Bitmap.Config.ARGB_8888)
    val encoded = try {
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            drawText("ARKAI", 18f, 68f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 52f })
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } finally { bitmap.recycle() }
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", "Read the word in the image. Call connection_check with that word as text. Do not answer with plain text."))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$encoded")))))
    val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "connection_check").put("description", "Return the word read from the image")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("text", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("text"))))))
    val result = client.complete(messages, tools).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")
    check(result.getString("name") == "connection_check" && JSONObject(result.getString("arguments")).getString("text").trim().uppercase() == "ARKAI") {
        "The model did not pass the image and tool check"
    }
}
