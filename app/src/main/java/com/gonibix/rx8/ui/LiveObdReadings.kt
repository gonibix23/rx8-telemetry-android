package com.gonibix.rx8.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.obd.ObdMetric
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdReading
import com.gonibix.rx8.obd.ObdReadingStatus
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.BorderColor
import com.gonibix.rx8.ui.theme.CardColor
import com.gonibix.rx8.ui.theme.MintColor
import com.gonibix.rx8.ui.theme.MutedColor
import com.gonibix.rx8.ui.theme.WarmWhite
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Presents vehicle measurements only; simulator data uses its own screen and models. */
@Composable
fun LiveObdReadings(snapshot: ObdSessionSnapshot, modifier: Modifier = Modifier) {
    val stopped = snapshot.phase == ObdPhase.STOPPED || snapshot.phase == ObdPhase.ERROR
    val busy = snapshot.phase == ObdPhase.INITIALIZING || snapshot.phase == ObdPhase.DISCOVERING
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (stopped) "Last vehicle readings" else "Live vehicle data",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MintColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, MintColor.copy(alpha = 0.3f)),
            ) {
                Text(
                    "OBD",
                    color = MintColor,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
        Surface(
            color = CardColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    Text(
                        snapshot.phase.title(),
                        color = if (snapshot.phase == ObdPhase.ERROR) AccentColor else WarmWhite,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("obd_session_status"),
                    )
                }
                Text(
                    snapshot.statusMessage,
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("obd_session_message"),
                )
                Text(
                    "Protocol: ${snapshot.protocolLabel ?: "Not identified yet"}",
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("obd_protocol"),
                )
                if (snapshot.phase == ObdPhase.LIVE) {
                    val rate = snapshot.effectiveResponsesPerSecond.takeIf { it.isFinite() && it >= 0 } ?: 0.0
                    Text(
                        String.format(Locale.ROOT, "Observed: %.1f responses/s · %d supported PIDs", rate, snapshot.supportedPidCount),
                        color = MutedColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("obd_observed_rate"),
                    )
                }
                if (stopped && snapshot.readings.isNotEmpty()) {
                    Text(
                        "These are retained readings, not current measurements.",
                        color = AccentColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        val ecuIds = (snapshot.ecus.map { it.ecuId } + snapshot.readings.map { it.ecuId }).distinct()
        ecuIds.forEach { ecuId ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ECU $ecuId",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("obd_ecu_$ecuId"),
                    )
                    Text("Source: OBD · Mode 01", color = MutedColor, fontSize = 10.sp)
                }
                BoxWithConstraints {
                    val columns = if (maxWidth >= 600.dp) 3 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ObdMetric.entries.chunked(columns).forEach { metrics ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                metrics.forEach { metric ->
                                    val reading = snapshot.readings.firstOrNull {
                                        it.ecuId == ecuId && it.metric == metric
                                    }
                                    ReadingCard(
                                        metric = metric,
                                        ecuId = ecuId,
                                        reading = reading,
                                        elapsedMillis = snapshot.elapsedMillis,
                                        stopped = stopped,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - metrics.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (ecuIds.isNotEmpty()) {
            Text(
                if (stopped) {
                    "Sample ages are shown at the time reading stopped. Reconnect and start reading for current values."
                } else {
                    "Sensors update one at a time. Each reading shows its own age and UTC timestamp."
                },
                color = MutedColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadingCard(
    metric: ObdMetric,
    ecuId: String,
    reading: ObdReading?,
    elapsedMillis: Long,
    stopped: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = if (stopped && reading?.status == ObdReadingStatus.VALID) {
        ObdReadingStatus.STALE
    } else {
        reading?.status
    }
    val value = reading?.value?.takeIf {
        it.isFinite() && (status == ObdReadingStatus.VALID || status == ObdReadingStatus.STALE)
    }
    val ageMillis = reading?.let { (elapsedMillis - it.elapsedMillis).coerceAtLeast(0L) }
    val suffix = "${ecuId}_${metric.name.lowercase(Locale.ROOT)}"
    Surface(
        modifier = modifier.heightIn(min = 172.dp),
        shape = RoundedCornerShape(16.dp),
        color = CardColor,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(metric.label, color = MutedColor, style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    value?.let { metric.formatValue(it) } ?: "—",
                    color = if (status == ObdReadingStatus.STALE) AccentColor else WarmWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 31.sp,
                    lineHeight = 36.sp,
                    modifier = Modifier.testTag("obd_value_$suffix"),
                )
                Text(
                    metric.unit,
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                if (status == ObdReadingStatus.VALID && value == null) "No valid value" else status.label(),
                color = if (status == ObdReadingStatus.VALID) MintColor else AccentColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag("obd_reading_status_$suffix"),
            )
            if (reading != null && status != ObdReadingStatus.UNSUPPORTED) {
                Text(
                    String.format(
                        Locale.ROOT,
                        if (stopped) "Age at stop: %.1f s" else "Age: %.1f s",
                        (ageMillis ?: 0L) / 1_000.0,
                    ),
                    color = MutedColor,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("obd_age_$suffix"),
                )
                Text(
                    SampleTime.format(Instant.ofEpochMilli(reading.observedAtEpochMillis)),
                    color = MutedColor,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("obd_time_$suffix"),
                )
            } else {
                Text(
                    if (status == ObdReadingStatus.UNSUPPORTED) "Not supported by this ECU" else "Waiting for a response",
                    color = MutedColor,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private val SampleTime: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)

private fun ObdMetric.formatValue(value: Double): String = when (this) {
    ObdMetric.THROTTLE_POSITION, ObdMetric.ENGINE_LOAD -> String.format(Locale.ROOT, "%.1f", value)
    else -> value.roundToInt().toString()
}

private fun ObdReadingStatus?.label(): String = when (this) {
    ObdReadingStatus.VALID -> "Current"
    ObdReadingStatus.UNSUPPORTED -> "Unsupported"
    ObdReadingStatus.NO_DATA -> "No data"
    ObdReadingStatus.TIMEOUT -> "Timed out"
    ObdReadingStatus.PARSE_ERROR -> "Unreadable response"
    ObdReadingStatus.ADAPTER_ERROR -> "Adapter error"
    ObdReadingStatus.STALE -> "Stale · last value"
    null -> "Awaiting reading"
}

private fun ObdPhase.title(): String = when (this) {
    ObdPhase.IDLE -> "Readings not started"
    ObdPhase.INITIALIZING -> "Preparing the adapter"
    ObdPhase.DISCOVERING -> "Finding protocol and ECU capabilities"
    ObdPhase.LIVE -> "Reading from the vehicle"
    ObdPhase.ERROR -> "Unable to continue reading"
    ObdPhase.STOPPED -> "Reading stopped"
}
