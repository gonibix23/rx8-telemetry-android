package com.gonibix.rx8.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.ReadingStatus
import com.gonibix.rx8.telemetry.SimulationScenario
import com.gonibix.rx8.telemetry.TelemetryMetric
import com.gonibix.rx8.telemetry.TelemetryReading
import com.gonibix.rx8.telemetry.TelemetrySnapshot
import com.gonibix.rx8.ui.theme.*
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun SimulatorDashboard(
    snapshot: TelemetrySnapshot,
    onToggleSimulation: () -> Unit,
    onReset: () -> Unit,
    onScenarioSelected: (SimulationScenario) -> Unit,
    onOpenDashboard: (() -> Unit)? = null,
) {
    val connected = snapshot.connection == ConnectionState.CONNECTED
    Scaffold(containerColor = CanvasColor) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 1_000.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BrandHeader()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Live telemetry",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Explore synthetic vehicle data. No car is connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedColor,
                    )
                }
                SimulationControls(connected, onToggleSimulation, onReset)
                if (onOpenDashboard != null) {
                    OutlinedButton(onClick = onOpenDashboard, modifier = Modifier.testTag("preview_dashboard")) {
                        Text("Preview dashboard")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (connected) MintColor else MutedColor),
                        )
                        Text(
                            if (connected) "Simulator running" else "Simulator stopped",
                            color = if (connected) MintColor else MutedColor,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("simulation_status"),
                        )
                    }
                    Text(
                        "${formatElapsed(snapshot.elapsedMs)} simulated",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("elapsed_time"),
                    )
                }
                DashboardReadings(snapshot)
                ScenarioControls(snapshot.scenario, onScenarioSelected)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = CardColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "A preview of what's ahead",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Use the Adapter tab for real OBD readings and Trips to record them. This simulation pauses in the background and cannot be saved as a trip.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedColor,
                        )
                        Text(
                            "API NOT CONFIGURED",
                            color = MutedColor,
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
                Text(
                    "RX-8 TELEMETRY  /  DEVELOPMENT PREVIEW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedColor,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.width(4.dp).height(26.dp).clip(RoundedCornerShape(2.dp))
                        .background(AccentColor),
                )
                Text(
                    "RX-8",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                )
            }
            Text(
                "231 PS · 2004 EU",
                style = MaterialTheme.typography.labelMedium,
                color = MutedColor,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = AccentColor.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.35f)),
        ) {
            Text(
                "SIMULATOR",
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                color = AccentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.3.sp,
            )
        }
    }
}

@Composable
private fun SimulationControls(
    connected: Boolean,
    onToggleSimulation: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onToggleSimulation,
            modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("toggle_simulator"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                if (connected) "Stop simulator" else "Start simulator",
                fontWeight = FontWeight.Bold,
            )
        }
        OutlinedButton(
            onClick = onReset,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 50.dp).testTag("reset_simulator"),
            border = BorderStroke(1.dp, BorderColor),
        ) {
            Text("Reset", color = WarmWhite)
        }
    }
}

@Composable
private fun DashboardReadings(snapshot: TelemetrySnapshot) {
    val rpm = snapshot.reading(TelemetryMetric.ENGINE_RPM)
    BoxWithConstraints {
        if (maxWidth >= 720.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EngineSpeedGauge(rpm, snapshot.connection, Modifier.weight(1f))
                MetricGrid(snapshot, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EngineSpeedGauge(rpm, snapshot.connection, Modifier.fillMaxWidth())
                MetricGrid(snapshot, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EngineSpeedGauge(
    reading: TelemetryReading?,
    connection: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val value = reading.validValue()
    val status = reading.statusLabel(connection)
    Surface(
        modifier = modifier.testTag("engine_speed_card"),
        shape = RoundedCornerShape(22.dp),
        color = CardColor,
        border = BorderStroke(1.dp, BorderColor),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Eyebrow("ENGINE SPEED")
                Text(
                    "SYNTHETIC",
                    fontSize = 10.sp,
                    color = AccentColor,
                    letterSpacing = 1.sp,
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "Engine speed gauge, synthetic data"
                    },
                ) {
                    val center = Offset(size.width / 2f, size.height * 0.53f)
                    val radius = min(size.width * 0.45f, size.height * 0.48f)
                    val progress = ((value ?: 0.0) / 9_000.0).coerceIn(0.0, 1.0)
                    val segments = 44
                    for (index in 0..segments) {
                        val fraction = index.toDouble() / segments
                        val radians = Math.toRadians(140.0 + 260.0 * fraction)
                        val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
                        val from = center + direction * (radius * 0.88f)
                        val to = center + direction * radius
                        drawLine(
                            color = if (value != null && fraction <= progress) AccentColor else BorderColor,
                            start = from,
                            end = to,
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        reading.displayValue(),
                        fontSize = 58.sp,
                        lineHeight = 64.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Monospace,
                        color = WarmWhite,
                        modifier = Modifier.testTag("reading_engine_rpm"),
                    )
                    Text("rpm", color = MutedColor, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status,
                        color = if (value == null && reading != null) AccentColor else MutedColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("status_engine_rpm"),
                    )
                }
                Text(
                    "0",
                    color = MutedColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp),
                )
                Text(
                    "9,000",
                    color = MutedColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(snapshot: TelemetrySnapshot, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(TelemetryMetric.SPEED, "Speed", snapshot, Modifier.weight(1f))
            MetricCard(TelemetryMetric.COOLANT_TEMPERATURE, "Coolant", snapshot, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(TelemetryMetric.INTAKE_TEMPERATURE, "Intake air", snapshot, Modifier.weight(1f))
            MetricCard(TelemetryMetric.ENGINE_LOAD, "Engine load", snapshot, Modifier.weight(1f))
        }
        MetricCard(
            TelemetryMetric.THROTTLE_POSITION,
            "Throttle position",
            snapshot,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricCard(
    metric: TelemetryMetric,
    label: String,
    snapshot: TelemetrySnapshot,
    modifier: Modifier = Modifier,
) {
    val reading = snapshot.reading(metric)
    val status = reading.statusLabel(snapshot.connection)
    val tag = metric.name.lowercase(Locale.ROOT)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = CardColor,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, color = MutedColor, style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    reading.displayValue(),
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("reading_$tag"),
                )
                Text(
                    metric.unit,
                    color = MutedColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (reading != null && reading.status != ReadingStatus.VALID) AccentColor else MutedColor,
                modifier = Modifier.testTag("status_$tag"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioControls(
    scenario: SimulationScenario,
    onScenarioSelected: (SimulationScenario) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("SIMULATION SCENARIO")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SimulationScenario.entries.forEach { option ->
                FilterChip(
                    selected = scenario == option,
                    onClick = { onScenarioSelected(option) },
                    label = { Text(option.label()) },
                    modifier = Modifier.testTag("scenario_${option.name.lowercase(Locale.ROOT)}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentColor.copy(alpha = 0.15f),
                        selectedLabelColor = AccentColor,
                        labelColor = MutedColor,
                    ),
                )
            }
        }
        Text(
            when (scenario) {
                SimulationScenario.NORMAL -> "Six simulated readings update once per second while this screen is visible."
                SimulationScenario.TIMEOUT -> "Responses time out. Missing readings stay blank instead of becoming zero."
                SimulationScenario.MISSING_DATA -> "Temperature readings are unavailable; the other simulated sensors continue."
            },
            color = MutedColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        color = MutedColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
    )
}

private fun TelemetrySnapshot.reading(metric: TelemetryMetric): TelemetryReading? =
    readings.firstOrNull { it.metric == metric }

private fun TelemetryReading?.validValue(): Double? =
    this?.takeIf { it.status == ReadingStatus.VALID }?.value?.takeIf { it.isFinite() }

private fun TelemetryReading?.displayValue(): String =
    validValue()?.roundToInt()?.toString() ?: "—"

private fun TelemetryReading?.statusLabel(connection: ConnectionState): String = when {
    connection == ConnectionState.DISCONNECTED -> "Not connected"
    this == null -> "No reading"
    status == ReadingStatus.TIMEOUT -> "Timed out"
    status == ReadingStatus.UNAVAILABLE -> "Unavailable"
    validValue() == null -> "No reading"
    else -> "Simulated"
}

private fun SimulationScenario.label(): String = when (this) {
    SimulationScenario.NORMAL -> "Normal"
    SimulationScenario.TIMEOUT -> "Timeout"
    SimulationScenario.MISSING_DATA -> "Missing data"
}

private fun formatElapsed(elapsedMs: Long): String {
    val seconds = elapsedMs / 1_000L
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
}
