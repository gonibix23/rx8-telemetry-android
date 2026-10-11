package com.gonibix.rx8.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A visual scale only: never an inferred engine limit, redline, average or peak. */
@Composable
internal fun RealRpmGauge(reading: DashboardReading, stopped: Boolean, modifier: Modifier = Modifier) {
    val suffix = "${reading.ecuId}_engine_rpm"
    val value = reading.value
    val current = reading.status == ObdReadingStatus.VALID
    val displayed = value?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—"
    Surface(modifier, color = CardColor, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Engine speed", color = MutedColor, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth().height(154.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().testTag("dashboard_gauge_$suffix").semantics {
                    contentDescription = "Engine speed dial, ECU ${reading.ecuId}"
                    stateDescription = when {
                        value == null -> "No measured value"
                        current -> "$displayed rpm, current"
                        else -> "$displayed rpm, stale last value"
                    }
                }) {
                    val center = Offset(size.width / 2f, size.height * 0.54f)
                    val radius = min(size.width * 0.44f, size.height * 0.48f)
                    val progress = value?.div(10_000.0)?.coerceIn(0.0, 1.0)
                    for (index in 0..44) {
                        val fraction = index / 44.0
                        val angle = Math.toRadians(140.0 + 260.0 * fraction)
                        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                        drawLine(
                            color = if (progress != null && fraction <= progress) AccentColor else BorderColor,
                            start = center + direction * (radius * 0.87f),
                            end = center + direction * radius,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(displayed, fontFamily = FontFamily.Monospace, fontSize = 38.sp, lineHeight = 42.sp,
                        color = if (current) WarmWhite else AccentColor, modifier = Modifier.testTag("dashboard_value_$suffix"))
                    Text("rpm", color = MutedColor, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Dial scale 0–10,000 rpm", color = MutedColor, style = MaterialTheme.typography.labelSmall)
            if (value != null && value > 10_000.0) Text("Value exceeds the dial scale", color = AccentColor, style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ReadingStatus(reading, !current, suffix)
                ReadingAge(reading, stopped, suffix)
            }
        }
    }
}
