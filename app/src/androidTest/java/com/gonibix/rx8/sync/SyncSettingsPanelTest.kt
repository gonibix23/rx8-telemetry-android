package com.gonibix.rx8.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSettingsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun absentServerDoesNotOfferRetryOrClaimDelivery() {
        show(ready())
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Server not configured")
        compose.onNodeWithTag("retry_uploads").assertDoesNotExist()
        compose.onNodeWithTag("upload_settings_message").assertDoesNotExist()
        compose.onNodeWithTag("upload_feedback").assertDoesNotExist()
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("enable_uploads").performScrollTo().assertIsNotEnabled()
    }

    @Test fun enabledStatusDoesNotClaimSuccessOrDisableLocalFirstExplanation() {
        show(ready(UploadSettings(URL, true)))
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Uploads enabled")
        compose.onNodeWithTag("upload_settings_message").assertDoesNotExist()
        compose.onNodeWithText("Trips are saved on this device first. Completed and interrupted trips can upload when a server and connection are available.").assertIsDisplayed()
    }

    @Test fun typedUrlOnlySavesAfterExplicitEnableAndExplainsIncludedVin() {
        val saves = mutableListOf<Pair<String?, Boolean>>()
        show(ready(), onSave = { url, enabled -> saves += url to enabled })
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("upload_server_url").performTextReplacement(URL)
        assertTrue(saves.isEmpty())
        compose.onNodeWithText("Uploads include vehicle ID, alias, saved VIN when present, trip times and readings. They do not include Bluetooth addresses or GPS.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("enable_uploads").performScrollTo().performClick()
        assertEquals(listOf(URL to true), saves)
    }

    @Test fun pauseUsesSavedEndpointInsteadOfUnsavedEditedText() {
        val saves = mutableListOf<Pair<String?, Boolean>>()
        show(ready(UploadSettings(URL, true)), onSave = { url, enabled -> saves += url to enabled })
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("upload_server_url").performTextReplacement("https://other.example")
        compose.onNodeWithTag("pause_uploads").performScrollTo().performClick()
        assertEquals(listOf(URL to false), saves)
    }

    @Test fun boundServerRemainsVisibleAndResumeUsesThatSameEndpoint() {
        val saves = mutableListOf<Pair<String?, Boolean>>()
        show(ready(UploadSettings(null, false, URL)), onSave = { url, enabled -> saves += url to enabled })
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Uploads paused")
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("upload_server_url").assertTextContains(URL, substring = true)
        compose.onNodeWithTag("upload_server_url").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        compose.onNodeWithTag("upload_endpoint_policy").performScrollTo().assertTextContains("stays fixed", substring = true)
        compose.onNodeWithTag("enable_uploads").performScrollTo().performClick()
        assertEquals(listOf(URL to true), saves)
    }

    @Test fun sendingAllowsPauseButDoesNotOfferConcurrentRetry() {
        show(ready(UploadSettings(URL, true, URL)).copy(runtime = UploadRuntimeStatus(isRunning = true)))
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Sending saved trips…")
        compose.onNodeWithTag("retry_uploads").assertIsNotEnabled()
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("pause_uploads").performScrollTo().assertIsEnabled()
    }

    @Test fun loadFailureDoesNotMasqueradeAsNoServerAndHasRecovery() {
        var reloads = 0
        show(SyncSettingsUiState(isLoading = false, error = "Settings could not be loaded."), onReload = { reloads++ })
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Upload settings unavailable")
        compose.onNodeWithTag("upload_settings_error").assertTextEquals("Settings could not be loaded.")
        compose.onNodeWithTag("toggle_upload_settings").assertDoesNotExist()
        compose.onNodeWithTag("reload_upload_settings").performClick()
        assertEquals(1, reloads)
    }

    @Test fun savingDisablesConflictingActionsAndRetainsConfirmedStatus() {
        show(ready(UploadSettings(URL, false)).copy(isSaving = true))
        compose.onNodeWithTag("upload_settings_status").assertTextEquals("Uploads paused")
        compose.onNodeWithTag("toggle_upload_settings").performClick()
        compose.onNodeWithTag("upload_server_url").assertIsNotEnabled()
        compose.onNodeWithTag("enable_uploads").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("upload_settings_message").assertDoesNotExist()
    }

    @Test fun retryIsAnExplicitActionAndKeepsRemoteErrorVisible() {
        var retries = 0
        show(ready(UploadSettings(URL, true, URL)).copy(runtime = UploadRuntimeStatus(error = "Server unavailable. Saved trips remain local.")), onRetry = { retries++ })
        compose.onNodeWithTag("upload_settings_error").assertTextContains("Saved trips remain local", substring = true)
        assertEquals(0, retries)
        compose.onNodeWithTag("retry_uploads").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun collapsedUploadsContainsOneStatusThatFollowsDurableDelivery() {
        var state by mutableStateOf(ready(UploadSettings(URL, true)))
        var delivery by mutableStateOf(UploadDeliveryState(UploadDeliveryCounts(2, 1, 1, 0)))
        showContent { SyncSettingsPanel(state, { _, _ -> }, {}, {}, delivery = delivery) }
        compose.onNodeWithTag("upload_server_url").assertDoesNotExist()
        compose.onAllNodesWithTag("upload_feedback").assertCountEquals(1)
        compose.onNodeWithTag("upload_feedback").assert(hasAnyAncestor(hasTestTag("upload_settings")))
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("1 trip waiting to upload")

        compose.runOnIdle { state = state.copy(runtime = UploadRuntimeStatus(isRunning = true)) }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Uploading trips…")
        compose.onNodeWithTag("upload_feedback_detail").assertTextContains("1 of 2", substring = true)
        compose.runOnIdle {
            delivery = UploadDeliveryState(UploadDeliveryCounts(2, 2, 0, 0))
            state = state.copy(runtime = UploadRuntimeStatus())
        }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Trips uploaded successfully")
        compose.onNodeWithTag("upload_feedback_title").assert(hasAnyAncestor(hasTestTag("upload_settings")))
        compose.onNodeWithTag("upload_feedback_detail").assertTextEquals("2 of 2 finished trips confirmed by server. Local copies are kept.")
        compose.onAllNodesWithTag("upload_feedback").assertCountEquals(1)
        compose.onNodeWithTag("upload_server_url").assertDoesNotExist()
    }

    @Test fun deliveryFailureInsideUploadsDoesNotInventConfirmedCounts() {
        var delivery by mutableStateOf(UploadDeliveryState(UploadDeliveryCounts(2, 1, 0, 1)))
        showContent { SyncSettingsPanel(ready(UploadSettings(URL, true)), { _, _ -> }, {}, {}, delivery = delivery) }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("1 trip needs attention")
        compose.onNodeWithTag("upload_feedback_detail").assertTextContains("1 of 2", substring = true)
        compose.runOnIdle { delivery = UploadDeliveryState(error = "Delivery could not be checked.") }
        compose.onNodeWithTag("upload_feedback_title").assertTextEquals("Upload status unavailable")
        compose.onNodeWithTag("upload_feedback_detail").assertTextEquals("Delivery could not be checked.")
        compose.onNodeWithTag("upload_feedback").assert(hasAnyAncestor(hasTestTag("upload_settings")))
        compose.onAllNodesWithTag("upload_feedback").assertCountEquals(1)
        compose.onNodeWithText("Trips uploaded successfully").assertDoesNotExist()
        compose.onNodeWithTag("upload_server_url").assertDoesNotExist()
    }

    private fun show(
        state: SyncSettingsUiState,
        onSave: (String?, Boolean) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
        onReload: () -> Unit = {},
    ) {
        showContent { SyncSettingsPanel(state, onSave, onRetry, onReload) }
    }

    private fun showContent(content: @Composable () -> Unit) {
        compose.setContent {
            Rx8Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                        content()
                    }
                }
            }
        }
    }

    private fun ready(settings: UploadSettings = UploadSettings()) = SyncSettingsUiState(settings = settings, isLoading = false)
    private companion object { const val URL = "https://api.example.test/rx8" }
}
