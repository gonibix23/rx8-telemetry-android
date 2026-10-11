package com.gonibix.rx8.vehicle

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.obd.ObdVinSnapshot
import com.gonibix.rx8.obd.ObdVinStatus
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VinDetectionPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detectedVinIsMaskedAndReviewRequiresAnExplicitClick() {
        val candidate = VinResponse("7E8", "TEST0000000000001")
        var reviewed: VinResponse? = null
        compose.setContent {
            Rx8Theme {
                VinDetectionPanel(
                    ObdVinSnapshot(ObdVinStatus.AVAILABLE, candidates = listOf(candidate)),
                    onRead = {}, onReview = { reviewed = it },
                )
            }
        }
        compose.onNodeWithText(candidate.vin).assertDoesNotExist()
        compose.onNodeWithText("VIN ending 000001 · ECU 7E8").assertExists()
        assertNull(reviewed)
        compose.onNodeWithTag("review_vin_7E8").performClick()
        assertEquals(candidate, reviewed)
    }

    @Test fun pendingReadCannotBeRequestedAgain() {
        compose.setContent {
            Rx8Theme {
                VinDetectionPanel(ObdVinSnapshot(ObdVinStatus.READING), onRead = {}, onReview = {})
            }
        }
        compose.onNodeWithTag("read_vin").assertIsNotEnabled()
    }
}
