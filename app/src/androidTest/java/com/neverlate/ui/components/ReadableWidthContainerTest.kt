package com.neverlate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neverlate.ui.theme.NeverLateTheme
import org.junit.Rule
import org.junit.Test

/**
 * Drives [ReadableWidthContainer] directly (no screen/ViewModel involved), same pattern as
 * [com.neverlate.ui.components.MessageStateTest]. `createComposeRule()` cannot control the real
 * device/window width, so every test wraps [ReadableWidthContainer] in a fixed-size outer [Box]
 * (`outerWidth`) to stand in for "the available window width" — this is what lets Compact vs
 * Medium/Expanded be asserted deterministically without a tablet.
 */
class ReadableWidthContainerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Mirrors [ReadableWidthContainer]'s private `MaxReadableWidth` constant (640.dp). */
    private val maxReadableWidth = 640.dp

    private val contentTag = "content"
    private val outerTag = "outer"

    private fun setContainer(outerWidth: Dp, widthSizeClass: WindowWidthSizeClass) {
        composeTestRule.setContent {
            NeverLateTheme {
                Box(
                    modifier = Modifier
                        .testTag(outerTag)
                        .width(outerWidth)
                        .height(300.dp),
                ) {
                    ReadableWidthContainer(widthSizeClass = widthSizeClass) {
                        Box(modifier = Modifier.testTag(contentTag).fillMaxSize())
                    }
                }
            }
        }
    }

    @Test
    fun compact_contentFillsTheFullAvailableWidth_edgeToEdge() {
        setContainer(outerWidth = 1000.dp, widthSizeClass = WindowWidthSizeClass.Compact)

        composeTestRule.onNodeWithTag(contentTag).assertWidthIsEqualTo(1000.dp)
    }

    @Test
    fun medium_contentIsConstrainedToMaxReadableWidth() {
        setContainer(outerWidth = 1000.dp, widthSizeClass = WindowWidthSizeClass.Medium)

        composeTestRule.onNodeWithTag(contentTag).assertWidthIsEqualTo(maxReadableWidth)
    }

    @Test
    fun expanded_contentIsConstrainedToMaxReadableWidth() {
        setContainer(outerWidth = 1000.dp, widthSizeClass = WindowWidthSizeClass.Expanded)

        composeTestRule.onNodeWithTag(contentTag).assertWidthIsEqualTo(maxReadableWidth)
    }

    @Test
    fun expanded_contentIsCenteredWithinTheAvailableWidth() {
        setContainer(outerWidth = 1000.dp, widthSizeClass = WindowWidthSizeClass.Expanded)

        val outerBounds = composeTestRule.onNodeWithTag(outerTag).fetchSemanticsNode().boundsInRoot
        val contentBounds = composeTestRule.onNodeWithTag(contentTag).fetchSemanticsNode().boundsInRoot

        val expectedLeftOffset = (outerBounds.width - contentBounds.width) / 2f
        // Relative to the outer container (not the root), so this holds regardless of any window
        // inset offsetting both boxes equally.
        val actualLeftOffset = contentBounds.left - outerBounds.left

        assert(kotlin.math.abs(actualLeftOffset - expectedLeftOffset) < 1f) {
            "Expected content to be centered (offset ~$expectedLeftOffset px from the outer box), " +
                "was offset by $actualLeftOffset px"
        }
    }

    @Test
    fun expanded_availableWidthNarrowerThanMax_contentDoesNotOverflow() {
        // 500dp is narrower than the 640dp max: widthIn(max = 640.dp) must not force an overflow —
        // the content should just take the whole (narrower) available width, same as Compact would.
        setContainer(outerWidth = 500.dp, widthSizeClass = WindowWidthSizeClass.Expanded)

        composeTestRule.onNodeWithTag(contentTag).assertWidthIsEqualTo(500.dp)
    }
}
