package com.gonibix.rx8

import com.gonibix.rx8.telemetry.ConnectionState
import com.gonibix.rx8.telemetry.DemoTelemetrySource
import com.gonibix.rx8.telemetry.ReadingStatus
import com.gonibix.rx8.telemetry.SimulationScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SimulatorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SimulatorViewModel(DemoTelemetrySource(), dispatcher)
    }

    @After
    fun tearDown() {
        viewModel.setActive(false)
        Dispatchers.resetMain()
    }

    @Test
    fun startsDisconnectedWithoutAutomaticAcquisition() = simulatorTest {
        viewModel.setActive(true)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.connection)
        assertEquals(0L, viewModel.uiState.value.elapsedMs)
        assertTrue(viewModel.uiState.value.readings.isEmpty())
    }

    @Test
    fun repeatedForegroundAndStartEventsDoNotCreateExtraTickers() = simulatorTest {
        viewModel.setActive(true)
        viewModel.startSimulation()
        viewModel.setActive(true)
        viewModel.startSimulation()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(3_000L, viewModel.uiState.value.elapsedMs)
        assertEquals(6, viewModel.uiState.value.readings.size)
    }

    @Test
    fun backgroundPausesAndForegroundResumesWithoutCatchingUp() = simulatorTest {
        viewModel.setActive(true)
        viewModel.startSimulation()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        viewModel.setActive(false)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1_000L, viewModel.uiState.value.elapsedMs)

        viewModel.setActive(true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2_000L, viewModel.uiState.value.elapsedMs)
    }

    @Test
    fun stopClearsReadingsAndCancelsAcquisition() = simulatorTest {
        viewModel.setActive(true)
        viewModel.startSimulation()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        viewModel.stopSimulation()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.connection)
        assertEquals(1_000L, viewModel.uiState.value.elapsedMs)
        assertTrue(viewModel.uiState.value.readings.isEmpty())
    }

    @Test
    fun failureScenarioPublishesMissingValuesWithoutReplacingThemWithZero() = simulatorTest {
        viewModel.startSimulation()
        viewModel.selectScenario(SimulationScenario.TIMEOUT)

        assertEquals(SimulationScenario.TIMEOUT, viewModel.uiState.value.scenario)
        assertTrue(viewModel.uiState.value.readings.isNotEmpty())
        assertTrue(viewModel.uiState.value.readings.all {
            it.status == ReadingStatus.TIMEOUT && it.value == null
        })
        assertEquals(0L, viewModel.uiState.value.elapsedMs)
    }

    @Test
    fun resetRestoresDisconnectedNormalScenarioAndStopsTicks() = simulatorTest {
        viewModel.setActive(true)
        viewModel.startSimulation()
        viewModel.selectScenario(SimulationScenario.MISSING_DATA)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        viewModel.resetSimulation()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.connection)
        assertEquals(SimulationScenario.NORMAL, viewModel.uiState.value.scenario)
        assertEquals(0L, viewModel.uiState.value.elapsedMs)
        assertTrue(viewModel.uiState.value.readings.isEmpty())
    }

    private fun simulatorTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            viewModel.setActive(false)
        }
    }
}
