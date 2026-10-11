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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import org.junit.Assert.assertTrue
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
            val tab = compose.onNodeWithTag("${id}_tab")
            tab.performScrollTo().assertIsDisplayed().performClick()
            val results = mutableListOf<TextLayoutResult>()
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            text.assertTextEquals(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            val diagnostic = "Tab '$label': size=${layout.size}, lineHeight=${layout.getLineBottom(0) - layout.getLineTop(0)}, " +
                "lineWidth=${layout.getLineRight(0) - layout.getLineLeft(0)}, constraints=${layout.layoutInput.constraints}"
            assertEquals(label, layout.layoutInput.text.text)
            assertEquals("$diagnostic must remain one line", 1, layout.lineCount)
            assertEquals("$diagnostic must start at the first character", 0, layout.getLineStart(0))
            assertEquals("$diagnostic must show the complete label", label.length, layout.getLineEnd(0, visibleEnd = true))
            assertFalse("$diagnostic must not ellipsize", layout.isLineEllipsized(0))
            // Compose foundation 1.10.0 rebuilds the semantics MultiParagraph with the incoming
            // unbounded width, but retains the actual finite layout size. didOverflowWidth then
            // compares container widths, not drawn text. Check every rendered extent instead.
            assertTrue("$diagnostic extends left of its text box", layout.getLineLeft(0) >= 0f)
            assertTrue("$diagnostic extends right of its text box", layout.getLineRight(0) <= layout.size.width)
            assertTrue("$diagnostic extends above its text box", layout.getLineTop(0) >= 0f)
            assertTrue("$diagnostic extends below its text box", layout.getLineBottom(0) <= layout.size.height)
            label.indices.forEach { offset ->
                val glyph = layout.getBoundingBox(offset)
                assertTrue("$diagnostic clipped character $offset: $glyph",
                    glyph.left >= 0f && glyph.right <= layout.size.width &&
                        glyph.top >= 0f && glyph.bottom <= layout.size.height)
            }
            val textBounds = text.getUnclippedBoundsInRoot()
            val tabBounds = tab.getUnclippedBoundsInRoot()
            val viewportBounds = compose.onNodeWithTag("main_tabs").getUnclippedBoundsInRoot()
            listOf("tab" to tabBounds, "visible row" to viewportBounds).forEach { (container, bounds) ->
                assertTrue("$diagnostic text $textBounds is clipped by $container $bounds",
                    textBounds.left >= bounds.left && textBounds.right <= bounds.right &&
                        textBounds.top >= bounds.top && textBounds.bottom <= bounds.bottom)
            }
            compose.runOnIdle { assertEquals(id, selected) }
        }
    }
}
