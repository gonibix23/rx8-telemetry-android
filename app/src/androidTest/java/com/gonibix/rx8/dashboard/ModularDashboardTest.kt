package com.gonibix.rx8.dashboard

import androidx.compose.runtime.mutableStateOf
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

    private fun show(data: ObdSessionSnapshot = snapshot) {
        compose.setContent { Rx8Theme {
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
            })
        } }
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("live_dashboard")
        .performScrollToNode(hasTestTag(tag)).let { compose.onNodeWithTag(tag) }
    private fun search(text: String) {
        scroll("dashboard_sensor_search").performTextReplacement(text)
        compose.onNodeWithTag("dashboard_sensor_search").performImeAction()
    }
}
