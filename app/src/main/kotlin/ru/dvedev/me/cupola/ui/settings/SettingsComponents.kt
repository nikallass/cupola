package ru.dvedev.me.cupola.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.dvedev.me.cupola.R
import ru.dvedev.me.cupola.ui.components.Label
import ru.dvedev.me.cupola.ui.components.ZoneDivider
import ru.dvedev.me.cupola.ui.components.ZoneHeader
import ru.dvedev.me.cupola.ui.theme.CupolaDimens
import ru.dvedev.me.cupola.ui.theme.CupolaTheme

@Composable
fun SettingsSection(title: String) {
    ZoneDivider()
    ZoneHeader(left = { Label(title) })
}

/** A settings row: title with a «?» that opens [help], and a trailing control. */
@Composable
fun SettingRow(title: String, help: String?, trailing: @Composable () -> Unit) {
    val c = CupolaTheme.colors
    var showHelp by remember { mutableStateOf(false) }
    val titleRow: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = CupolaTheme.type.body, color = c.ink)
            if (help != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(18.dp).clip(CircleShape).border(1.dp, c.line2, CircleShape).clickable { showHelp = true },
                    contentAlignment = Alignment.Center,
                ) { Text("?", style = CupolaTheme.type.axis, color = c.dim) }
            }
        }
    }
    // phones (< 420 dp): the control goes under the title, right-aligned, so −/+ and long
    // values are never squeezed off the screen
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 8.dp)) {
                titleRow()
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CupolaDimens.paddingH, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                titleRow()
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
    }
    if (showHelp && help != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(title) },
            text = { Text(help, style = CupolaTheme.type.body) },
        )
    }
}

@Composable
fun SwitchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = CupolaTheme.colors
    SettingRow(title, help) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.violetInk, checkedThumbColor = c.panel, uncheckedTrackColor = c.panel2, uncheckedThumbColor = c.mut, uncheckedBorderColor = c.line2),
        )
    }
}

/** Picks one of [options] from a dialog list. */
@Composable
fun <T> ChoiceRow(title: String, help: String?, value: T, options: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val c = CupolaTheme.colors
    var open by remember { mutableStateOf(false) }
    SettingRow(title, help) {
        Text(
            label(value),
            style = CupolaTheme.type.body,
            color = c.violetInk,
            modifier = Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(title) },
            text = {
                // options as outlined chips (owner 2026‑09‑16: the pale fill alone was lost on the dialog)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { o ->
                        val selected = o == value
                        val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        Text(
                            label(o),
                            style = CupolaTheme.type.body,
                            color = if (selected) c.violetInk else c.ink,
                            modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, if (selected) c.violet else c.goldInk.copy(alpha = 0.55f), shape)
                                .clickable { onSelect(o); open = false }.background(if (selected) c.panel2 else c.panel).padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

/** −/+ control for numeric settings. */
@Composable
fun StepperRow(title: String, help: String?, valueText: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    val c = CupolaTheme.colors
    SettingRow(title, help) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", onDecrement)
            Text(valueText, style = CupolaTheme.type.stats, color = c.ink, modifier = Modifier.widthIn(min = 76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            StepButton("+", onIncrement)
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    val c = CupolaTheme.colors
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(c.panel).border(1.dp, c.line2, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = CupolaTheme.type.button, color = c.mut) }
}
