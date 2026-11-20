package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.VolumeSteps
import io.embodit.mrirehearsal.domain.WindowLayout
import io.embodit.mrirehearsal.domain.toDeg
import io.embodit.mrirehearsal.ui.MriViewModel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main window and the scanner (v0.1.1 build 4): one placement function for every opening path (factory profile
 * 48). The window opens straight away on the actual line of sight, head pitch included (QA #5 q5-1, profile D9 / D10:
 * no level gate), and the room is laid out clear of it: the window's outline with its TabBar and system controls
 * >= 10 degrees from the gantry from either eye (D41 / D35 corner projection, sit 1.2 m and stand 1.6 m eye heights,
 * head level / 30 degrees down / 30 degrees up, lying).
 */
class WindowLayoutTest {
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f): Q4 =
        (Q4.axisAngle(V3.UP, yawDeg) * Q4.axisAngle(V3.RIGHT, pitchDeg) * Q4.axisAngle(V3(0f, 0f, -1f), rollDeg)).normalized()

    /** Lying face up, the top of the head towards +Z (feet towards -Z), looking at the ceiling; [tiltDeg] > 0 looks towards the feet. */
    private fun lying(tiltDeg: Float = 0f): Q4 = (Q4.axisAngle(V3.RIGHT, 90f - tiltDeg)).normalized()

    private fun sepAtPark(eye: V3, openRot: Q4, posture: Posture, relayRot: Q4 = openRot): Float {
        val window = WindowLayout.actualWindow(eye, openRot, posture)
        val frame = WindowLayout.frameFor(eye, relayRot, posture)
        val box = WindowLayout.gantryBox(frame, 0f)
        return WindowLayout.separationBothEyesDeg(eye, openRot, WindowLayout.outline(window), box)
    }

    // ---- every opening path: launch, chip reopen, recall (settings / scan page), lying -------------------------

    @Test
    fun launch_windowClearsTheGantryByTenDegrees_sitAndStand() {
        for (eyeY in listOf(1.2f, 1.6f)) for (yaw in listOf(0f, 37f, -120f)) for (pitch in listOf(-40f, -30f, -10f, 0f, 10f, 30f, 40f)) {
            val eye = V3(0.3f, eyeY, -0.2f)
            val rot = head(yaw, pitch)
            val sep = sepAtPark(eye, rot, Posture.SIT)
            assertTrue("launch eyeY=$eyeY yaw=$yaw pitch=$pitch sep=$sep", sep >= WindowLayout.MIN_SEP_DEG)
        }
    }

    @Test
    fun reopenWhileFacingTheGantry_relaysTheRoomAndStillClears() {
        // the window opened at yaw 0; the user turned to face the bore (80 deg right) and reopens with the chip: the
        // new window opens towards the bore, the room is laid out again around it (MriViewModel.onWindowOpened)
        val eye = V3(0f, 1.2f, 0f)
        val first = WindowLayout.frameFor(eye, head(0f), Posture.SIT)
        val towardsBore = -first.b
        val yawToBore = atan2(-towardsBore.x, -towardsBore.z).toDeg()
        assertEquals(-WindowLayout.GANTRY_YAW_SIT_DEG, yawToBore, 0.5f)
        val facing = head(yawToBore)
        // without the re-lay the new window would sit in front of the gantry
        assertEquals(0f, sepAtPark(eye, facing, Posture.SIT, relayRot = head(0f)), 0f)
        // with it (the code path for S00-S03, S11) it clears
        assertTrue(MriViewModel.relaysOnOpen(Screen.S02))
        assertTrue(sepAtPark(eye, facing, Posture.SIT) >= WindowLayout.MIN_SEP_DEG)
    }

    @Test
    fun recallAfterTurningRound_clears() {
        val eye = V3(-1f, 1.2f, 2f)
        for (yaw in listOf(180f, 90f, -45f)) assertTrue("recall yaw=$yaw", sepAtPark(eye, head(yaw), Posture.SIT) >= WindowLayout.MIN_SEP_DEG)
    }

    @Test
    fun lying_windowAboveTheFaceClearsTheGantryBeyondTheHead() {
        val eye = V3(0f, 0.75f, 0f)
        for (tilt in listOf(-7f, 0f, 20f, 40f)) {
            val rot = lying(tilt)
            assertTrue("supine tilt=$tilt", WindowLayout.supineOk(rot))
            val sep = sepAtPark(eye, rot, Posture.LIE)
            assertTrue("lying tilt=$tilt sep=$sep", sep >= WindowLayout.MIN_SEP_DEG)
        }
        // looking back over the head (towards the gantry) is not a supine pose: the window opens upright on that line of
        // sight (no gate any more, q5-1)
        assertFalse(WindowLayout.supineOk(lying(-15f)))
        // sitting upright is not supine either
        assertFalse(WindowLayout.supineOk(head(0f, 30f)))
    }

    @Test
    fun runStatesDoNotRelay_andTheCallGoesBackToPark() {
        for (s in listOf(Screen.S04, Screen.S05, Screen.S06, Screen.S07, Screen.S09, Screen.S10)) assertFalse("$s", MriViewModel.relaysOnOpen(s))
        for (s in listOf(Screen.S00, Screen.S01, Screen.S02, Screen.S03, Screen.S11)) assertTrue("$s", MriViewModel.relaysOnOpen(s))
        // S09: the world is back at park, so the window opened before the run clears the gantry again (sit and lie)
        assertEquals(0f, Slide.callTarget(Slide.MAX), 0f)
        assertTrue(sepAtPark(V3(0f, 1.2f, 0f), head(0f), Posture.SIT) >= WindowLayout.MIN_SEP_DEG)
        assertTrue(sepAtPark(V3(0f, 0.75f, 0f), lying(), Posture.LIE) >= WindowLayout.MIN_SEP_DEG)
    }

    // ---- q5-1: the window opens on the line of sight at any pitch, the gantry clears it --------------------------

    @Test
    fun windowOpensOnTheLineOfSightAtAnyPitch() {
        val eye = V3(0f, 1.3f, 0f)
        for ((pitch, roll) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -10f to 15f, -60f to 0f, 60f to 0f)) {
            val rot = head(25f, pitch, roll)
            val w = WindowLayout.expectedWindow(eye, rot, Posture.SIT)
            val f = rot.forward.normalized()
            assertEquals(WindowLayout.DIST_M, (w.center - eye).length, 1e-3f)
            // centred on the line of sight, perpendicular to it, facing the eye (not mirrored)
            assertTrue("pitch=$pitch centre off the line of sight", (w.center - eye).normalized().dot(f) > 0.9999f)
            assertTrue("pitch=$pitch roll=$roll faces the eye", w.normal.dot((eye - w.center).normalized()) > 0.999f)
            // no roll: its right edge stays level, its up leans back with the pitch only
            assertEquals("pitch=$pitch roll=$roll right level", 0f, w.right.y, 1e-4f)
            assertEquals(0f, w.up.dot(f), 1e-4f)
        }
    }

    /** QA #5 q5-1 cold starts: head level, 30 deg down, 30 deg up; the window opens there and the room goes around it. */
    @Test
    fun coldStartLevelDownUp30_gantryClearOfTheWindow_andRoomComesInFrontAgain() {
        for (eyeY in listOf(1.2f, 1.6f)) for (pitch in listOf(0f, -30f, 30f)) {
            val eye = V3(0f, eyeY, 0f)
            val rot = head(0f, pitch)
            val p = WindowLayout.place(eye, rot, Posture.SIT)
            val box = WindowLayout.gantryBox(p.frame, 0f)
            val sep = WindowLayout.separationBothEyesDeg(eye, rot, WindowLayout.outline(p.window), box)
            assertTrue("cold start eyeY=$eyeY pitch=$pitch sep=$sep", sep >= WindowLayout.MIN_SEP_DEG)
            // the window itself is where the head looks: every outline corner within the 39 deg default view
            assertTrue(WindowLayout.maxCornerOffAxisDeg(eye, rot, WindowLayout.outline(p.window)) <= 39f)
            // the gantry box is never between the eye and any point of the window (it cannot cover the window, nor the window it)
            assertTrue(sep > 0f)
        }
    }

    @Test
    fun gantryTurnsFurtherOnlyWhenTheWindowNeedsIt() {
        val eye = V3(0f, 1.2f, 0f)
        // the design's 80 deg is kept with the head level ...
        for (pitch in listOf(0f, -10f, 10f)) assertEquals("pitch=$pitch", WindowLayout.GANTRY_YAW_SIT_DEG, WindowLayout.frameFor(eye, head(0f, pitch), Posture.SIT).gantryYawDeg, 0f)
        // ... with the head 30 deg down or up (the window's outer corner comes within 10 deg of the gantry at 80 deg) or
        // further, the room turns further and clears it
        for (pitch in listOf(-30f, 30f, -60f, 60f)) {
            val r = head(0f, pitch)
            val f = WindowLayout.frameFor(eye, r, Posture.SIT)
            val sep = WindowLayout.separationBothEyesDeg(eye, r, WindowLayout.outline(WindowLayout.expectedWindow(eye, r, Posture.SIT)), WindowLayout.gantryBox(f, 0f))
            println("AUDIT | sit 1.2 m | pitch ${pitch.toInt()} | gantry yaw ${f.gantryYawDeg} | sep %.1f |".format(sep))
            assertTrue("pitch=$pitch sep=$sep", sep >= WindowLayout.MIN_SEP_DEG)
        }
        // a window that was dragged towards the gantry and then reopened there: the room turns further (or the other way)
        val rot = head(0f)
        val towards = head(-70f) // the window sits 70 deg right, where the 80 deg gantry would be
        val w = WindowLayout.expectedWindow(eye, towards, Posture.SIT)
        val f = WindowLayout.frameFor(eye, rot, Posture.SIT, w)
        assertTrue("turned ${f.gantryYawDeg}", f.gantryYawDeg != WindowLayout.GANTRY_YAW_SIT_DEG)
        assertTrue(WindowLayout.separationBothEyesDeg(eye, rot, WindowLayout.outline(w), WindowLayout.gantryBox(f, 0f)) >= WindowLayout.MIN_SEP_DEG)
    }

    @Test
    fun windowOutlineStaysInTheDefaultView() {
        // every corner of the window + TabBar + system controls within 39 deg of the line of sight (D14 / D35)
        val eye = V3(0f, 1.2f, 0f)
        for (pitch in listOf(-30f, -10f, 0f, 10f, 30f)) {
            val rot = head(0f, pitch)
            val o = WindowLayout.outline(WindowLayout.actualWindow(eye, rot, Posture.SIT))
            assertTrue("pitch=$pitch", WindowLayout.maxCornerOffAxisDeg(eye, rot, o) <= 39f)
        }
        val lie = lying()
        val o = WindowLayout.outline(WindowLayout.actualWindow(V3(0f, 0.75f, 0f), lie, Posture.LIE))
        assertTrue(WindowLayout.maxCornerOffAxisDeg(V3(0f, 0.75f, 0f), lie, o) <= 39f)
    }

    @Test
    fun lyingCaptionPrePositionIsOutsideTheWindowAboveTheFace() {
        // MriScene.captionPlace before the head is in the bore: 0.6 m up, 0.32 m towards the feet, 420 x 108 dp at 0.8
        val eye = V3(0f, 0.75f, 0f)
        val rot = lying()
        val frame = WindowLayout.frameFor(eye, rot, Posture.LIE)
        val strip = frame.at(0.32f, 0f, 0.6f)
        val halfH = 108 * WindowLayout.DP_M * 0.8f / 2f
        val nearEdge = strip - frame.b * halfH // its edge towards the head
        val o = WindowLayout.outline(WindowLayout.actualWindow(eye, rot, Posture.LIE))
        // the strip's near edge is further towards the feet (from the eye) than the window's controls edge
        val feetEdge = (o[2] + o[3]) * 0.5f
        val a = atan2((nearEdge - eye).dot(frame.b), (nearEdge - eye).y).toDeg()
        val w = atan2((feetEdge - eye).dot(frame.b), (feetEdge - eye).y).toDeg()
        assertTrue("strip at $a deg, window controls at $w deg", a - w >= 2f)
    }

    /** The numbers for ui-audit.md §10 (occlusion table): printed, and each must clear 10 deg. */
    @Test
    fun auditTable() {
        val rows = ArrayList<String>()
        for ((label, eye) in listOf("sit 1.2 m" to V3(0f, 1.2f, 0f), "stand 1.6 m" to V3(0f, 1.6f, 0f))) {
            for (turn in listOf(0f, 90f, 180f)) for (pitch in listOf(-30f, 0f, 30f)) {
                val rot = head(-turn, pitch) // turned right by [turn], the window (re)opened there, the room re-laid
                val sep = sepAtPark(eye, rot, Posture.SIT)
                val off = WindowLayout.maxCornerOffAxisDeg(eye, rot, WindowLayout.outline(WindowLayout.actualWindow(eye, rot, Posture.SIT)))
                rows += "| $label | turned ${turn.toInt()} deg, pitch ${pitch.toInt()} | %.1f | %.1f |".format(sep, off)
                assertTrue(sep >= WindowLayout.MIN_SEP_DEG)
            }
        }
        val eye = V3(0f, 0.75f, 0f)
        for (tilt in listOf(-7f, 0f, 20f, 40f)) {
            val rot = lying(tilt)
            val sep = sepAtPark(eye, rot, Posture.LIE)
            val off = WindowLayout.maxCornerOffAxisDeg(eye, rot, WindowLayout.outline(WindowLayout.actualWindow(eye, rot, Posture.LIE)))
            rows += "| lying | looking ${tilt.toInt()} deg towards the feet | %.1f | %.1f |".format(sep, off)
            assertTrue(sep >= WindowLayout.MIN_SEP_DEG)
        }
        println("AUDIT | eye | head when the window opened | window outline to gantry, min of both eyes (deg) | farthest outline corner from the line of sight (deg) |")
        rows.forEach { println("AUDIT $it") }
    }

    // ---- volume (B20): three steps ------------------------------------------------------------------------------

    @Test
    fun volumeSnapsToThreeStepsOnTapsAndSmallDrags() {
        // taps at 0 / ~67 / 100 % of the track (0.4 .. 1.0) land on the three steps
        assertEquals(0.4f, VolumeSteps.snap(0.4f), 0f)
        assertEquals(0.8f, VolumeSteps.snap(0.8f), 0f)
        assertEquals(1.0f, VolumeSteps.snap(1.0f), 0f)
        // small drags stay on their step, a drag past the middle between two steps moves on
        assertEquals(0.8f, VolumeSteps.snap(0.8f + 0.05f), 0f)
        assertEquals(0.8f, VolumeSteps.snap(0.8f - 0.15f), 0f)
        assertEquals(1.0f, VolumeSteps.snap(0.95f), 0f)
        assertEquals(0.4f, VolumeSteps.snap(0.55f), 0f)
        assertEquals(0.8f, VolumeSteps.snap(0.65f), 0f)
        assertEquals(VolumeSteps.DEFAULT, VolumeSteps.snap(VolumeSteps.DEFAULT), 0f)
        assertEquals(listOf(40, 80, 100), VolumeSteps.LEVELS.map { VolumeSteps.percent(it) })
        assertTrue(abs(VolumeSteps.snap(-1f) - 0.4f) < 1e-6f && abs(VolumeSteps.snap(5f) - 1f) < 1e-6f)
    }
}
