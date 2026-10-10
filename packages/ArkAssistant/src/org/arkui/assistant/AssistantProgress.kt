/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.arkui.assistant

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun ProgressPanel(host: AssistantOverlay) {
    val task = TaskState.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(task.running) {
        while (task.running) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val elapsed = ((task.finishedAt.takeIf { it > 0 } ?: now) - task.startedAt).coerceAtLeast(0) / 1000
    val enlarged = host.preview && host.previewExpanded
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp - 112).coerceAtLeast(160).dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelReveal(!enlarged) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.appPackage.isNotBlank()) AppIcon(task.appPackage, Modifier.size(32.dp))
            else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(task.appLabel.ifBlank { stringResource(R.string.assistant_title) },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (task.background) R.string.background_mode else task.status),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("%d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.appPackage.isNotBlank()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(R.string.live_screen, R.string.execution_log).forEachIndexed { index, title ->
                SegmentedButton(selected = host.preview == (index == 0), onClick = { host.preview = index == 0 },
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(stringResource(title)) }
            }
        }
        } }
        AnimatedContent(host.preview && task.appPackage.isNotBlank() && task.running,
            transitionSpec = { AssistantMotion.content().using(SizeTransform(clip = false) { _, _ ->
                tween(AssistantMotion.expand, easing = AssistantMotion.spatial) }) }, label = "progress view") { preview ->
        if (preview) LiveScreen(host)
        else {
            val scroll = rememberScrollState()
            LaunchedEffect(task.events.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxWidth().heightIn(min = 100.dp,
                max = (LocalConfiguration.current.screenHeightDp * 0.43f).dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(task.prompt, style = MaterialTheme.typography.bodyLarge)
                task.events.forEachIndexed { index, event ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (index == task.events.lastIndex && task.running) Icons.Rounded.MoreHoriz else Icons.Rounded.Check,
                            null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(event.title, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        val time = ((event.time - task.startedAt).coerceAtLeast(0) / 1000)
                        Text("%d:%02d".format(time / 60, time % 60), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.running && !task.paused && task.confirmation == null) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(if (task.paused) Icons.Rounded.PauseCircle else Icons.Rounded.CheckCircle,
                        null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(task.detail.ifBlank { context.getString(task.status) }, Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        }
        PanelReveal(!enlarged) {
            Text(stringResource(R.string.step_count, task.step, task.limit), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TaskConfirmation(host)
        TaskControls(host)
    }
}

@Composable
private fun LiveScreen(host: AssistantOverlay) {
    val task = TaskState.current
    var frame by remember { mutableStateOf(PreviewFrame()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(task.startedAt, task.background) {
        // Poll only while this pane is composed. Closing it cancels refreshes; no frame is saved.
        while (isActive) {
            frame = withContext(Dispatchers.IO) { host.previewFrame() }
            now = SystemClock.elapsedRealtime()
            delay(650)
        }
    }
    val bitmap = frame.bitmap
    val previewHeight by animateDpAsState((LocalConfiguration.current.screenHeightDp *
        if (host.previewExpanded) 0.70f else 0.46f).dp,
        tween(AssistantMotion.expand, easing = AssistantMotion.spatial), label = "preview bounds")
    val imageAlpha by animateFloatAsState(if (bitmap == null) 0f else 1f,
        tween(AssistantMotion.enter), label = "preview first frame")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(previewHeight),
            contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.live_screen),
                Modifier.fillMaxHeight().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .clip(RoundedCornerShape(8.dp)).graphicsLayer { alpha = imageAlpha }, contentScale = ContentScale.Fit)
            else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (frame.error.isBlank()) LoadingIndicator(Modifier.size(40.dp))
                else Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(frame.error.ifBlank { stringResource(R.string.preview_connecting) }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (bitmap != null) Text(stringResource(R.string.preview_updated, ((now - frame.takenAt) / 1000).coerceAtLeast(0)),
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Spacer(Modifier.weight(1f))
            ToolIcon(if (host.previewExpanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                if (host.previewExpanded) R.string.shrink_preview else R.string.enlarge_preview) { host.previewExpanded = !host.previewExpanded }
        }
    }
}

@Composable
internal fun TaskConfirmation(host: AssistantOverlay) {
    val confirmation = TaskState.current.confirmation
    var question by remember { mutableStateOf(confirmation.orEmpty()) }
    if (confirmation != null) SideEffect { question = confirmation }
    PanelReveal(confirmation != null) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(question, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { host.command(AssistantService.APPROVE) }, Modifier.align(Alignment.End)) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.continue_task))
            }
        }
    }
}

@Composable
internal fun TaskControls(host: AssistantOverlay) {
    val task = TaskState.current
    if (!task.running) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        FilledTonalIconButton(onClick = { host.supplement = false; host.command(AssistantService.PAUSE) },
            enabled = task.confirmation == null && !host.takingOver) {
            AnimatedGlyph(if (task.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                stringResource(if (task.paused) R.string.resume else R.string.pause))
        }
        ToolIcon(Icons.Rounded.ChatBubbleOutline, R.string.supplement) { host.edit() }
        if (task.operating) FilledTonalIconButton(onClick = { host.command(AssistantService.TAKEOVER) }, enabled = !host.takingOver) {
            if (host.takingOver) LoadingIndicator(Modifier.size(24.dp))
            else Icon(Icons.Rounded.TouchApp, stringResource(R.string.take_over))
        }
        ToolIcon(Icons.Rounded.Stop, R.string.stop) { host.command(AssistantService.STOP) }
    }
}
