package com.gonibix.rx8.vehicle

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gonibix.rx8.telemetry.obd.VinResponse
import com.gonibix.rx8.ui.theme.AccentColor
import com.gonibix.rx8.ui.theme.Rx8Theme
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleProfileScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun creatingAVehicleRequiresANameButNotAVin() {
        var created: Pair<String, String?>? = null
        show(VehicleState(), onCreate = { alias, vin -> created = alias to vin })
        compose.onNodeWithTag("vehicles_empty").assertExists()
        compose.runOnIdle { assertNull(created) }
        compose.onNodeWithTag("create_vehicle").performClick()
        compose.onNodeWithTag("save_vehicle_form").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle_form_alias").performTextInput("  My RX-8  ")
        compose.onNodeWithTag("save_vehicle_form").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("My RX-8" to null, created) }
    }

    @Test
    fun manualVinValidationBlocksInvalidInputAndNormalizesValidInput() {
        var created: Pair<String, String?>? = null
        show(VehicleState(), onCreate = { alias, vin -> created = alias to vin })
        compose.onNodeWithTag("create_vehicle").performClick()
        compose.onNodeWithTag("vehicle_form_alias").performTextInput("My RX-8")
        compose.onNodeWithTag("vehicle_form_vin").performTextInput("INVALID")
        compose.onNodeWithTag("save_vehicle_form").assertIsNotEnabled()
        compose.runOnIdle { assertNull(created) }
        compose.onNodeWithTag("vehicle_form_vin").performTextReplacement("  ${VIN.lowercase()}  ")
        compose.onNodeWithTag("save_vehicle_form").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("My RX-8" to VIN, created) }
    }

    @Test
    fun manualVinAttachmentRetainsTheExistingVehicleId() {
        val state = mutableStateOf(VehicleState(listOf(profile(vin = null)), ID))
        var attached: Pair<String, String>? = null
        compose.setContent {
            Fixture {
                VehicleProfileScreen(
                    state = state.value,
                    onCreate = { _, _ -> },
                    onSelect = {},
                    onRename = { _, _ -> },
                    onAttachManualVin = { id, vin ->
                        attached = id to vin
                        state.value = state.value.copy(profiles = listOf(profile(vin = vin)))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("add_vin_$ID").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle_form_vin").performTextInput(VIN)
        compose.onNodeWithTag("save_vehicle_form").performClick()
        compose.runOnIdle { assertEquals(ID to VIN, attached) }
        compose.onNodeWithTag("vehicle_id_$ID").assertTextEquals(ID)
        compose.onNodeWithTag("vehicle_vin_$ID").assertTextEquals(MASKED_VIN)
        compose.onNodeWithTag("add_vin_$ID").assertDoesNotExist()
    }

    @Test
    fun renamingPassesTheExistingIdAndDoesNotOfferVinReplacement() {
        var renamed: Pair<String, String>? = null
        show(VehicleState(listOf(profile()), ID), onRename = { id, alias -> renamed = id to alias })
        compose.onNodeWithTag("add_vin_$ID").assertDoesNotExist()
        compose.onNodeWithTag("rename_vehicle_$ID").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle_form_alias").performTextReplacement("Weekend RX-8")
        compose.onNodeWithTag("vehicle_form_vin").assertDoesNotExist()
        compose.onNodeWithTag("save_vehicle_form").performClick()
        compose.runOnIdle { assertEquals(ID to "Weekend RX-8", renamed) }
    }

    @Test
    fun matchingDetectedVinRequiresExplicitProfileSelection() {
        var selected: String? = null
        val existing = profile()
        val active = profile(id = OTHER_ID, alias = "Another vehicle", vin = OTHER_VIN)
        show(
            VehicleState(listOf(existing, active), OTHER_ID),
            pendingVin = VinResponse("7E8", VIN),
            onSelect = { selected = it },
        )
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("pending_vin").assertTextEquals(MASKED_VIN)
        compose.onNodeWithTag("attach_detected_vin").assertDoesNotExist()
        compose.onNodeWithTag("create_detected_vehicle").assertDoesNotExist()
        compose.onNodeWithTag("select_detected_match").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ID, selected) }
    }

    @Test
    fun aDifferentDetectedVinCannotOverwriteTheSelectedVehicle() {
        var createdAlias: String? = null
        var attachedId: String? = null
        show(
            VehicleState(listOf(profile()), ID),
            pendingVin = VinResponse("7E9", OTHER_VIN),
            onAttachDetectedVin = { attachedId = it },
            onCreateDetectedVehicle = { createdAlias = it },
        )
        compose.onNodeWithTag("detected_vin_conflict").assertExists()
        compose.onNodeWithTag("attach_detected_vin").assertDoesNotExist()
        compose.runOnIdle { assertNull(createdAlias); assertNull(attachedId) }
        compose.onNodeWithTag("create_detected_vehicle").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle_form_vin").assertDoesNotExist()
        compose.onNodeWithTag("vehicle_form_alias").performTextInput("Second RX-8")
        compose.onNodeWithTag("save_vehicle_form").performClick()
        compose.runOnIdle { assertEquals("Second RX-8", createdAlias); assertNull(attachedId) }
    }

    @Test
    fun attachingADetectedVinToAVinlessProfileIsAnExplicitChoice() {
        var attachedId: String? = null
        show(
            VehicleState(listOf(profile(vin = null)), ID),
            pendingVin = VinResponse("7E8", VIN),
            onAttachDetectedVin = { attachedId = it },
        )
        compose.runOnIdle { assertNull(attachedId) }
        compose.onNodeWithTag("create_detected_vehicle").assertExists()
        compose.onNodeWithTag("attach_detected_vin").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ID, attachedId) }
    }

    @Test
    fun duplicateVinFeedbackOffersSelectionWithoutSelectingAutomatically() {
        val message = mutableStateOf<String?>(null)
        val matchingId = mutableStateOf<String?>(null)
        var selected: String? = null
        compose.setContent {
            Fixture {
                VehicleProfileScreen(
                    state = VehicleState(listOf(profile()), ID),
                    onCreate = { _, _ ->
                        message.value = "This VIN already belongs to a saved vehicle."
                        matchingId.value = ID
                    },
                    onSelect = { selected = it },
                    onRename = { _, _ -> },
                    onAttachManualVin = { _, _ -> },
                    message = message.value,
                    matchingVehicleId = matchingId.value,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithTag("create_vehicle").performClick()
        compose.onNodeWithTag("vehicle_form_alias").performTextInput("Duplicate")
        compose.onNodeWithTag("vehicle_form_vin").performTextInput(VIN)
        compose.onNodeWithTag("save_vehicle_form").performClick()
        compose.onNodeWithTag("vehicle_form_message").assertTextEquals("This VIN already belongs to a saved vehicle.")
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("select_matching_vehicle").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ID, selected) }
    }

    @Test
    fun vinIsMaskedByDefaultAndFixtureScreenshotContainsOnlyMaskedFakeVin() {
        show(VehicleState(listOf(profile()), ID), screenshotFixture = true)
        compose.onNodeWithTag("vehicle_vin_$ID").assertTextEquals(MASKED_VIN)
        compose.onNodeWithText(VIN).assertDoesNotExist()
        compose.onNodeWithTag("vehicle_vin_source_$ID").assertTextEquals("VIN source: entered manually")
        captureVehicleScreen()
        compose.onNodeWithTag("vehicle_vin_${ID}_visibility").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle_vin_$ID").assertTextEquals(VIN)
        compose.onNodeWithTag("vehicle_vin_${ID}_visibility").performClick()
        compose.onNodeWithTag("vehicle_vin_$ID").assertTextEquals(MASKED_VIN)
        compose.onNodeWithText(VIN).assertDoesNotExist()
    }

    private fun show(
        state: VehicleState,
        onCreate: (String, String?) -> Unit = { _, _ -> },
        onSelect: (String) -> Unit = {},
        onRename: (String, String) -> Unit = { _, _ -> },
        pendingVin: VinResponse? = null,
        onAttachDetectedVin: (String) -> Unit = {},
        onCreateDetectedVehicle: (String) -> Unit = {},
        screenshotFixture: Boolean = false,
    ) {
        compose.setContent {
            Fixture(screenshotFixture) {
                VehicleProfileScreen(
                    state = state,
                    onCreate = onCreate,
                    onSelect = onSelect,
                    onRename = onRename,
                    onAttachManualVin = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    pendingVin = pendingVin,
                    onAttachDetectedVin = onAttachDetectedVin,
                    onCreateDetectedVehicle = onCreateDetectedVehicle,
                )
            }
        }
    }

    @Composable
    private fun Fixture(screenshotFixture: Boolean = false, content: @Composable () -> Unit) {
        Rx8Theme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (screenshotFixture) {
                        Text("UI TEST FIXTURE · FAKE VEHICLE", color = AccentColor, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    content()
                }
            }
        }
    }

    private fun profile(id: String = ID, alias: String = "My RX-8", vin: String? = VIN) = VehicleProfile(
        id = id,
        alias = alias,
        vin = vin,
        vinSource = if (vin == null) null else VinSource.MANUAL,
        vinEcuId = null,
        createdAtEpochMillis = 1_791_676_800_000L,
    )

    private fun captureVehicleScreen() {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("The emulator must capture the masked vehicle fixture", screenshot)
        val captured = requireNotNull(screenshot)
        try {
            val source = File(directory, "vehicle.png")
            source.outputStream().use { output -> assertTrue(captured.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "cp /sdcard/Android/data/com.gonibix.rx8/files/screenshots/vehicle.png /data/local/tmp/rx8-vehicle.png",
                ),
            ).use { it.readBytes() }
            val copied = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/rx8-vehicle.png"),
            ).use { it.readBytes() }
            assertArrayEquals("The vehicle screenshot must survive teardown", source.readBytes(), copied)
        } finally {
            captured.recycle()
        }
    }

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000001"
        const val OTHER_ID = "00000000-0000-4000-8000-000000000002"
        const val VIN = "TEST0000000000001"
        const val OTHER_VIN = "TEST0000000000002"
        const val MASKED_VIN = "•••••••••••000001"
    }
}
