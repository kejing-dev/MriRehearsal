package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.CaptionFit
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.ScanFrame
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.WindowLayout
import kotlin.math.atan2
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA #7 q7-1 (D35 / D16): the in-bore readout strip, all four corners inside the bore's round wall, 60 and 70 cm,
 * lying on the roof (every debug caption offset) and sitting on the far rim (the whole slide), with the head up to
 * [CaptionFit.HEAD_SLACK_M] away from where the frame was taken. Same numbers as MriScene.captionPlace: 420 × 108 dp,
 * scale 0.5 lying / 1.0 sitting, lying at radius − 0.015 at most, sitting at 0.19 m at most, 0.80 m ahead.
 */
class CaptionFitTest {
    private val radii = listOf(0.30f, 0.35f)
    private val wLie = 420 * CaptionFit.M_PER_DP * 0.5f
    private val hLie = 108 * CaptionFit.M_PER_DP * 0.5f
    private val wSit = 420 * CaptionFit.M_PER_DP * 1.0f
    private val hSit = 108 * CaptionFit.M_PER_DP * 1.0f

    private fun lying(tiltDeg: Float = 0f): Q4 = Q4.axisAngle(V3.RIGHT, 90f - tiltDeg).normalized()

    private fun lieFrame(): ScanFrame = WindowLayout.frameFor(V3(0f, 0.75f, 0f), lying(), Posture.LIE)

    private fun sitFrame(): ScanFrame = WindowLayout.frameFor(V3(0f, 1.2f, 0f), Q4.IDENTITY, Posture.SIT)

    private fun up(f: ScanFrame): V3 = if (f.posture == Posture.LIE) -f.b else V3.UP

    private fun eyes(f: ScanFrame): List<V3> = CaptionFit.slackEyes(f)

    @Test
    fun v012Placement_reproducesTheQaFinding() {
        // QA #7: at radius − 0.015 the top corners stand ~0.296 m up at 0.105 m to the side; the wall there is 0.281 m
        val f = lieFrame()
        for (r in radii) {
            val worst = CaptionFit.maxCornerRadial(f, 0.13f, r - 0.015f, f.h0, up(f), wLie, hLie)
            assertTrue("r=$r: the old corners were at $worst, inside the wall", worst > r)
        }
        assertEquals(0.281f, CaptionFit.wallUpAt(0.30f, 0.105f), 0.002f)
    }

    @Test
    fun lyingStrip_allFourCornersInsideTheWall_60and70cm_everyOffset_headSlack() {
        val f = lieFrame()
        for (r in radii) for (a in listOf(0.10f, 0.13f, 0.18f, 0.25f)) {
            val y = CaptionFit.fitUp(f, a, r - 0.015f, r, up(f), wLie, hLie)
            assertTrue("never above the design place", y <= r - 0.015f + 1e-6f)
            // placement at H0: every corner keeps the full clearance
            val atH0 = CaptionFit.maxCornerRadial(f, a, y, f.h0, up(f), wLie, hLie)
            assertTrue("r=$r a=$a: corner at $atH0", atH0 <= r - CaptionFit.CLEARANCE_M + 1e-4f)
            // the strip turns to the actual head (±3 cm on every axis): the full clearance is kept for each
            for (eye in eyes(f)) {
                val m = CaptionFit.maxCornerRadial(f, a, y, eye, up(f), wLie, hLie)
                assertTrue("r=$r a=$a eye=$eye: corner at $m", m <= r - CaptionFit.CLEARANCE_M + 1e-4f)
            }
        }
    }

    @Test
    fun lyingStrip_linesOfSightToEveryCornerStayInTheBore() {
        val f = lieFrame()
        for (r in radii) {
            val y = CaptionFit.fitUp(f, 0.13f, r - 0.015f, r, up(f), wLie, hLie)
            for (eye in eyes(f)) for (c in CaptionFit.corners(f.at(0.13f, 0f, y), eye, up(f), wLie, hLie)) {
                for (i in 0..20) {
                    val p = eye + (c - eye) * (i / 20f)
                    assertTrue("r=$r: sight line to $c leaves the bore", CaptionFit.radial(f, p) < r)
                }
            }
        }
    }

    @Test
    fun lyingStrip_isTightNotJustLowered() {
        // the fit lowers only as far as needed (worst corner within 1 mm of the limit) and stays readable
        val f = lieFrame()
        for (r in radii) {
            val y = CaptionFit.fitUp(f, 0.13f, r - 0.015f, r, up(f), wLie, hLie)
            val worst = CaptionFit.worstCornerRadial(f, 0.13f, y, up(f), wLie, hLie)
            assertEquals(r - CaptionFit.CLEARANCE_M, worst, 0.001f)
            val dist = (f.at(0.13f, 0f, y) - f.h0).length
            assertTrue("r=$r: strip $dist m from the eyes", dist in 0.25f..0.40f)
            // 24 sp caption at scale 0.5 ≈ 12 mm: ≥ 0.5 deg cap height (D13) with room to spare
            val capDeg = Math.toDegrees(atan2(0.012 * 0.7, dist.toDouble())).toFloat()
            assertTrue("cap height $capDeg deg", capDeg >= 0.5f)
        }
    }

    @Test
    fun sittingStrip_cornersInsideTheBore_wholeSlide_60and70cm() {
        val f = sitFrame()
        for (r in radii) {
            val y = CaptionFit.fitUp(f, -0.80f, 0.19f, r, up(f), wSit, hSit)
            assertTrue(y <= 0.19f + 1e-6f)
            for (eye in eyes(f)) {
                val m = CaptionFit.maxCornerRadial(f, -0.80f, y, eye, up(f), wSit, hSit)
                assertTrue("sit r=$r eye=$eye: corner at $m", m <= r - CaptionFit.CLEARANCE_M + 1e-4f)
            }
            // during the slide (S04, s from 0 to 1.8) the bore passes over the strip: the corners are inside wherever
            // the bore is (the bore's cross-section is the same along its length)
            for (step in 0..18) {
                val s = Slide.MAX * step / 18f
                val boreFar = (s - Slide.MAX) - 0.80f
                val boreNear = (s - Slide.MAX) + 0.80f
                val inBore = -0.80f in boreFar..boreNear
                if (inBore) assertTrue(CaptionFit.maxCornerRadial(f, -0.80f, y, f.h0, up(f), wSit, hSit) < r)
            }
        }
    }

    @Test
    fun auditTable() {
        val rows = ArrayList<String>()
        for ((label, f, a, pref, w, h) in listOf(
            Row("lie", lieFrame(), 0.13f, null, wLie, hLie),
            Row("sit", sitFrame(), -0.80f, 0.19f, wSit, hSit),
        )) for (r in radii) {
            val p = pref ?: (r - 0.015f)
            val before = CaptionFit.maxCornerRadial(f, a, p, f.h0, up(f), w, h)
            val y = CaptionFit.fitUp(f, a, p, r, up(f), w, h)
            var worst = 0f
            for (eye in eyes(f)) worst = max(worst, CaptionFit.maxCornerRadial(f, a, y, eye, up(f), w, h))
            rows += "| $label | ${(r * 200).toInt()} cm | ${"%.3f".format(p)} → ${"%.3f".format(y)} | ${"%.3f".format(before)} | ${"%.3f".format(worst)} | ${"%.1f".format((r - worst) * 100)} cm |"
            assertTrue("$label r=$r: worst corner $worst", r - worst >= CaptionFit.CLEARANCE_M - 1e-4f)
        }
        println("| posture | bore | centre up (m) before → after | worst corner before (m) | worst corner after, head ±3 cm (m) | clearance |")
        rows.forEach(::println)
    }

    private data class Row(val label: String, val f: ScanFrame, val a: Float, val pref: Float?, val w: Float, val h: Float)
}
