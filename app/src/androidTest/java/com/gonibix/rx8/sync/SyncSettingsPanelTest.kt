package com.gonibix.rx8.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun show(
        state: SyncSettingsUiState,
        onSave: (String?, Boolean) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {},
        onReload: () -> Unit = {},
    ) {
        compose.setContent {
            Rx8Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                        SyncSettingsPanel(state, onSave, onRetry, onReload)
                    }
                }
            }
        }
    }

    private fun ready(settings: UploadSettings = UploadSettings()) = SyncSettingsUiState(settings = settings, isLoading = false)
    private companion object { const val URL = "https://api.example.test/rx8" }
}
