package com.gonibix.rx8.capabilities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.obd.ObdEcuCapabilities
import com.gonibix.rx8.obd.ObdPhase
import com.gonibix.rx8.obd.ObdSessionSnapshot
import com.gonibix.rx8.telemetry.obd.ObdProtocol
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Intercept the document picker; no actual document, external app or vehicle is used. */
@RunWith(AndroidJUnit4::class)
class CapabilityReportExportActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val replacementModels = ViewModelStore()
    @After fun tearDown() { compose.runOnIdle { models.clear(); replacementModels.clear() } }

    @Test fun pendingPickerSurvivesNavigationRemovalAndReceivesCancellationWithoutSuccess() {
        val model = model(models)
        val registry = ControlledRegistry()
        var showReport by mutableStateOf(true)
        compose.setContent { Harness(model, registry, showReport) }
        compose.runOnIdle { assertTrue(model.capture(snapshot())) }
        compose.onNodeWithTag("launch_report_export").performClick()
        val report = model.uiState.value.report!!
        assertEquals("RX8-capabilities-${report.reportId}.json", registry.fileName)
        compose.onNodeWithTag("report_picker_state").assertTextEquals("Picker pending")
        compose.runOnIdle { showReport = false }
        compose.onNodeWithTag("adapter_fixture").assertExists()
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_CANCELED, null)) }
        compose.onNodeWithTag("report_picker_state").assertTextEquals("No pending picker")
        assertSame(report, model.uiState.value.report)
        assertNull(model.uiState.value.message)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isExporting)
    }

    @Test fun actualActivityRecreationRetainsTheSameFrozenReportAndDeliversThePendingResult() {
        val registry = ControlledRegistry()
        lateinit var original: CapabilityReportViewModel
        compose.activityRule.scenario.onActivity { activity ->
            original = model(activity.viewModelStore)
            activity.setContent { Harness(original, registry, true) }
            assertTrue(original.capture(snapshot()))
        }
        compose.onNodeWithTag("launch_report_export").performClick()
        val report = original.uiState.value.report!!
        val fileName = registry.fileName
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            val retained = model(activity.viewModelStore)
            assertSame(original, retained)
            assertSame(report, retained.uiState.value.report)
            assertTrue(retained.uiState.value.isAwaitingDestination)
            activity.setContent { Harness(retained, registry, true) }
        }
        compose.onNodeWithTag("report_picker_state").assertTextEquals("Picker pending")
        assertEquals(fileName, registry.fileName)
        assertEquals(1, registry.launches)
        compose.runOnIdle {
            assertFalse(original.capture(snapshot().copy(elapsedMillis = 4_000)))
            assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_OK, unavailableProvider()))
        }
        compose.waitUntil(5_000) { !original.uiState.value.isExporting && !original.uiState.value.isAwaitingDestination }
        assertSame(report, original.uiState.value.report)
        assertTrue(original.uiState.value.error!!.contains("Export could not be completed"))
        assertNull(original.uiState.value.message)
    }

    @Test fun restoredCallbackWithoutItsProcessMemoryReportsAnExpiredReportAndDoesNotWrite() {
        var current by mutableStateOf(model(models))
        val registry = ControlledRegistry()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Harness(current, registry, true) }
        compose.runOnIdle { current.capture(snapshot()) }
        compose.onNodeWithTag("launch_report_export").performClick()
        compose.runOnIdle {
            models.clear()
            current = model(replacementModels)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_OK, unavailableProvider())) }
        compose.waitForIdle()
        assertNull(current.uiState.value.report)
        assertFalse(current.uiState.value.isAwaitingDestination)
        assertFalse(current.uiState.value.isExporting)
        assertTrue(current.uiState.value.error!!.contains("no longer available"))
        assertNull(current.uiState.value.message)
    }

    @Test fun missingDocumentPickerClearsPendingAndAllowsRetryWithoutReplacingThePreview() {
        val model = model(models)
        val registry = ControlledRegistry().apply { failLaunch = true }
        compose.setContent { Harness(model, registry, true) }
        compose.runOnIdle { model.capture(snapshot()) }
        val report = model.uiState.value.report
        compose.onNodeWithTag("launch_report_export").performClick()
        assertFalse(model.uiState.value.isAwaitingDestination)
        assertTrue(model.uiState.value.error!!.contains("picker could not be opened"))
        assertNull(model.uiState.value.message)
        assertSame(report, model.uiState.value.report)
        compose.runOnIdle { registry.failLaunch = false }
        compose.onNodeWithTag("launch_report_export").performClick()
        assertTrue(model.uiState.value.isAwaitingDestination)
        assertNull(model.uiState.value.error)
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_CANCELED, null)) }
        assertNull(model.uiState.value.message)
    }

    @Test fun repeatedExportActionsCannotOpenTwoDestinations() {
        val model = model(models)
        val registry = ControlledRegistry()
        lateinit var export: () -> Unit
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner()) {
                export = rememberCapabilityReportExportAction(model)
            }
        }
        compose.runOnIdle {
            model.capture(snapshot())
            export()
            export()
        }
        assertEquals(1, registry.launches)
        assertTrue(model.uiState.value.isAwaitingDestination)
        compose.runOnIdle { assertTrue(registry.dispatchResult(registry.request, Activity.RESULT_CANCELED, null)) }
    }

    @Composable
    private fun Harness(model: CapabilityReportViewModel, registry: ControlledRegistry, showReport: Boolean) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner()) {
            val export = rememberCapabilityReportExportAction(model)
            val state by model.uiState.collectAsState()
            Rx8Theme {
                Column {
                    Text(if (state.isAwaitingDestination) "Picker pending" else "No pending picker", Modifier.testTag("report_picker_state"))
                    if (showReport) {
                        Button(onClick = export, modifier = Modifier.testTag("launch_report_export")) { Text("Export fixture") }
                    } else Text("Adapter fixture", Modifier.testTag("adapter_fixture"))
                }
            }
        }
    }

    private fun model(store: ViewModelStore): CapabilityReportViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
            CapabilityReportViewModel("0.9.0-dev", 36, clock = { 10_000 }),
        ))
    })[CapabilityReportViewModel::class.java]

    private class ControlledRegistry : ActivityResultRegistry() {
        var request = 0
        var launches = 0
        var fileName: String? = null
        var failLaunch = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            launches++
            fileName = input as? String
            if (failLaunch) throw ActivityNotFoundException("Synthetic missing document picker")
        }
        fun owner() = object : ActivityResultRegistryOwner { override val activityResultRegistry = this@ControlledRegistry }
    }

    private fun unavailableProvider() = Intent().setData("content://com.gonibix.rx8.test.unavailable/report.json".toUri())
    private fun snapshot() = ObdSessionSnapshot(phase = ObdPhase.LIVE,
        protocolLabel = ObdProtocol.CAN_11_500.description,
        ecus = listOf(ObdEcuCapabilities("7E8", setOf(0x0C, 0x0D))), elapsedMillis = 1_000)
}