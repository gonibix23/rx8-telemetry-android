package com.gonibix.rx8

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.DemoTelemetrySource
import com.gonibix.rx8.telemetry.SimulationScenario
import com.gonibix.rx8.telemetry.TelemetrySnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns a foreground-only demo; no vehicle connection or recording is started. */
class SimulatorViewModel(
    private val source: DemoTelemetrySource = DemoTelemetrySource(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val mutableState = MutableStateFlow(source.snapshot)
    val uiState: StateFlow<TelemetrySnapshot> = mutableState.asStateFlow()

    private var active = false
    private var ticker: Job? = null

    fun setActive(isActive: Boolean) {
        active = isActive
        updateTicker()
    }

    fun startSimulation() {
        if (mutableState.value.connection == ConnectionState.DISCONNECTED) {
            mutableState.value = source.connect()
        }
        updateTicker()
    }

    fun stopSimulation() {
        mutableState.value = source.disconnect()
        updateTicker()
    }

    fun toggleSimulation() {
        if (mutableState.value.connection == ConnectionState.CONNECTED) {
            stopSimulation()
        } else {
            startSimulation()
        }
    }

    fun selectScenario(scenario: SimulationScenario) {
        mutableState.value = source.setScenario(scenario)
    }

    fun resetSimulation() {
        mutableState.value = source.reset()
        updateTicker()
    }

    private fun updateTicker() {
        if (!active || mutableState.value.connection != ConnectionState.CONNECTED) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch(dispatcher) {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                mutableState.value = source.advanceBy(TICK_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1_000L
    }
}
