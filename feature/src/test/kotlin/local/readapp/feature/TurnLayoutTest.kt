package local.readapp.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page-turn geometry.
 *
 * The backward turn used to place the previous page on the right-hand side, so
 * dragging right animated in the opposite direction from dragging left. These
 * tests pin the mirrored behaviour.
 */
class TurnLayoutTest {

    private val width = 1000

    @Test
    fun forwardTurnFollowsTheFingerLeftAndPinsTheNextPage() {
        val start = turnLayout(forward = true, fraction = 0f, width = width)
        assertEquals("current page covers the screen", 0f, start.baseX, 0f)
        assertEquals("next page waits underneath", 0f, start.targetX, 0f)

        val middle = turnLayout(forward = true, fraction = 0.5f, width = width)
        assertEquals("current page follows the finger to the left", -500f, middle.baseX, 0f)
        assertEquals("next page must not move", 0f, middle.targetX, 0f)

        val end = turnLayout(forward = true, fraction = 1f, width = width)
        assertEquals("current page leaves the screen", -1000f, end.baseX, 0f)
    }

    @Test
    fun backwardTurnKeepsTheCurrentPageStillAndBringsThePreviousPageFromTheLeft() {
        val start = turnLayout(forward = false, fraction = 0f, width = width)
        assertEquals("current page must stay put", 0f, start.baseX, 0f)
        assertEquals("previous page starts off the left edge", -1000f, start.targetX, 0f)

        val middle = turnLayout(forward = false, fraction = 0.5f, width = width)
        assertEquals("current page still must not move", 0f, middle.baseX, 0f)
        assertEquals("previous page has slid halfway in", -500f, middle.targetX, 0f)

        val end = turnLayout(forward = false, fraction = 1f, width = width)
        assertEquals("previous page now covers the screen", 0f, end.targetX, 0f)
    }

    @Test
    fun backwardTurnNeverApproachesFromTheRightEdge() {
        // Regression: the previous page used to animate in from +width.
        for (step in 0..20) {
            val fraction = step / 20f
            val layout = turnLayout(forward = false, fraction = fraction, width = width)
            assertTrue(
                "previous page must stay at or left of x=0, was ${layout.targetX} at fraction=$fraction",
                layout.targetX <= 0f
            )
        }
    }

    @Test
    fun backwardTurnRetracesTheForwardTurnInReverse() {
        // Forward: the moving layer travels 0 -> -width as the gesture advances.
        // Backward: the moving layer travels -width -> 0, so at fraction f it sits
        // exactly where the forward turn was at 1-f. That is the "reverse
        // playback" relationship the two animations are supposed to have.
        for (step in 0..10) {
            val fraction = step / 10f
            val forward = turnLayout(forward = true, fraction = fraction, width = width)
            val backward = turnLayout(forward = false, fraction = 1f - fraction, width = width)
            assertEquals(
                "backward must replay the forward path backwards",
                forward.baseX,
                backward.targetX,
                0.001f
            )
        }
    }

    @Test
    fun thePinnedLayerNeverMoves() {
        for (step in 0..10) {
            val fraction = step / 10f
            assertEquals("next page is pinned underneath", 0f, turnLayout(forward = true, fraction = fraction, width = width).targetX, 0f)
            assertEquals("current page is pinned while going back", 0f, turnLayout(forward = false, fraction = fraction, width = width).baseX, 0f)
        }
    }

    @Test
    fun seamShadowSitsOnTheTrailingEdgeOfTheMovingPage() {
        for (step in 0..10) {
            val fraction = step / 10f
            val forward = turnLayout(forward = true, fraction = fraction, width = width)
            // Moving page spans [baseX, baseX + width]; the seam is at its right edge.
            assertEquals(forward.baseX + width, forward.shadowX, 0.001f)

            val backward = turnLayout(forward = false, fraction = fraction, width = width)
            assertEquals(backward.targetX + width, backward.shadowX, 0.001f)
        }
    }

    @Test
    fun fractionIsClampedToTheUnitRange() {
        assertEquals(0f, turnLayout(forward = true, fraction = -3f, width = width).baseX, 0f)
        assertEquals(-1000f, turnLayout(forward = true, fraction = 7f, width = width).baseX, 0f)
        assertEquals(0f, turnLayout(forward = false, fraction = 7f, width = width).targetX, 0f)
    }

    @Test
    fun zeroWidthDoesNotProduceNaN() {
        val layout = turnLayout(forward = false, fraction = 0.5f, width = 0)
        assertTrue(layout.targetX.isFinite())
        assertTrue(layout.shadowX.isFinite())
    }

    // -----------------------------------------------------------------------
    // Animation pacing. A turn used to run in at most 180ms, which read as a
    // snap rather than a page turn.
    // -----------------------------------------------------------------------

    @Test
    fun aFullSweepTakesTheConfiguredDuration() {
        assertTrue(
            "a committed turn from the very start must run the full duration",
            turnDurationMs(accept = true, fraction = 0f) >= 300L
        )
    }

    @Test
    fun durationScalesWithTheDistanceLeftToTravel() {
        val almostDone = turnDurationMs(accept = true, fraction = 0.95f)
        val halfWay = turnDurationMs(accept = true, fraction = 0.5f)
        val justStarted = turnDurationMs(accept = true, fraction = 0f)
        assertTrue("closer to the target must be quicker", almostDone < halfWay)
        assertTrue("halfway must be quicker than a full sweep", halfWay < justStarted)
    }

    @Test
    fun cancelledTurnsScaleWithHowFarTheyMustReturn() {
        assertTrue(turnDurationMs(accept = false, fraction = 0.9f) > turnDurationMs(accept = false, fraction = 0.1f))
    }

    @Test
    fun durationIsNeverInstantAndNeverSluggish() {
        for (step in 0..20) {
            val fraction = step / 20f
            for (accept in listOf(true, false)) {
                val ms = turnDurationMs(accept, fraction)
                assertTrue("turn of ${ms}ms is too abrupt at fraction=$fraction", ms >= 110L)
                assertTrue("turn of ${ms}ms is too slow at fraction=$fraction", ms <= 300L)
            }
        }
    }
}
