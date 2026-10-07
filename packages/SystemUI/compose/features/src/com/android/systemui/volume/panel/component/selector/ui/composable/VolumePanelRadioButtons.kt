/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume.panel.component.selector.ui.composable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Connected MD3 Expressive options: icon, label and selected state share one surface. */
@Composable
fun VolumePanelRadioButtonBar(
    modifier: Modifier = Modifier,
    indicatorBackgroundPadding: Dp =
        VolumePanelRadioButtonBarDefaults.DefaultIndicatorBackgroundPadding,
    spacing: Dp = VolumePanelRadioButtonBarDefaults.DefaultSpacing,
    labelIndicatorBackgroundSpacing: Dp =
        VolumePanelRadioButtonBarDefaults.DefaultLabelIndicatorBackgroundSpacing,
    indicatorCornerSize: CornerSize =
        CornerSize(VolumePanelRadioButtonBarDefaults.DefaultIndicatorCornerRadius),
    indicatorBackgroundCornerSize: CornerSize =
        CornerSize(VolumePanelRadioButtonBarDefaults.DefaultIndicatorBackgroundCornerRadius),
    colors: VolumePanelRadioButtonBarColors = VolumePanelRadioButtonBarDefaults.defaultColors(),
    content: VolumePanelRadioButtonBarScope.() -> Unit,
) {
    val scope = VolumePanelRadioButtonBarScopeImpl().apply(content)
    require(scope.hasSelectedItem) { "At least one item should be selected" }
    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((index, item) in scope.items.withIndex()) {
            val isSelected = index == scope.selectedIndex
            val background by animateColorAsState(
                if (isSelected) colors.indicatorColor else colors.indicatorBackgroundColor, label = "OptionColor")
            val corner by animateDpAsState(if (isSelected) 28.dp else 20.dp, label = "OptionShape")
            Surface(onClick = item.onItemSelected,
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    selected = isSelected
                    item.contentDescription?.let { contentDescription = it }
                },
                shape = RoundedCornerShape(corner), color = background) {
                Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                    CompositionLocalProvider(LocalContentColor provides colors.getIconColor(isSelected)) {
                        Row { item.icon(this) }
                    }
                    CompositionLocalProvider(LocalContentColor provides colors.getLabelColor(isSelected)) {
                        Row { item.label(this) }
                    }
                }
            }
        }
    }
}

data class VolumePanelRadioButtonBarColors(
    /** Color of the indicator. */
    val indicatorColor: Color,
    /** Color of the indicator background. */
    val indicatorBackgroundColor: Color,
    /** Color of the icon. */
    val iconColor: Color,
    /** Color of the icon when it's selected. */
    val selectedIconColor: Color,
    /** Color of the label. */
    val labelColor: Color,
    /** Color of the label when it's selected. */
    val selectedLabelColor: Color,
)

private fun VolumePanelRadioButtonBarColors.getIconColor(selected: Boolean): Color =
    if (selected) selectedIconColor else iconColor

private fun VolumePanelRadioButtonBarColors.getLabelColor(selected: Boolean): Color =
    if (selected) selectedLabelColor else labelColor

object VolumePanelRadioButtonBarDefaults {

    val DefaultIndicatorBackgroundPadding = 8.dp
    val DefaultSpacing = 8.dp
    val DefaultLabelIndicatorBackgroundSpacing = 6.dp
    val DefaultIndicatorCornerRadius = 20.dp
    val DefaultIndicatorBackgroundCornerRadius = 28.dp

    /**
     * Returns the default VolumePanelRadioButtonBar colors.
     *
     * @param indicatorColor is the color of the indicator
     * @param indicatorBackgroundColor is the color of the indicator background
     */
    @Composable
    fun defaultColors(
        indicatorColor: Color = MaterialTheme.colorScheme.primary,
        indicatorBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedIconColor: Color = MaterialTheme.colorScheme.onPrimary,
        labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedLabelColor: Color = MaterialTheme.colorScheme.onPrimary,
    ): VolumePanelRadioButtonBarColors =
        VolumePanelRadioButtonBarColors(
            indicatorColor = indicatorColor,
            indicatorBackgroundColor = indicatorBackgroundColor,
            iconColor = iconColor,
            selectedIconColor = selectedIconColor,
            labelColor = labelColor,
            selectedLabelColor = selectedLabelColor,
        )
}

/** [VolumePanelRadioButtonBar] content scope. Use [item] to add more items. */
interface VolumePanelRadioButtonBarScope {

    /**
     * Adds a single item to the radio button group.
     *
     * @param isSelected true when the item is selected and false the otherwise
     * @param onItemSelected is called when the item is selected
     * @param icon of the to show in the indicator bar
     * @param label to show below the indicator bar for the corresponding [icon]
     */
    fun item(
        isSelected: Boolean,
        onItemSelected: () -> Unit,
        icon: @Composable RowScope.() -> Unit = Empty,
        label: @Composable RowScope.() -> Unit = Empty,
        contentDescription: String? = null,
    )
}

private val Empty: @Composable RowScope.() -> Unit = {}

private class VolumePanelRadioButtonBarScopeImpl : VolumePanelRadioButtonBarScope {

    var selectedIndex: Int = UNSET_INDEX
        private set

    val hasSelectedItem: Boolean
        get() = selectedIndex != UNSET_INDEX

    private val mutableItems: MutableList<Item> = mutableListOf()
    val items: List<Item> = mutableItems

    override fun item(
        isSelected: Boolean,
        onItemSelected: () -> Unit,
        icon: @Composable RowScope.() -> Unit,
        label: @Composable RowScope.() -> Unit,
        contentDescription: String?,
    ) {
        require(!isSelected || !hasSelectedItem) { "Only one item should be selected at a time" }
        if (isSelected) {
            selectedIndex = mutableItems.size
        }
        mutableItems.add(
            Item(
                onItemSelected = onItemSelected,
                icon = icon,
                label = label,
                contentDescription = contentDescription,
            )
        )
    }

    private companion object {
        const val UNSET_INDEX = -1
    }
}

private class Item(
    val onItemSelected: () -> Unit,
    val icon: @Composable RowScope.() -> Unit,
    val label: @Composable RowScope.() -> Unit,
    val contentDescription: String?,
)
