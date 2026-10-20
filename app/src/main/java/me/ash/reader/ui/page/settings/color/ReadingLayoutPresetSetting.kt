package me.ash.reader.ui.page.settings.color

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingColumns
import me.ash.reader.infrastructure.preference.LocalReadingLayoutPreset
import me.ash.reader.infrastructure.preference.ReadingColumnsPreference
import me.ash.reader.infrastructure.preference.ReadingLayoutPresetPreference
import me.ash.reader.ui.component.base.RYDialog

/** Both appearance pages edit the same persisted settings, not independent copies. */
@Composable
fun ReadingLayoutPresetSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val columns = LocalReadingColumns.current
    val preset = LocalReadingLayoutPreset.current

    Column {
        LayoutChoiceSetting(
            title = stringResource(R.string.reading_columns),
            selectedDescription = columns.toDesc(context),
            description = stringResource(R.string.reading_columns_description),
            selectedValue = columns.value,
            options = ReadingColumnsPreference.values.map { option ->
                LayoutChoice(option.value, option.toDesc(context), option.description(context)) {
                    option.put(context, scope)
                }
            },
        )
        LayoutChoiceSetting(
            title = stringResource(R.string.layout_preset),
            selectedDescription = preset.toDesc(context),
            description = stringResource(R.string.layout_preset_description),
            selectedValue = preset.value,
            options = ReadingLayoutPresetPreference.values.map { option ->
                LayoutChoice(option.value, option.toDesc(context), option.description(context)) {
                    option.put(context, scope)
                }
            },
        )
    }
}

private data class LayoutChoice(
    val value: Int,
    val label: String,
    val description: String,
    val select: () -> Unit,
)

@Composable
private fun LayoutChoiceSetting(
    title: String,
    selectedDescription: String,
    description: String,
    selectedValue: Int,
    options: List<LayoutChoice>,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    // Static selection feedback: no ripple, animated radio control, or size transition.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { showDialog = true },
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = selectedDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RYDialog(
        visible = showDialog,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.selectableGroup()) {
                item {
                    Text(description)
                    Spacer(Modifier.height(16.dp))
                }
                items(options, key = { it.value }) { option ->
                    val selected = option.value == selectedValue
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.RadioButton,
                                onClick = {
                                    option.select()
                                    showDialog = false
                                },
                            )
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (selected) Icons.Outlined.RadioButtonChecked
                            else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        onDismissRequest = { showDialog = false },
    )
}
