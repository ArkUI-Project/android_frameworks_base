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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class AssistantSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !resources.configuration.isNightModeActive
            isAppearanceLightNavigationBars = !resources.configuration.isNightModeActive
        }
        setContent { AssistantTheme { ConfigScreen(this, intent.action == "org.arkui.assistant.REMOVE") } }
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    var testClient by remember { mutableStateOf<AiClient?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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

    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surface,
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(when (page) { "apps" -> R.string.blocked_apps; "speech" -> R.string.voice_input; else -> R.string.settings })) },
            navigationIcon = { IconButton(onClick = { if (page == "main") activity.finish() else page = "main" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.close))
            } }, scrollBehavior = scroll, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }
    ) { padding ->
        AnimatedContent(page, label = "settings page") { selected ->
            if (selected == "apps") BlockedAppsScreen(activity, blocked, padding) { apps ->
                blocked = apps; store.setBlockedApps(apps)
                if (TaskState.current.running) activity.stopService(Intent(activity, AssistantService::class.java))
            } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (selected == "main") {
                    Surface(shape = RoundedCornerShape(28.dp), color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.enabled), Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                            Switch(enabled, onCheckedChange = { checked ->
                                if (!checked || config.valid()) {
                                    enabled = checked; store.setEnabled(checked)
                                    if (!checked) activity.stopService(Intent(activity, AssistantService::class.java))
                                    else if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                                } else message = activity.getString(R.string.invalid_config)
                            }, thumbContent = { Icon(if (enabled) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp)) })
                        }
                    }
                    SectionTitle(R.string.provider)
                    SettingsCell(Icons.Rounded.Dns, R.string.endpoint, config.endpoint, first = true) { edit("endpoint", config.endpoint) }
                    SettingsCell(Icons.Rounded.Memory, R.string.model, config.model) { edit("model", config.model) }
                    SettingsCell(Icons.Rounded.Key, R.string.api_key, activity.getString(if (config.key.isBlank()) R.string.not_configured else R.string.key_saved), last = true) { edit("key", config.key) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { test() }, Modifier.fillMaxWidth().height(52.dp), enabled = !testing) {
                            if (testing) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Wifi, null)
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.test_connection))
                        }
                    }
                    if (message.isNotBlank()) Text(message, Modifier.padding(12.dp).animateContentSize(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.screen_disclosure), Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionTitle(R.string.assistant_behavior)
                    SettingsSwitch(Icons.Rounded.TouchApp, R.string.control, R.string.control_summary, config.control, first = true) { save(config.copy(control = it)) }
                    SettingsSwitch(Icons.Rounded.Layers, R.string.background_mode, R.string.background_summary, config.background) { save(config.copy(background = it)) }
                    SettingsCell(Icons.Rounded.Block, R.string.blocked_apps, activity.getString(R.string.blocked_count, blocked.size)) { page = "apps" }
                    SettingsCell(Icons.Rounded.Mic, R.string.voice_input, activity.getString(if (config.speechEndpoint.isNotBlank()) R.string.voice_custom else R.string.voice_system), last = true) { page = "speech" }
                    SectionTitle(R.string.advanced)
                    SettingsCell(Icons.Rounded.Route, R.string.step_limit, config.steps.toString(), first = true) { editing = "steps" }
                    SettingsCell(Icons.Rounded.Assistant, R.string.default_assistant, "") {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                    }
                    SettingsCell(Icons.Rounded.DashboardCustomize, R.string.add_tile, "", last = true) {
                        activity.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(activity, AssistantTileService::class.java),
                            activity.getString(R.string.app_name), android.graphics.drawable.Icon.createWithResource(activity, R.drawable.ic_assistant), activity.mainExecutor) { }
                    }
                    Spacer(Modifier.height(20.dp))
                    SettingsCell(Icons.Rounded.DeleteOutline, R.string.remove, "", first = true, last = true) { remove = true }
                } else {
                    SectionTitle(R.string.voice_system)
                    SettingsCell(Icons.Rounded.RecordVoiceOver, R.string.voice_system,
                        activity.getString(if (SpeechRecognizer.isRecognitionAvailable(activity)) R.string.available else R.string.not_available), first = true, last = true) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                    }
                    Text(stringResource(R.string.voice_disclosure), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionTitle(R.string.voice_custom)
                    SettingsCell(Icons.Rounded.Dns, R.string.speech_endpoint, config.speechEndpoint.ifBlank { activity.getString(R.string.not_configured) }, first = true) { edit("speech_endpoint", config.speechEndpoint) }
                    SettingsCell(Icons.Rounded.Memory, R.string.model, config.speechModel) { edit("speech_model", config.speechModel) }
                    SettingsCell(Icons.Rounded.Key, R.string.api_key, activity.getString(if (config.speechKey.isBlank()) R.string.not_configured else R.string.key_saved), last = true) { edit("speech_key", config.speechKey) }
                }
            }
        }
    }
    if (editing == "steps") {
        var steps by remember { mutableStateOf(config.steps.toFloat()) }
        AlertDialog(onDismissRequest = { editing = "" }, icon = { Icon(Icons.Rounded.Route, null) }, title = { Text(stringResource(R.string.step_limit)) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(steps.toInt().toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Slider(steps, { steps = it }, valueRange = 4f..30f, steps = 25)
            } }, confirmButton = { TextButton(onClick = { save(config.copy(steps = steps.toInt())); editing = "" }) { Text(stringResource(R.string.save)) } },
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
private fun SectionTitle(label: Int) {
    Text(stringResource(label), Modifier.padding(start = 16.dp, top = 28.dp, bottom = 12.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingsCell(icon: ImageVector, title: Int, subtitle: String, first: Boolean = false, last: Boolean = false, action: () -> Unit) {
    Surface(onClick = action, shape = cellShape(first, last), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitch(icon: ImageVector, title: Int, subtitle: Int, checked: Boolean, first: Boolean = false, last: Boolean = false, change: (Boolean) -> Unit) {
    Surface(shape = cellShape(first, last), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable { change(!checked) }.padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = change)
        }
    }
}

private fun cellShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 24.dp else 4.dp, topEnd = if (first) 24.dp else 4.dp,
    bottomStart = if (last) 24.dp else 4.dp, bottomEnd = if (last) 24.dp else 4.dp)

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
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), placeholder = { Text(stringResource(R.string.search_apps)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp), singleLine = true,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
        Text(stringResource(R.string.blocked_summary), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val filtered = apps.filter { it.second.contains(query, true) || it.first.contains(query, true) }
            filtered.forEachIndexed { index, (name, label) ->
                Surface(onClick = { change(if (name in blocked) blocked - name else blocked + name) },
                    shape = cellShape(index == 0, index == filtered.lastIndex), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(name, Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(name in blocked, onCheckedChange = { checked -> change(if (checked) blocked + name else blocked - name) })
                    }
                }
            }
        }
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
