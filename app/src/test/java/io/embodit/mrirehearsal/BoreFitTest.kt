package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.BoreFit
import io.embodit.mrirehearsal.domain.Gantry
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.PostureFollow
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.ScanFrame
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.WindowLayout
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device rework r5 (gate C 2026-11-24: "the thing above my head is in the way, not sure it is in the right place" =
 * the gantry / bore). The bore has to be where the head that is scanned actually is (1:1: 60 cm bore, the eyes on the
 * axis, 0.30 m under the roof; the front face passes the top of the head at s = 1.0), not where the head was at T1.
 */
class BoreFitTest {
    /** Lying face up, the top of the head towards +Z, looking at the ceiling; [tiltDeg] > 0 looks towards the feet. */
    private fun lying(tiltDeg: Float = 0f): Q4 = Q4.axisAngle(V3.RIGHT, 90f - tiltDeg).normalized()

    private fun sitting(yawDeg: Float = 0f, pitchDeg: Float = 0f): Q4 = (Q4.axisAngle(V3.UP, yawDeg) * Q4.axisAngle(V3.RIGHT, pitchDeg)).normalized()

    @Test
    fun lyingRunStart_putsTheEyesOnTheBoreAxis_60and70cm() {
        // T1 pressed with the head raised (looking at the window, 0.25 m higher, 0.15 m towards the feet); then lay back
        val t1Eye = V3(0f, 0.95f, -0.15f)
        val restEye = V3(0f, 0.70f, 0f)
        val old = WindowLayout.frameFor(t1Eye, lying(20f), Posture.LIE)
        val stale = BoreFit.inBore(old, Slide.MAX, restEye, 0.30f)
        assertTrue("stale frame: eye ${stale.offAxisM} m off the axis", stale.offAxisM > 0.2f)

        for (radius in listOf(0.30f, 0.35f)) for (tilt in listOf(0f, 15f, 30f)) {
            val f = WindowLayout.frameFor(restEye, lying(tilt), Posture.LIE)
            val b = BoreFit.inBore(f, Slide.MAX, restEye, radius)
            assertEquals("off axis r=$radius tilt=$tilt", 0f, b.offAxisM, 0.005f)
            assertEquals("roof r=$radius tilt=$tilt", radius, b.roofM, 0.005f)
            assertEquals("at the isocentre axially", 0f, b.axial, 0.005f)
            // the top of the head points away from the feet (-b): looking up, the gantry is beyond the top of the head
            assertTrue(f.b.dot(V3(0f, 0f, 1f)) < -0.9f)
        }
    }

    @Test
    fun frontFace_isOneMetreBeyondTheHeadAtPark_andPassesItAtSOne() {
        val eye = V3(0f, 0.7f, 0f)
        val f = WindowLayout.frameFor(eye, lying(), Posture.LIE)
        // the gantry centre sits at the isocentre H0 + b (s - 1.8); its face towards the feet is half its depth closer
        for ((s, faceAxial) in listOf(0f to -1.0f, 0.5f to -0.5f, Slide.FACE_AT_HEAD to 0f, Slide.MAX to Gantry.D / 2)) {
            val iso = f.h0 + f.b * (s - Slide.MAX)
            val face = (iso + f.b * (Gantry.D / 2) - eye).dot(f.b)
            assertEquals("s=$s", faceAxial, face, 0.01f)
        }
    }

    @Test
    fun runStart_judgesThePostureAgain_unlessSetByHand() {
        // T1 sitting, lay back before choosing the level: looking at the window above the face (F.up 0.95)
        assertEquals(Posture.LIE, BoreFit.postureAtRunStart(Posture.SIT, manual = false, forwardDotUp = 0.95f))
        // still sitting, looking at the window ahead
        assertEquals(Posture.SIT, BoreFit.postureAtRunStart(Posture.SIT, manual = false, forwardDotUp = 0.05f))
        // hysteresis: lying, head lifted a little (0.65) stays lying
        assertEquals(Posture.LIE, BoreFit.postureAtRunStart(Posture.LIE, manual = false, forwardDotUp = 0.65f))
        // the manual links win
        assertEquals(Posture.SIT, BoreFit.postureAtRunStart(Posture.SIT, manual = true, forwardDotUp = 0.95f))
        // no head pose yet: keep
        assertEquals(Posture.LIE, BoreFit.postureAtRunStart(Posture.LIE, manual = false, forwardDotUp = null))
    }

    @Test
    fun sitToLie_afterT1_layingTheRoomAgainMovesTheBoreOntoTheLyingEyes() {
        val sitEye = V3(0f, 1.25f, 0f)
        val lieEye = V3(0f, 0.68f, 0.35f)
        val sitFrame = WindowLayout.frameFor(sitEye, sitting(), Posture.SIT)
        val stale = BoreFit.inBore(sitFrame, Slide.MAX, lieEye, 0.30f)
        assertTrue("sitting frame over a lying user: ${stale.offAxisM} m off the axis", stale.offAxisM > 0.3f)
        val p = BoreFit.postureAtRunStart(Posture.SIT, false, lying(10f).forward.y)
        assertEquals(Posture.LIE, p)
        val f = WindowLayout.frameFor(lieEye, lying(10f), p)
        val b = BoreFit.inBore(f, Slide.MAX, lieEye, 0.30f)
        assertEquals(0f, b.offAxisM, 0.005f)
        assertTrue(BoreFit.jumps(sitFrame, f))
    }

    @Test
    fun jumps_onlyForRealChanges() {
        val eye = V3(0f, 0.7f, 0f)
        val f = ScanFrame(eye, V3(0f, 0f, -1f), Posture.LIE)
        assertFalse(BoreFit.jumps(null, f))
        assertFalse(BoreFit.jumps(f, f.copy(h0 = eye + V3(0.01f, 0.01f, 0f))))
        assertTrue(BoreFit.jumps(f, f.copy(h0 = eye + V3(0f, 0.05f, 0f))))
        assertTrue(BoreFit.jumps(f, f.copy(b = V3(0.1f, 0f, -1f).normalized())))
        assertTrue(BoreFit.jumps(f, f.copy(posture = Posture.SIT)))
    }

    @Test
    fun postureFollow_needsThreeSecondsHeld() {
        val follow = PostureFollow()
        // a glance at the ceiling for 2 s while sitting does not lay the room out again
        var t = 0L
        while (t <= 2000L) {
            assertNull(follow.feed(Posture.LIE, Posture.SIT, t))
            t += 500L
        }
        assertNull(follow.feed(Posture.SIT, Posture.SIT, t))
        // lying back and staying there: after 3 s the room follows
        val start = 10_000L
        var got: Posture? = null
        var at = -1L
        t = start
        while (t <= start + 4000L && got == null) {
            got = follow.feed(Posture.LIE, Posture.SIT, t)
            if (got != null) at = t
            t += 500L
        }
        assertEquals(Posture.LIE, got)
        assertTrue("after ${at - start} ms", abs(at - start - PostureFollow.HOLD_MS) <= 500L)
    }
}
