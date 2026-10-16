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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
internal fun AssistantTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xffaec6ff), onPrimary = Color(0xff003063), primaryContainer = Color(0xff144785),
        onPrimaryContainer = Color(0xffd9e5ff), secondary = Color(0xffbdc7dc), secondaryContainer = Color(0xff3d485c),
        tertiary = Color(0xff8ed3eb), tertiaryContainer = Color(0xff004e60),
        background = Color(0xff101318), surface = Color(0xff101318), onSurface = Color(0xffe1e2e8),
        surfaceContainerLow = Color(0xff191c21), surfaceContainer = Color(0xff1d2025), surfaceContainerHigh = Color(0xff272a30),
    ) else lightColorScheme(
        primary = Color(0xff216bea), onPrimary = Color.White, primaryContainer = Color(0xffdae6ff),
        onPrimaryContainer = Color(0xff143264), secondary = Color(0xff556177), secondaryContainer = Color(0xffdce5f8),
        tertiary = Color(0xff00677f), tertiaryContainer = Color(0xffb7eaff),
        background = Color(0xfff9faff), surface = Color(0xfff9faff), onSurface = Color(0xff1a1d24),
        surfaceContainerLow = Color(0xfff5f8ff), surfaceContainer = Color(0xffeff3fa), surfaceContainerHigh = Color(0xffedf2fa),
    )
    MaterialExpressiveTheme(colorScheme = colors,
        motionScheme = MotionScheme.expressive(), content = content)
}

class AssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        startService(Intent(this, AssistantService::class.java).setAction(
            if (intent.getBooleanExtra("progress", false)) AssistantService.PROGRESS else AssistantService.SHOW))
        finish()
    }
}

class AssistantShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        if (!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && ConfigStore(this).enabled) {
            startService(Intent(this, AssistantService::class.java).setAction(AssistantService.INVOKE)
                .putExtra(org.arkui.assistant.protocol.AssistantInvocation.EXTRA_ACTION,
                    intent.getIntExtra(org.arkui.assistant.protocol.AssistantInvocation.EXTRA_ACTION, 0))
                .putExtra(org.arkui.assistant.protocol.AssistantInvocation.EXTRA_ENTRY,
                    intent.getStringExtra(org.arkui.assistant.protocol.AssistantInvocation.EXTRA_ENTRY)))
        }
        finish()
    }
}

class AssistantPermissionActivity : ComponentActivity() {
    private var permission = ""
    private var delivered = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        permission = if (intent.getBooleanExtra("notifications", false)) Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(permission), 1)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
    override fun onPostResume() {
        super.onPostResume()
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) deliver(true)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        window.decorView.post { deliver(grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) }
    }
    private fun deliver(granted: Boolean) { if (!delivered) { delivered = true; val callback = result; result = null; callback?.invoke(granted); finishAndRemoveTask() } }
    companion object { internal var result: ((Boolean) -> Unit)? = null }
}

@Composable
internal fun ToolIcon(icon: ImageVector, label: Int, action: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(label)) } }, state = rememberTooltipState()) {
        IconButton(onClick = action) { AnimatedGlyph(icon, stringResource(label)) }
    }
}

@Composable
internal fun AssistantDrawer(host: AssistantOverlay) {
    val context = LocalContext.current
    val task = TaskState.current
    val configured = host.configured
    val voice = host.voice.state
    val voiceShown = voice.active || voice.error.isNotBlank()
    val voiceReveal by animateFloatAsState(if (voiceShown) 1f else 0f,
        tween(AssistantMotion.enter), label = "invocation to voice glow")
    val sheet = host.expanded && configured
    val backdropColor = animateColorAsState(if (voiceShown) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerLow, tween(180), label = "backdrop color").value
    val showComposer = !task.running || host.supplement
    val panelColor by animateColorAsState(if (sheet) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
        tween(AssistantMotion.enter), label = "sheet surface")
    val panelInset by animateDpAsState(if (sheet) 16.dp else 12.dp,
        tween(AssistantMotion.expand, easing = AssistantMotion.spatial), label = "sheet inset")
    val panelTop by animateDpAsState(if (sheet) 0.dp else 12.dp,
        tween(AssistantMotion.expand, easing = AssistantMotion.spatial), label = "sheet top")
    val drag by animateFloatAsState(host.dragOffset, if (host.dragging) snap() else
        spring(dampingRatio = 0.85f, stiffness = 650f), label = "drawer drag")
    LaunchedEffect(showComposer, voice.active, host.progress, host.textInput) {
        host.setFocus(voice.active || (showComposer && host.textInput && !host.progress))
    }
    val keyboardInset = WindowInsets.ime.getBottom(LocalDensity.current)
    SideEffect { host.setBackdrop(backdropColor.toArgb(), voiceShown, keyboardInset, voiceReveal) }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxWidth().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(voiceShown, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            .graphicsLayer { translationY = drag },
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                ((fadeIn(tween(160)) + slideInVertically(tween(220, easing = LinearOutSlowInEasing)) { it / 6 })
                    togetherWith (fadeOut(tween(90)) + slideOutVertically(tween(140)) { it / 12 }))
                    .using(SizeTransform(clip = false) { _, _ -> tween(220, easing = LinearOutSlowInEasing) })
            }, label = "voice panel") { listening ->
        if (listening && configured) VoicePanel(host) else {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxWidth()
            .background(panelColor, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .padding(horizontal = panelInset)
            .padding(top = panelTop, bottom = maxOf(28.dp, bottomInset + 12.dp)),
            verticalArrangement = Arrangement.Top) {
            if (!configured) {
                AssistantSetupPanel(onConfigure = { host.settings() }, onDismiss = { host.hide() })
            } else {
                DrawerHandle(host, sheet)
                PanelReveal(sheet) { Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawerHeader(host)
                    AnimatedContent(host.progress, transitionSpec = {
                        AssistantMotion.content().using(SizeTransform(clip = false) { _, _ ->
                            tween(AssistantMotion.expand, easing = AssistantMotion.spatial) })
                    }, label = "drawer page") { progress ->
                        if (progress) ProgressPanel(host) else ConversationPanel(host)
                    }
                } }
                PanelReveal(task.running && !sheet && (task.appLaunched || task.confirmation != null)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimatedContent(task.detail.ifBlank { context.getString(task.status) },
                            transitionSpec = { AssistantMotion.content() }, label = "execution status") { detail -> Text(detail,
                            Modifier.fillMaxWidth(), maxLines = if (task.confirmation != null) 3 else 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        TaskConfirmation(host)
                    }
                }
                PanelReveal(!task.running && !sheet && TaskState.messages.lastOrNull()?.user == false) {
                        Text(TaskState.messages.lastOrNull()?.text.orEmpty(), Modifier.fillMaxWidth().clickable { host.expanded = true }.padding(12.dp),
                            maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                }
                val footer = when { host.progress -> "none"; !showComposer -> "running"; task.running -> "supplement"; else -> "composer" }
                AnimatedContent(footer, contentAlignment = Alignment.BottomCenter, transitionSpec = {
                    AssistantMotion.content().using(SizeTransform(clip = false) { _, _ ->
                        tween(AssistantMotion.expand, easing = AssistantMotion.spatial) })
                }, label = "composer execution transition") { state ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state == "running" || state == "supplement") RunningBar(host)
                        if (state == "composer" || state == "supplement") Composer(host)
                    }
                }
            }
        }
        }
        }
    }
    }
}

@Composable
private fun DrawerHandle(host: AssistantOverlay, sheet: Boolean) {
    val height by animateDpAsState(if (sheet) 20.dp else 4.dp,
        tween(AssistantMotion.expand, easing = AssistantMotion.spatial), label = "drawer handle")
    val alpha by animateFloatAsState(if (sheet) 1f else 0f, tween(AssistantMotion.change), label = "handle opacity")
    Box(Modifier.fillMaxWidth().height(height).assistantDrag(host), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
    }
}

private fun Modifier.assistantDrag(host: AssistantOverlay) = pointerInput(host) {
    var distance = 0f
    fun settle() { host.dragging = false; host.dragOffset = 0f }
    detectVerticalDragGestures(onDragStart = { distance = 0f; host.dragging = true },
        onDragCancel = { settle() }, onDragEnd = {
            if (distance > 32.dp.toPx()) { if (host.expanded) host.collapse() else host.minimize() }
            if (distance < -32.dp.toPx()) {
                if (TaskState.current.running) host.openProgress() else host.expanded = true
            }
            settle()
        }) { change, amount ->
            change.consume(); distance += amount
            host.dragOffset = (distance * 0.35f).coerceIn(-64.dp.toPx(), 96.dp.toPx())
        }
}

@Composable
private fun DrawerHeader(host: AssistantOverlay) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (host.progress) ToolIcon(Icons.Rounded.ArrowBack, R.string.expand_messages) { host.closeProgress() }
        else Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(start = 4.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        AnimatedContent(stringResource(if (host.progress) R.string.task_progress else R.string.assistant_title),
            Modifier.weight(1f).padding(start = 8.dp), transitionSpec = { AssistantMotion.content() }, label = "drawer title") {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        if (!TaskState.current.running) ToolIcon(Icons.Rounded.AddComment, R.string.new_task) { host.command(AssistantService.NEW) }
        ToolIcon(Icons.Rounded.PictureInPictureAlt, R.string.minimize) { host.minimize() }
        ToolIcon(Icons.Rounded.KeyboardArrowDown, R.string.collapse_messages) { host.collapse() }
    }
}

@Composable
private fun RunningBar(host: AssistantOverlay) {
    val task = TaskState.current
    val capsule = task.appLaunched || task.background || !task.operating
    AnimatedContent(capsule, transitionSpec = { AssistantMotion.content() }, label = "execution toolbar") { capsule ->
    Surface(shape = RoundedCornerShape(28.dp), color = if (capsule && !host.expanded)
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (capsule) 8.dp else 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (capsule) Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                .clickable { host.openProgress() }.padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(task.appPackage to task.appLabel, transitionSpec = { AssistantMotion.content() }, label = "executing app") { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app.first.isNotBlank()) AppIcon(app.first, Modifier.size(24.dp))
                        else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(app.second.ifBlank { stringResource(R.string.assistant_title) }, Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            ExecutionAction(Icons.Rounded.ChatBubbleOutline, R.string.supplement, capsule) { host.edit() }
            if (!capsule) Spacer(Modifier.width(12.dp))
            ExecutionAction(if (task.paused) Icons.Rounded.PlayArrow else if (task.operating) Icons.Rounded.TouchApp else Icons.Rounded.Pause,
                if (task.paused) R.string.resume else if (task.operating) R.string.take_over else R.string.pause,
                capsule, enabled = !host.takingOver) {
                host.supplement = false
                host.command(if (task.paused || !task.operating) AssistantService.PAUSE else AssistantService.TAKEOVER)
            }
            if (!capsule) Spacer(Modifier.weight(1f))
            ExecutionAction(Icons.Rounded.Stop, R.string.stop, capsule, MaterialTheme.colorScheme.error) {
                host.command(AssistantService.STOP)
            }
            if (capsule) IconButton(onClick = { host.openProgress() }, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.OpenInFull, stringResource(R.string.task_progress), Modifier.size(18.dp))
            }
        }
    }
    }
}

@Composable
private fun ExecutionAction(icon: ImageVector, label: Int, stacked: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, action: () -> Unit) {
    val modifier = Modifier.height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = action)
    if (stacked) Column(modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        AnimatedGlyph(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        AnimatedContent(stringResource(label), transitionSpec = { AssistantMotion.content() }, label = "action label") {
            Text(it, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    } else Row(modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedGlyph(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint)
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
private fun Composer(host: AssistantOverlay) {
    var more by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(host.textInput) {
        if (host.textInput) { delay(80); focus.requestFocus(); keyboard?.show() }
    }
    Surface(modifier = Modifier.assistantDrag(host), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Rounded.CenterFocusStrong, R.string.tool_capture) {
                host.draft = context.getString(R.string.summarize_prompt); host.openKeyboard()
            }
            AnimatedContent(host.textInput, Modifier.weight(1f), transitionSpec = {
                fadeIn(tween(AssistantMotion.enter)) togetherWith fadeOut(tween(AssistantMotion.change))
            }, label = "input method") { textInput ->
            if (textInput) {
                BasicTextField(host.draft, { host.draft = it.take(6000) }, Modifier.fillMaxWidth().focusRequester(focus)
                    .padding(horizontal = 8.dp, vertical = 12.dp).heightIn(max = 96.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { host.submit() }),
                    decorationBox = { input -> Box {
                        if (host.draft.isEmpty()) Text(stringResource(R.string.prompt_hint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        input()
                    } })
            } else Box(Modifier.fillMaxWidth().height(48.dp).combinedClickable(role = Role.Button,
                onClick = { host.startVoice() }, onLongClick = { host.startHeldVoice() }), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.speak), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            }
            AnimatedContent(host.textInput, Modifier.size(48.dp), transitionSpec = {
                fadeIn(tween(AssistantMotion.enter)) togetherWith fadeOut(tween(AssistantMotion.change))
            }, label = "input action") { textInput ->
            if (!textInput) ToolIcon(Icons.Rounded.Keyboard, R.string.keyboard_input) { host.openKeyboard() }
            else FilledIconButton(onClick = { if (host.draft.isBlank()) host.startVoice() else host.submit() }, Modifier.size(48.dp)) {
                AnimatedGlyph(if (host.draft.isBlank()) Icons.Rounded.Mic else Icons.Rounded.ArrowUpward,
                    stringResource(if (host.draft.isBlank()) R.string.voice_input else R.string.send))
            }
            }
            Box {
                    ToolIcon(Icons.Rounded.Add, R.string.more) { more = true }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_task)) }, leadingIcon = { Icon(Icons.Rounded.AddComment, null) },
                            onClick = { more = false; host.command(AssistantService.NEW) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.expand_messages)) }, leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null) },
                            onClick = { more = false; host.closeProgress(); host.expanded = true })
                        if (TaskState.current.appPackage.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.block_current_app)) },
                            leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = {
                                more = false
                                val store = ConfigStore(context); store.setBlockedApps(store.blockedApps() + TaskState.current.appPackage)
                                host.command(AssistantService.STOP)
                            })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                            onClick = { more = false; host.settings() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.close)) }, leadingIcon = { Icon(Icons.Rounded.Close, null) },
                            onClick = { more = false; host.hide() })
                    }
            }
        }
    }
}

@Composable
private fun ConversationPanel(host: AssistantOverlay) {
    val context = LocalContext.current
    val messages = TaskState.messages
    val task = TaskState.current
    val initialCount = remember { messages.size }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, task.step) { delay(50); scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = (LocalConfiguration.current.screenHeightDp * 0.50f).dp)
                .verticalScroll(scroll).padding(horizontal = 4.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                messages.forEachIndexed { index, message -> key(index, message) {
                    val appeared = remember { MutableTransitionState(index < initialCount).apply { targetState = true } }
                    AnimatedVisibility(appeared, enter = fadeIn(tween(AssistantMotion.enter)) +
                        slideInVertically(tween(AssistantMotion.expand, easing = AssistantMotion.spatial)) { it.coerceAtMost(40) }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start) {
                        if (message.user) Surface(shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = MaterialTheme.colorScheme.primary) {
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
                } }
                PanelReveal(task.running) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(28.dp))
                        AnimatedContent(stringResource(task.status), Modifier.padding(start = 8.dp),
                            transitionSpec = { AssistantMotion.content() }, label = "conversation status") {
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (task.detail.isNotBlank()) Text(task.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(if (task.limit == 0) stringResource(R.string.step_count_unlimited, task.step)
                        else stringResource(R.string.step_count, task.step, task.limit), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                } }
                TaskConfirmation(host)
            }
    }
}

@Composable
private fun VoicePanel(host: AssistantOverlay) {
    var lastState by remember { mutableStateOf(host.voice.state) }
    val current = host.voice.state
    if (current.active || current.error.isNotBlank()) SideEffect { lastState = current }
    val state = if (current.active || current.error.isNotBlank()) current else lastState
    val primary = Color.White
    val context = LocalContext.current
    val level by animateFloatAsState(state.level, spring(0.8f, 400f), label = "microphone level")
    val height by animateDpAsState(if (state.error.isNotBlank()) 248.dp else 216.dp,
        tween(AssistantMotion.expand, easing = AssistantMotion.spatial), label = "speech state height")
    var samples by remember { mutableStateOf(List(36) { 0f }) }
    LaunchedEffect(state.active) {
        while (state.active) { samples = (samples.drop(1) + host.voice.state.level); delay(70) }
    }
    Box(Modifier.fillMaxWidth().height(height)
        .drawWithCache {
            val radius = maxOf(size.width * 0.8f, size.height * 1.5f)
            val blue = Color(AssistantGlowPalette.Blue)
            val glow = Brush.radialGradient(
                0f to Color(AssistantGlowPalette.BrightCyan), 0.35f to Color(AssistantGlowPalette.Cyan),
                0.62f to blue, 0.80f to blue.copy(alpha = 240f / 255f),
                0.94f to blue.copy(alpha = 80f / 255f), 1f to blue.copy(alpha = 0f),
                center = Offset(size.width / 2, radius), radius = radius)
            onDrawBehind { drawRect(glow) }
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(context.getString(R.string.cancel)) { host.voice.cancel(); true },
                CustomAccessibilityAction(context.getString(R.string.voice_done)) { host.voice.finish(); true },
                CustomAccessibilityAction(context.getString(R.string.keyboard_input)) {
                    host.voice.cancel(); host.openKeyboard(); true
                })
        }) {
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
        .padding(horizontal = 28.dp).padding(bottom = if (state.error.isNotBlank()) 16.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AnimatedContent(state.text.ifBlank { state.error.ifBlank { stringResource(when {
            state.transcribing -> R.string.voice_transcribing
            host.voiceHeld -> R.string.voice_release
            else -> R.string.voice_listening
        }) } },
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
            label = "speech transcription") { Text(it, color = primary, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        if (state.transcribing) CircularProgressIndicator(Modifier.size(24.dp), color = primary, strokeWidth = 2.dp)
        else if (state.error.isBlank()) Canvas(Modifier.fillMaxWidth(0.68f).height(20.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.voice_done)) { host.voice.finish() }) {
            val gap = size.width / samples.size
            samples.forEachIndexed { index, value ->
                val height = (4.dp.toPx() + value * size.height * 0.8f + level * 2.dp.toPx()).coerceAtMost(size.height)
                drawLine(primary, Offset(index * gap + gap / 2, (size.height - height) / 2),
                    Offset(index * gap + gap / 2, (size.height + height) / 2), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (state.error.isNotBlank()) CompositionLocalProvider(LocalContentColor provides primary) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Rounded.Keyboard, R.string.keyboard_input) { if (state.text.isNotBlank()) host.draft = state.text; host.voice.cancel(); host.openKeyboard() }
            ToolIcon(Icons.Rounded.Close, R.string.cancel) { host.voice.cancel() }
            TextButton(onClick = { host.voice.cancel(); host.settings() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(stringResource(R.string.settings)) }
        }
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
