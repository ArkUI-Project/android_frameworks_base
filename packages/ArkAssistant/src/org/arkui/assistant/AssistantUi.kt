/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.arkui.assistant

import android.Manifest
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
internal fun AssistantTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialExpressiveTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        motionScheme = MotionScheme.expressive(), content = content)
}

class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.SHOW))
        finish()
    }
}

class AssistantPermissionActivity : ComponentActivity() {
    private var permission = ""
    private var delivered = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permission = if (intent.getBooleanExtra("notifications", false)) Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(permission), 1)
    }
    override fun onPostResume() {
        super.onPostResume()
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) deliver(true)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        window.decorView.post { deliver(grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) }
    }
    private fun deliver(granted: Boolean) { if (!delivered) { delivered = true; val callback = result; result = null; callback?.invoke(granted); finish() } }
    companion object { internal var result: ((Boolean) -> Unit)? = null }
}

@Composable
internal fun ToolIcon(icon: ImageVector, label: Int, action: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(label)) } }, state = rememberTooltipState()) {
        IconButton(onClick = action) { Icon(icon, stringResource(label)) }
    }
}

@Composable
internal fun AssistantDrawer(host: AssistantOverlay) {
    val context = LocalContext.current
    val task = TaskState.current
    val configured = host.configured
    val voice = host.voice.state
    val voiceShown = voice.active || voice.error.isNotBlank()
    val sheet = host.expanded && !voiceShown && configured
    val backdropColor = if (voiceShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
    val showComposer = !task.running || host.supplement
    LaunchedEffect(showComposer, voice.active) { host.setFocus(showComposer && !voice.active) }
    SideEffect { host.setBackdrop(backdropColor.toArgb(), voiceShown) }
    Column(Modifier.fillMaxWidth().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .animateContentSize(tween(180, easing = LinearOutSlowInEasing))
            .background(if (sheet) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(horizontal = if (sheet || voiceShown) 16.dp else 12.dp)
            .padding(top = 16.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!configured) Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.assistant_title), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                        ToolIcon(Icons.Rounded.Close, R.string.close) { host.hide() }
                    }
                    Text(stringResource(R.string.configure_first), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { host.settings() }, Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.configure))
                    }
                }
            } else if (voiceShown) {
                VoicePanel(host)
            } else {
                if (host.expanded) ConversationPanel(host)
                if (task.running && !host.expanded) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!task.paused && task.confirmation == null) LoadingIndicator(Modifier.size(24.dp))
                        else Icon(Icons.Rounded.PauseCircle, null, Modifier.size(24.dp))
                        Text(task.confirmation ?: task.detail.ifBlank { context.getString(task.status) },
                            Modifier.weight(1f).padding(start = 8.dp), maxLines = if (task.confirmation != null) 3 else 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (task.confirmation != null) FilledTonalIconButton(onClick = { host.command(AssistantService.APPROVE) }) {
                            Icon(Icons.Rounded.Check, stringResource(R.string.continue_task))
                        }
                    }
                }
                if (!task.running && !host.expanded && TaskState.messages.lastOrNull()?.user == false) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(TaskState.messages.last().text, Modifier.fillMaxWidth().clickable { host.expanded = true }.padding(16.dp),
                            maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (task.running) RunningBar(host)
                if (showComposer) {
                    if (TaskState.messages.isEmpty() && !host.expanded) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.summarize to R.string.summarize_prompt, R.string.translate to R.string.translate_prompt,
                            R.string.operate to R.string.operate_prompt).forEach { (title, prompt) ->
                            SuggestionChip(onClick = { host.mode = if (title == R.string.operate) "operate" else "screen"; host.draft = context.getString(prompt) },
                                label = { Text(stringResource(title)) }, shape = RoundedCornerShape(20.dp), border = null,
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)))
                        }
                        SuggestionChip(onClick = { host.mode = "chat"; host.expanded = true }, label = { Text(stringResource(R.string.chat_mode)) },
                            shape = RoundedCornerShape(20.dp), border = null,
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)))
                        ToolIcon(Icons.Rounded.Close, R.string.close) { host.hide() }
                    }
                    if (TaskState.messages.isNotEmpty() || host.expanded || host.mode == "operate") InputOptions(host)
                    Composer(host)
                }
            }
        }
    }
}

@Composable
private fun RunningBar(host: AssistantOverlay) {
    val task = TaskState.current
    Surface(shape = RoundedCornerShape(32.dp), color = if (host.expanded) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
        shadowElevation = if (host.expanded) 0.dp else 6.dp) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (task.appPackage.isNotBlank()) AppIcon(task.appPackage, Modifier.size(28.dp))
            else Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(start = 6.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(task.appLabel.ifBlank { stringResource(R.string.assistant_title) }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (task.background) R.string.background_mode else task.status),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BarAction(Icons.Rounded.ChatBubbleOutline, R.string.supplement) { host.edit() }
            BarAction(Icons.Rounded.TouchApp, R.string.take_over) { host.command(AssistantService.TAKEOVER) }
            BarAction(Icons.Rounded.Stop, R.string.stop) { host.command(AssistantService.STOP) }
            ToolIcon(Icons.Rounded.ExpandLess, R.string.expand_messages) { host.expanded = !host.expanded }
            ToolIcon(Icons.Rounded.PictureInPictureAlt, R.string.minimize) { host.hide() }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: Int, action: () -> Unit) {
    Column(Modifier.width(44.dp).height(48.dp).clickable(onClick = action), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, stringResource(label), Modifier.size(20.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(packageName) { runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() }
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, modifier = modifier,
        update = { it.setImageDrawable(drawable) })
}

@Composable
private fun InputOptions(host: AssistantOverlay) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }, enabled = !TaskState.current.running, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Icon(if (host.mode == "chat") Icons.Rounded.ChatBubbleOutline else Icons.Rounded.CenterFocusStrong, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(when (host.mode) { "chat" -> R.string.chat_mode; "operate" -> R.string.operate; else -> R.string.screen_mode }))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                listOf("chat" to R.string.chat_mode, "screen" to R.string.screen_mode, "operate" to R.string.operate).forEach { (mode, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { host.mode = mode; menu = false; if (mode == "chat") host.expanded = true },
                        trailingIcon = { if (mode == host.mode) Icon(Icons.Rounded.Check, null) })
                }
            }
        }
        if (host.mode == "operate") FilterChip(host.background, onClick = { host.background = !host.background },
            enabled = !TaskState.current.running, label = { Text(stringResource(R.string.background_mode)) }, leadingIcon = { Icon(Icons.Rounded.Layers, null, Modifier.size(16.dp)) })
        Spacer(Modifier.weight(1f))
        if (TaskState.messages.isNotEmpty() && !host.expanded) ToolIcon(Icons.Rounded.OpenInFull,
            R.string.expand_messages) { host.expanded = !host.expanded }
        ToolIcon(Icons.Rounded.Close, R.string.close) { host.hide() }
    }
}

@Composable
private fun Composer(host: AssistantOverlay) {
    var more by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.97f), shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Rounded.CenterFocusStrong, R.string.tool_capture) { host.mode = "screen"; host.draft = context.getString(R.string.summarize_prompt) }
            Row(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                BasicTextField(host.draft, { host.draft = it.take(6000) }, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp).heightIn(max = 96.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { host.submit() }),
                    decorationBox = { input -> Box {
                        if (host.draft.isEmpty()) Text(stringResource(R.string.prompt_hint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        input()
                    } })
                Box {
                    ToolIcon(Icons.Rounded.Add, R.string.more) { more = true }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_task)) }, leadingIcon = { Icon(Icons.Rounded.AddComment, null) },
                            onClick = { more = false; host.command(AssistantService.NEW) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_mode)) }, leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null) },
                            enabled = !TaskState.current.running,
                            onClick = { more = false; host.mode = "chat"; host.expanded = true })
                        if (TaskState.current.appPackage.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.block_current_app)) },
                            leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                more = false
                                val store = ConfigStore(context); store.setBlockedApps(store.blockedApps() + TaskState.current.appPackage)
                                host.command(AssistantService.STOP)
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                            onClick = { more = false; host.settings() })
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = { if (host.draft.isBlank()) host.startVoice() else host.submit() }, Modifier.size(48.dp)) {
                Icon(if (host.draft.isBlank()) Icons.Rounded.Mic else Icons.Rounded.ArrowUpward,
                    stringResource(if (host.draft.isBlank()) R.string.voice_input else R.string.send))
            }
        }
    }
}

@Composable
private fun ConversationPanel(host: AssistantOverlay) {
    val context = LocalContext.current
    val messages = TaskState.messages
    val task = TaskState.current
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, task.step) { delay(50); scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.assistant_title), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                if (task.running) ToolIcon(if (task.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (task.paused) R.string.resume else R.string.pause) { host.supplement = false; host.command(AssistantService.PAUSE) }
                ToolIcon(Icons.Rounded.AddComment, R.string.new_task) { host.command(AssistantService.NEW) }
                ToolIcon(Icons.Rounded.ExpandMore, R.string.collapse_messages) { host.expanded = false }
            }
            Column(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = (LocalConfiguration.current.screenHeightDp * 0.48f).dp)
                .verticalScroll(scroll).padding(horizontal = 4.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                messages.forEach { message ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start) {
                        if (message.user) Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(message.text, Modifier.widthIn(max = 300.dp).padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            Row {
                                ToolIcon(Icons.Rounded.ContentCopy, R.string.copy) { context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("ArkAI", message.text)) }
                                ToolIcon(Icons.Rounded.Share, R.string.share) {
                                    host.hide()
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, message.text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                    }
                }
                if (task.running) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        Text(stringResource(task.status), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    if (task.detail.isNotBlank()) Text(task.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.step_count, task.step, task.limit), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}

@Composable
private fun VoicePanel(host: AssistantOverlay) {
    val state = host.voice.state
    val primary = MaterialTheme.colorScheme.onPrimary
    val level by animateFloatAsState(state.level, spring(0.8f, 400f), label = "microphone level")
    var samples by remember { mutableStateOf(List(36) { 0f }) }
    LaunchedEffect(state.active) {
        while (state.active) { samples = (samples.drop(1) + host.voice.state.level); delay(70) }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 184.dp).padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(48.dp))
        AnimatedContent(state.text.ifBlank { state.error.ifBlank { stringResource(if (state.transcribing) R.string.voice_transcribing else R.string.voice_listening) } },
            label = "speech transcription") { Text(it, color = primary, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (state.transcribing) LoadingIndicator(Modifier.size(32.dp))
        else Canvas(Modifier.fillMaxWidth(0.78f).height(32.dp)) {
            val gap = size.width / samples.size
            samples.forEachIndexed { index, value ->
                val height = (4.dp.toPx() + value * size.height * 0.8f + level * 2.dp.toPx()).coerceAtMost(size.height)
                drawLine(primary, Offset(index * gap + gap / 2, (size.height - height) / 2),
                    Offset(index * gap + gap / 2, (size.height + height) / 2), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        CompositionLocalProvider(LocalContentColor provides primary) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Rounded.Keyboard, R.string.keyboard_input) { if (state.text.isNotBlank()) host.draft = state.text; host.voice.cancel(); host.setFocus(true) }
            ToolIcon(Icons.Rounded.Close, R.string.cancel) { host.voice.cancel() }
            if (state.error.isNotBlank()) FilledTonalButton(onClick = { host.voice.cancel(); host.settings() }) {
                Text(stringResource(R.string.settings))
            } else ToolIcon(Icons.Rounded.Check, R.string.voice_done) { host.voice.finish() }
        }
        }
    }
}

class AssistantTileService : TileService() {
    override fun onStartListening() { qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() } }
    override fun onClick() {
        unlockAndRun {
            startActivityAndCollapse(PendingIntent.getActivity(this, 741, Intent(this, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }
}
