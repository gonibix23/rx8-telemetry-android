package com.gonibix.rx8.dashboard

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.obd.*
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModularDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val cards = mutableStateOf(listOf(DashboardCardConfig(ObdMetric.SPEED)))
    private val snapshot = ObdSessionSnapshot(phase = ObdPhase.LIVE, elapsedMillis = 1_000,
        ecus = listOf(ObdEcuCapabilities("7E8", setOf(ObdMetric.SPEED.pid))),
        readings = listOf(ObdReading(ObdMetric.SPEED, 0.0, ObdReadingStatus.VALID, "7E8", 123, 1_000)))

    @Test fun everySensorIncludingBasicsCanBeRemovedAndUnsupportedSensorsStayHonest() {
        show()
        scroll("dashboard_value_7E8_speed").assertTextEquals("0")
        scroll("dashboard_choose_sensors").performClick()
        search("PID 0D")
        scroll("dashboard_sensor_search_count").assertTextEquals("1 of 120 sensors")
        scroll("dashboard_favorite_speed").assertIsOn().performClick()
        compose.runOnIdle { assertTrue(cards.value.isEmpty()) }
        search("PID 10")
        scroll("dashboard_favorite_mass_air_flow").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(ObdMetric.MASS_AIR_FLOW, cards.value.single().metric) }
        scroll("dashboard_value_7E8_mass_air_flow").assertTextEquals("\u2014")
        scroll("dashboard_status_7E8_mass_air_flow").assertTextEquals("Unsupported")
        compose.onNodeWithTag("dashboard_value_7E8_speed").assertDoesNotExist()
    }

    @Test fun longPressAndDiscoverableEditChangeAppearanceSizeAndFloatingSelection() {
        show()
        scroll("dashboard_card_7E8_speed").performTouchInput { longClick() }
        compose.onNodeWithTag("card_style_BAR").performClick()
        compose.onNodeWithTag("card_size_LARGE").performClick()
        compose.onNodeWithTag("card_overlay_selected").performScrollTo().performClick()
        compose.onNodeWithTag("card_save").performClick()
        compose.runOnIdle {
            assertEquals(DashboardCardConfig(ObdMetric.SPEED, DashboardCardStyle.BAR, DashboardCardSize.LARGE, true), cards.value.single())
        }
        scroll("dashboard_bar_7E8_speed").assertExists()
        scroll("dashboard_value_7E8_speed").assertTextEquals("0")
        scroll("dashboard_edit_7E8_speed").performClick()
        compose.onNodeWithTag("card_style_DIAL").performClick()
        compose.onNodeWithTag("card_save").performClick()
        scroll("dashboard_dial_7E8_speed").assertExists()
    }

    @Test fun editorReordersAndRemovesWithoutDroppingOtherConfiguration() {
        cards.value = listOf(DashboardCardConfig(ObdMetric.SPEED), DashboardCardConfig(ObdMetric.COOLANT_TEMPERATURE, DashboardCardStyle.BAR))
        show()
        scroll("dashboard_edit_7E8_coolant_temperature").performClick()
        compose.onNodeWithTag("card_move_up").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ObdMetric.COOLANT_TEMPERATURE, cards.value.first().metric) }
        compose.onNodeWithTag("card_save").performClick()
        scroll("dashboard_edit_7E8_speed").performClick()
        compose.onNodeWithTag("card_remove").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(DashboardCardConfig(ObdMetric.COOLANT_TEMPERATURE, DashboardCardStyle.BAR)), cards.value)
        }
    }

    @Test fun offlineLayoutCanBeEditedWithoutInventingASourceOrMeasurement() {
        show(ObdSessionSnapshot())
        scroll("dashboard_value_Unknown_speed").assertTextEquals("\u2014")
        scroll("dashboard_status_Unknown_speed").assertTextEquals("Awaiting reading")
        scroll("dashboard_edit_Unknown_speed").performClick()
        compose.onNodeWithTag("card_remove").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cards.value.isEmpty()) }
    }

    @Test fun normalModeHidesAllEditorsAndAdapterInformationAndDoneRestoresOnlyReadings() {
        show(snapshot.copy(adapterIdentity = "Private adapter fixture", protocolLabel = "Protocol fixture",
            statusMessage = "Private adapter status"), edit = false)
        compose.onNodeWithTag("dashboard_mode_title").assertTextEquals("Live")
        compose.onNodeWithTag("dashboard_choose_sensors").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_edit_7E8_speed").assertDoesNotExist()
        compose.onNodeWithText("Private adapter fixture", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Protocol fixture", substring = true).assertDoesNotExist()
        scroll("dashboard_card_7E8_speed").performTouchInput { longClick() }
        compose.onNodeWithTag("card_save").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_edit_mode").performClick()
        scroll("dashboard_choose_sensors").performClick()
        search("coolant")
        compose.onNodeWithTag("dashboard_edit_mode").performClick()
        compose.onNodeWithTag("dashboard_sensor_search").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_columns_TWO").assertDoesNotExist()
        scroll("dashboard_value_7E8_speed").assertTextEquals("0")
        compose.onNodeWithTag("dashboard_edit_mode").performClick()
        scroll("dashboard_sensor_search").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("coolant")))
    }

    @Test fun compactTwoColumnLayoutKeepsOddCardWidthAndLargeCardSpan() {
        cards.value = listOf(ObdMetric.SPEED, ObdMetric.COOLANT_TEMPERATURE, ObdMetric.ENGINE_LOAD)
            .map { DashboardCardConfig(it) } + DashboardCardConfig(ObdMetric.ENGINE_RPM, size = DashboardCardSize.LARGE)
        show(edit = false, initialColumns = DashboardColumns.TWO)
        val speed = scroll("dashboard_card_7E8_speed").fetchSemanticsNode().boundsInRoot
        val coolant = scroll("dashboard_card_7E8_coolant_temperature").fetchSemanticsNode().boundsInRoot
        assertEquals(speed.top, coolant.top, 1f)
        assertTrue(coolant.left > speed.left)
        val load = scroll("dashboard_card_7E8_engine_load").fetchSemanticsNode().boundsInRoot
        assertEquals(speed.width, load.width, 1f)
        val rpm = scroll("dashboard_card_7E8_engine_rpm").fetchSemanticsNode().boundsInRoot
        assertTrue(rpm.width > speed.width * 1.8f)
    }

    @Test fun largeFontUsesOneColumnAndStillPreservesFractionalNegativeReadings() {
        cards.value = listOf(DashboardCardConfig(ObdMetric.SPEED), DashboardCardConfig(ObdMetric.COOLANT_TEMPERATURE))
        val data = snapshot.copy(ecus = listOf(ObdEcuCapabilities("7E8", setOf(13, 5))),
            readings = snapshot.readings + ObdReading(ObdMetric.COOLANT_TEMPERATURE, -20.0, ObdReadingStatus.VALID, "7E8", 123, 1_000))
        show(data, edit = false, initialColumns = DashboardColumns.TWO, fontScale = 2f)
        val speed = scroll("dashboard_card_7E8_speed").fetchSemanticsNode().boundsInRoot
        val coolant = scroll("dashboard_card_7E8_coolant_temperature").fetchSemanticsNode().boundsInRoot
        assertEquals(speed.width, coolant.width, 1f)
        assertEquals(speed.left, coolant.left, 1f)
        assertTrue(coolant.top > speed.top)
        scroll("dashboard_value_7E8_coolant_temperature").assertTextEquals("-20")
    }

    @Test fun editModeAndSearchRestoreWithoutOpeningACardEditorOrChangingSelection() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { Rx8Theme {
            LiveDashboard(snapshot, cards.value.map { it.metric }.toSet(), {}, {}, cards = cards.value)
        } }
        compose.onNodeWithTag("dashboard_edit_mode").performClick()
        scroll("dashboard_choose_sensors").performClick()
        search("PID 0D")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("dashboard_mode_title").assertTextEquals("Edit Live")
        scroll("dashboard_sensor_search_count").assertTextEquals("1 of 120 sensors")
        scroll("dashboard_favorite_speed").assertIsOn()
        compose.onNodeWithTag("card_save").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_edit_mode").performClick()
        compose.onNodeWithTag("dashboard_choose_sensors").assertDoesNotExist()
        scroll("dashboard_value_7E8_speed").assertTextEquals("0")
    }

    private fun show(data: ObdSessionSnapshot = snapshot, edit: Boolean = true,
        initialColumns: DashboardColumns = DashboardColumns.ONE, fontScale: Float = 1f) {
        val columns = mutableStateOf(initialColumns)
        compose.setContent { Rx8Theme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            LiveDashboard(data, cards.value.map { it.metric }.toSet(), { metric ->
                cards.value = if (cards.value.any { it.metric == metric }) cards.value.filterNot { it.metric == metric }
                    else cards.value + defaultCard(metric)
            }, {}, cards = cards.value, onUpdateCard = { updated ->
                cards.value = cards.value.map { if (it.metric == updated.metric) updated else it }
            }, onMoveCard = { metric, delta ->
                cards.value = cards.value.toMutableList().apply {
                    val index = indexOfFirst { it.metric == metric }
                    add((index + delta).coerceIn(0, lastIndex), removeAt(index))
                }
            }, columns = columns.value, onColumnsChange = { columns.value = it })
            }
        } }
        if (edit) compose.onNodeWithTag("dashboard_edit_mode").performClick()
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("live_dashboard")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
    private fun search(text: String) {
        scroll("dashboard_sensor_search").performTextReplacement(text)
        compose.onNodeWithTag("dashboard_sensor_search").performImeAction()
    }
}
