package com.gonibix.rx8

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gonibix.rx8.ui.theme.Rx8Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowViewportAndLargeFontKeepEveryTabReadableOnOneLine() {
        var selected by mutableStateOf("adapter")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Rx8Theme { Box(Modifier.width(240.dp)) { MainNavigationTabs(selected, { selected = it }) } }
            }
        }
        listOf("adapter" to "Adapter", "dashboard" to "Live", "trips" to "Trips", "vehicles" to "Vehicles").forEach { (id, label) ->
            compose.onNodeWithTag("${id}_tab").performScrollTo().assertIsDisplayed().performClick()
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(1, results.single().lineCount)
            assertFalse(results.single().hasVisualOverflow)
            compose.runOnIdle { assertEquals(id, selected) }
        }
    }
}
