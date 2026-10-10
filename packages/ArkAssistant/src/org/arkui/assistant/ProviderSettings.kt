/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.Footer
import java.util.UUID

@Composable
internal fun ProviderPicker(config: AssistantConfig, dismiss: () -> Unit, select: (String) -> Unit) {
    ChoiceDialog(stringResource(R.string.select_provider),
        config.providers.map { it.id to it.name }, config.providerId, dismiss, select)
}

@Composable
internal fun ModelPicker(provider: AiProvider, dismiss: () -> Unit, select: (String) -> Unit) {
    ChoiceDialog(stringResource(R.string.model),
        provider.models.map { it to it }, provider.model, dismiss, select)
}

@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, selected: String,
    dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { (id, name) ->
                ChoiceRow(name, id == selected, { select(id) })
            }
        } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, select: () -> Unit,
    subtitle: String = "", remove: (() -> Unit)? = null) {
    ListItem(modifier = Modifier.fillMaxWidth().selectable(selected = selected,
        role = Role.RadioButton, onClick = select),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isBlank()) null else ({ Text(subtitle) }),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = if (remove == null) null else ({
            IconButton(onClick = remove) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_model))
            }
        }))
}

@Composable
internal fun ProvidersScreen(config: AssistantConfig, save: (AssistantConfig) -> Unit) {
    val provider = config.provider
    val notConfigured = stringResource(R.string.not_configured)
    var editing by remember(provider.id) { mutableStateOf("") }
    var value by remember(provider.id) { mutableStateOf("") }
    var deleteProvider by remember(provider.id) { mutableStateOf(false) }
    var deleteModel by remember(provider.id) { mutableStateOf<String?>(null) }

    fun edit(field: String, current: String) { value = current; editing = field }
    fun update(next: AiProvider) { save(config.withProvider(next)) }

    Category(stringResource(R.string.select_provider)) {
        config.providers.forEach { entry ->
            ChoiceRow(entry.name, entry.id == provider.id,
                { save(config.copy(providerId = entry.id)) }, entry.model)
        }
        SettingsCell(R.string.add_provider, "") { edit("add_provider", "") }
    }
    Category(provider.name) {
        SettingsCell(R.string.provider_name, provider.name) { edit("name", provider.name) }
        SettingsCell(R.string.endpoint, provider.endpoint.ifBlank { notConfigured }) {
            edit("endpoint", provider.endpoint)
        }
        SettingsCell(R.string.api_key, stringResource(if (provider.key.isBlank()) R.string.not_configured else R.string.key_saved)) {
            edit("key", provider.key)
        }
        SettingsCell(R.string.delete_provider, "", enabled = config.providers.size > 1) { deleteProvider = true }
    }
    Category(stringResource(R.string.models)) {
        provider.models.forEach { model ->
            ChoiceRow(model, model == provider.model, { update(provider.copy(model = model)) },
                remove = { deleteModel = model })
        }
        SettingsCell(R.string.add_model, "") { edit("add_model", "") }
    }
    Footer(stringResource(R.string.provider_api_hint))

    if (editing.isNotEmpty()) {
        val secret = editing == "key"
        var reveal by remember(editing) { mutableStateOf(false) }
        val title = when (editing) {
            "add_provider" -> R.string.add_provider
            "name" -> R.string.provider_name
            "endpoint" -> R.string.endpoint
            "add_model" -> R.string.add_model
            else -> R.string.api_key
        }
        AlertDialog(onDismissRequest = { editing = "" }, title = { Text(stringResource(title)) },
            properties = DialogProperties(securePolicy = if (secret) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
            text = {
                OutlinedTextField(value, { value = it.take(if (editing == "add_model") 200 else 4096) },
                    Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        secret -> KeyboardType.Password
                        editing == "endpoint" -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    }), trailingIcon = {
                        if (secret) IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                stringResource(R.string.show_key))
                        }
                    })
            }, confirmButton = { TextButton(enabled = value.isNotBlank() || editing in listOf("key", "endpoint"),
                onClick = {
                    val text = value.trim()
                    when (editing) {
                        "add_provider" -> {
                            val next = AiProvider(id = UUID.randomUUID().toString(), name = text,
                                endpoint = "", key = "", models = emptyList(), model = "")
                            save(config.copy(providers = config.providers + next, providerId = next.id))
                        }
                        "name" -> update(provider.copy(name = text))
                        "endpoint" -> update(provider.copy(endpoint = text.trimEnd('/')))
                        "key" -> update(provider.copy(key = text))
                        "add_model" -> update(provider.copy(models = (provider.models + text).distinct(),
                            model = provider.model.ifBlank { text }))
                    }
                    editing = ""
                }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.cancel)) } })
    }
    if (deleteProvider) AlertDialog(onDismissRequest = { deleteProvider = false },
        title = { Text(stringResource(R.string.delete_provider)) },
        text = { Text(stringResource(R.string.delete_provider_message, provider.name)) },
        confirmButton = { TextButton(onClick = {
            val remaining = config.providers.filterNot { it.id == provider.id }
            if (remaining.isNotEmpty()) save(config.copy(providers = remaining, providerId = remaining.first().id))
            deleteProvider = false
        }) { Text(stringResource(R.string.delete_provider)) } },
        dismissButton = { TextButton(onClick = { deleteProvider = false }) { Text(stringResource(R.string.cancel)) } })
    deleteModel?.let { model ->
        AlertDialog(onDismissRequest = { deleteModel = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_message, model)) },
            confirmButton = { TextButton(onClick = {
                val remaining = provider.models.filterNot { it == model }
                update(provider.copy(models = remaining,
                    model = if (provider.model == model) remaining.firstOrNull().orEmpty() else provider.model))
                deleteModel = null
            }) { Text(stringResource(R.string.delete_model)) } },
            dismissButton = { TextButton(onClick = { deleteModel = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
