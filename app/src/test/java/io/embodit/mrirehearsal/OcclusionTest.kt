package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.Occlusion
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.V3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA q2-1: a collider on a line of sight through a shown panel must be taken out. Sitting S02 numbers. */
class OcclusionTest {
    private val eye = V3(0f, 1.34f, 0f)

    // main card 0.9 m ahead, 8 degrees down, 0.31 x 0.26 m, facing the eye
    private val card = Occlusion.PanelRect(V3(0f, 1.215f, -0.891f), Q4.IDENTITY, 0.307f, 0.256f)

    @Test
    fun wallBehindTheCardIsOut() {
        // the head wall 3 m ahead (5 x 2.9 x 0.05 m)
        val wall = Occlusion.Box(V3(0f, 1.34f + 1.45f - 1.2f, -3.025f), Q4.IDENTITY, V3(5f, 2.9f, 0.05f))
        assertTrue(Occlusion.behind(eye, card, wall))
    }

    @Test
    fun sideWallsAndBoxesBesideTheCardStay() {
        val left = Occlusion.Box(V3(-2.525f, 1.59f, 0f), Q4.IDENTITY, V3(0.05f, 2.9f, 6f))
        assertFalse(Occlusion.behind(eye, card, left))
        // (the card's own grab bar is not subject to the guard: it is in the card's plane, see MriScene.updateHits)
        // the floor below the line of sight
        val floor = Occlusion.Box(V3(0f, 0.115f, 0f), Q4.IDENTITY, V3(5f, 0.05f, 6f))
        assertFalse(Occlusion.behind(eye, card, floor))
    }

    @Test
    fun thingsBetweenTheEyeAndThePanelDoNotCount() {
        // a box between the eye and the card is in front of it, not behind (the pointer reaches it first anyway)
        val near = Occlusion.Box(V3(0f, 1.3f, -0.4f), Q4.IDENTITY, V3(0.1f, 0.1f, 0.1f))
        assertFalse(Occlusion.behind(eye, card, near))
    }

    @Test
    fun turnedBoxesAreTestedInTheirOwnAxes() {
        val turned = Q4.axisAngle(V3.UP, 90f)
        // a 2 m long thin box lying across the line of sight once turned by 90 degrees
        val box = Occlusion.Box(V3(0f, 1.25f, -2f), turned, V3(0.05f, 0.4f, 2f))
        assertTrue(Occlusion.behind(eye, card, box))
        val along = Occlusion.Box(V3(1.5f, 1.25f, -2f), turned, V3(0.05f, 0.4f, 2f))
        assertFalse(Occlusion.behind(eye, card, along))
    }

    @Test
    fun wallGetsAHoleBehindTheCardAndKeepsTheRest() {
        // sitting S06: the head wall 1.2 m ahead, the caption strip 0.8 m ahead and 0.19 m up (0.336 x 0.077 m)
        val strip = Occlusion.PanelRect(V3(0f, 1.34f + 0.19f, -0.8f), Q4.IDENTITY, 0.336f, 0.077f)
        val wall = Occlusion.Box(V3(0f, 1.34f + 0.25f, -1.225f), Q4.IDENTITY, V3(5f, 2.9f, 0.05f))
        val hole = Occlusion.hole(eye, listOf(strip), wall)!!
        // straight ahead (eye height, y = 1.34 -> local -0.25) is outside the hole: pointing ahead still calls
        assertTrue(hole[2] > -0.25f)
        val pieces = Occlusion.cut(wall.size, hole)
        assertTrue(pieces.size == 4)
        // no piece lies on a line of sight through the strip
        for ((c, size) in pieces) {
            val box = Occlusion.Box(wall.center + c, Q4.IDENTITY, size)
            assertFalse(Occlusion.behind(eye, strip, box))
        }
        // the straight-ahead line hits one of the pieces
        val ahead = pieces.any { (c, size) -> Occlusion.segmentHitsBox(eye, V3(0f, 0f, -1f), 5f, Occlusion.Box(wall.center + c, Q4.IDENTITY, size)) }
        assertTrue(ahead)
    }

    @Test
    fun noPanelNoHole() {
        val wall = Occlusion.Box(V3(-2.525f, 1.59f, 0f), Q4.IDENTITY, V3(0.05f, 2.9f, 6f))
        assertTrue(Occlusion.hole(eye, listOf(card), wall) == null)
        assertTrue(Occlusion.cut(wall.size, null).size == 1)
    }
}
