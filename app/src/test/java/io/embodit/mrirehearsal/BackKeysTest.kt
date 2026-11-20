package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.BackRule
import io.embodit.mrirehearsal.platform.BackKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA #5 q5-2 (profile D60 补): BACK / controller B go to one handler, once per press, and never exit. */
class BackKeysTest {
    @Test
    fun backAndButtonBAreConsumed_handlerOncePerPressOnUp() {
        val fired = ArrayList<String>()
        BackKeys.reset()
        BackKeys.handler = { fired += it }
        // DOWN: consumed, nothing fires yet
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_DOWN, 100L, false, "key:Stage"))
        assertEquals(0, fired.size)
        // UP: fires once; a second layer seeing the same press (same downTime) is dropped
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 100L, false, "key:Stage"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 100L, false, "unhandled:tab"))
        assertEquals(1, fired.size)
        // controller B and ESCAPE are BACK too; each new press fires again
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BUTTON_B, BackKeys.ACTION_DOWN, 200L, false, "compose:tab"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BUTTON_B, BackKeys.ACTION_UP, 200L, false, "compose:tab"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_ESCAPE, BackKeys.ACTION_UP, 300L, false, "key:Window"))
        assertEquals(3, fired.size)
        assertTrue(fired[1].endsWith("BUTTON_B"))
        // a cancelled press is consumed and does nothing
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 400L, true, "key:Stage"))
        assertEquals(3, fired.size)
        BackKeys.handler = {}
    }

    @Test
    fun otherKeysAreNotBack() {
        BackKeys.reset()
        var n = 0
        BackKeys.handler = { n++ }
        // A = 96, X = 99, Y = 100, ENTER = 66, DPAD_CENTER = 23: not BACK (the window eats them in Compose, Keys.kt)
        for (code in listOf(96, 99, 100, 66, 23)) {
            assertFalse(BackKeys.route(code, BackKeys.ACTION_UP, code.toLong(), false, "key:Stage"))
        }
        assertEquals(0, n)
        BackKeys.handler = {}
    }

    @Test
    fun backReopensAClosedWindowAndOtherwiseDoesNothing_neverExits() {
        assertEquals(BackRule.Action.REOPEN, BackRule.decide(windowOpen = false, openInFlight = false, hasStage = true))
        assertEquals(BackRule.Action.EATEN_OPEN, BackRule.decide(windowOpen = true, openInFlight = false, hasStage = true))
        assertEquals(BackRule.Action.EATEN_OPENING, BackRule.decide(windowOpen = false, openInFlight = true, hasStage = true))
        assertEquals(BackRule.Action.EATEN_NO_STAGE, BackRule.decide(windowOpen = false, openInFlight = false, hasStage = false))
        // three presses in a row with the window closed: the first reopens, the next two land while it is opening / open
        val seq = listOf(BackRule.decide(false, false, true), BackRule.decide(false, true, true), BackRule.decide(true, false, true))
        assertEquals(listOf(BackRule.Action.REOPEN, BackRule.Action.EATEN_OPENING, BackRule.Action.EATEN_OPEN), seq)
    }
}
