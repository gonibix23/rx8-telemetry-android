package com.gonibix.rx8.dashboard

import androidx.compose.foundation.Canvas

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.util.Locale


@Composable
internal fun CustomSensorCard(reading: DashboardReading, config: DashboardCardConfig, stopped: Boolean,
    onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val currentEdit by rememberUpdatedState(onEdit)
    val suffix = "${reading.ecuId}_${reading.metric.name.lowercase(Locale.ROOT)}"
    val large = config.size == DashboardCardSize.LARGE
    val value = metricDisplayValue(reading)
    val color = if (reading.status == ObdReadingStatus.VALID) WarmWhite else AccentColor
    val progress = metricProgress(reading)
    Surface(modifier.heightIn(min = if (large) 220.dp else 168.dp)
        .pointerInput(Unit) { detectTapGestures(onLongPress = { currentEdit() }) }
        .semantics { onLongClick("Customize ${reading.metric.label}") { currentEdit(); true } }
        .testTag("dashboard_card_$suffix"), color = CardColor, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(if (large) 18.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(reading.metric.label, modifier = Modifier.weight(1f), color = MutedColor, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onEdit, modifier = Modifier.testTag("dashboard_edit_$suffix")
                    .semantics { contentDescription = "Customize ${reading.metric.label}, ECU ${reading.ecuId}" }) { Text("Edit") }
            }
            if (config.style == DashboardCardStyle.DIAL) {
                Box(Modifier.fillMaxWidth().height(if (large) 180.dp else 130.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize().testTag("dashboard_dial_$suffix")) {
                        val diameter = minOf(size.width, size.height) * 0.92f
                        val offset = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                        drawArc(BorderColor, 140f, 260f, false, offset, Size(diameter, diameter), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                        if (progress != null) drawArc(if (reading.status == ObdReadingStatus.VALID) MintColor else AccentColor,
                            140f, 260f * progress, false, offset, Size(diameter, diameter), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                    }
                    SensorNumber(value, reading.metric.unit, large, color, suffix)
                }
            } else {
                SensorNumber(value, reading.metric.unit, large, color, suffix)
                if (config.style == DashboardCardStyle.BAR) Canvas(Modifier.fillMaxWidth().height(14.dp).testTag("dashboard_bar_$suffix")) {
                    drawRoundRect(BorderColor)
                    if (progress != null) drawRect(if (reading.status == ObdReadingStatus.VALID) MintColor else AccentColor,
                        size = Size(size.width * progress, size.height))
                }
            }
            if (config.style != DashboardCardStyle.DIGITAL) Text(
                "Scale ${scaleNumber(reading.metric.minimumValue)}–${scaleNumber(reading.metric.maximumValue)} ${reading.metric.unit}",
                color = MutedColor, fontSize = 10.sp)
            ReadingStatus(reading, reading.status != ObdReadingStatus.VALID, suffix)
            ReadingAge(reading, stopped, suffix)
            Text("ECU ${reading.ecuId}", color = MutedColor, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SensorNumber(value: String, unit: String, large: Boolean, color: androidx.compose.ui.graphics.Color, suffix: String) {
    Column {
        val digits = value.length.coerceAtLeast(4)
        val size = (if (large) 66f else 44f).coerceAtMost((if (large) 460f else 210f) / digits).coerceAtLeast(20f)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size + 5).sp,
            modifier = Modifier.testTag("dashboard_value_$suffix"))
        Text(unit, color = MutedColor, style = MaterialTheme.typography.bodySmall)
    }
}
private fun scaleNumber(value: Double): String = String.format(Locale.ROOT, if (value % 1.0 == 0.0) "%.0f" else "%.2f", value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditor(config: DashboardCardConfig, position: Int, total: Int, overlayCount: Int,
    onUpdate: (DashboardCardConfig) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var draft by remember(config) { mutableStateOf(config) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Customize ${config.metric.label}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashboardCardStyle.entries.forEach { style -> FilterChip(draft.style == style,
                        { draft = draft.copy(style = style) }, label = { Text(style.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("card_style_${style.name}")) }
                }
                Text("Card size")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashboardCardSize.entries.forEach { size -> FilterChip(draft.size == size,
                        { draft = draft.copy(size = size) }, label = { Text(size.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("card_size_${size.name}")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.showInOverlay, { draft = draft.copy(showInOverlay = it) },
                        enabled = draft.showInOverlay || config.showInOverlay || overlayCount < DashboardLayoutCodec.MAX_OVERLAY_METRICS,
                        modifier = Modifier.testTag("card_overlay_selected"))
                    Text("Show in floating panel")
                }
                Text("Up to six floating sensors. Their card size controls the text size. Dial and bar scales are display domains, not engine safety limits.",
                    style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ onMove(-1) }, enabled = position > 0, modifier = Modifier.testTag("card_move_up")) { Text("Move up") }
                    TextButton({ onMove(1) }, enabled = position < total - 1, modifier = Modifier.testTag("card_move_down")) { Text("Move down") }
                }
                TextButton({ onRemove(); onDismiss() }, modifier = Modifier.testTag("card_remove")) { Text("Remove sensor") }
            }
        },
        confirmButton = { TextButton({ onUpdate(draft); onDismiss() }, modifier = Modifier.testTag("card_save")) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
