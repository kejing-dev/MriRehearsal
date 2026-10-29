package io.embodit.mrirehearsal

import io.embodit.mrirehearsal.domain.Grade
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.Motion
import io.embodit.mrirehearsal.domain.NextLoudness
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.PostureJudge
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.RunEngine
import io.embodit.mrirehearsal.domain.ScanFrame
import io.embodit.mrirehearsal.domain.Score
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.StillnessJudge
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.lastAfterGap
import io.embodit.mrirehearsal.domain.nextLoudness
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The design package's numbers (`model.stillness.unitTests` U1–U11, `model.sequences`), driven through the same
 * domain code the app runs. Head samples at 60 Hz; the head sits at (0, 1.2, 0) looking along −Z.
 */
class DesignNumbersTest {
    private val base = V3(0f, 1.2f, 0f)

    /** A head turned by [deg] about world up around the neck pivot (a real head turn: the pivot stays put). */
    private fun turned(deg: Float, around: Q4 = Q4.IDENTITY): Pair<V3, Q4> {
        val pivot = StillnessJudge.pivotOf(base, around)
        val rot = (Q4.axisAngle(V3.UP, deg) * around).normalized()
        return (pivot + rot.forward * StillnessJudge.PIVOT_M) to rot
    }

    /**
     * Runs one sequence of [seconds] in [engine], the head given per time by [pose] (seconds → position, rotation).
     * The level clock starts at [levelStart].
     */
    private fun sequence(engine: RunEngine, seconds: Float, levelStart: Float = 0f, startMs: Long = 0L, pose: (Float) -> Pair<V3, Q4>): Long {
        // the reference is the resting head at the sequence's first frame; the profile applies from then on
        engine.startSequence(base, Q4.IDENTITY)
        val steps = (seconds * 60f).toInt()
        var ms = startMs
        for (i in 0..steps) {
            val t = i / 60f
            ms = startMs + (t * 1000f).toLong()
            val (p, q) = pose(t)
            engine.sample(ms, levelStart + t, p, q)
        }
        engine.endSequence(levelStart + seconds)
        return ms
    }

    private fun score(engine: RunEngine, level: Level): Int {
        val t = engine.totals()
        return Score.of(t.events, t.minorSec, level.sequenceSec.toFloat())
    }

    @Test
    fun u1_stillFor30sIsSteady100() {
        val e = RunEngine()
        sequence(e, 30f) { base to Q4.IDENTITY }
        assertEquals(0, e.totals().events)
        assertEquals(100, score(e, Level.L1))
        assertEquals(Grade.STEADY, Score.grade(score(e, Level.L1)))
    }

    @Test
    fun u2_oneBigMoveOfOneSecondIs84Good() {
        val e = RunEngine()
        sequence(e, 30f) { t -> (if (t >= 10f && t < 11f) base + V3(0.012f, 0f, 0f) else base) to Q4.IDENTITY }
        val t = e.totals()
        assertEquals(1, t.events)
        assertEquals(1.0f, t.minorSec, 0.06f)
        assertEquals(84, score(e, Level.L1))
        assertEquals(Grade.GOOD, Score.grade(84))
    }

    @Test
    fun u3_twoDegreesAllAlongIs60() {
        val e = RunEngine()
        sequence(e, 30f) { turned(2f) }
        val t = e.totals()
        assertEquals(0, t.events)
        assertEquals(30f, t.minorSec, 0.1f)
        assertEquals(60, score(e, Level.L1))
        assertEquals(Grade.RETRY, Score.grade(60))
    }

    @Test
    fun u4_belowBothMinorThresholdsCountsNothing() {
        val e = RunEngine()
        sequence(e, 30f) { val (p, q) = turned(1f); (p + V3(0.003f, 0f, 0f)) to q }
        val t = e.totals()
        assertEquals(0, t.events)
        assertEquals(0f, t.minorSec, 0.001f)
        assertEquals(100, score(e, Level.L1))
    }

    @Test
    fun u5_twoMajorsHalfASecondApartAreOneEvent() {
        val e = RunEngine()
        sequence(e, 10f) { t ->
            val dx =
                when {
                    t >= 2.0f && t < 3.0f -> 0.012f // major
                    t >= 3.0f && t < 3.4f -> 0f // back inside minor for 0.4 s
                    t >= 3.4f && t < 3.5f -> 0.006f // between minor and major
                    t >= 3.5f && t < 4.5f -> 0.012f // major again, 500 ms after the first ended
                    else -> 0f
                }
            (base + V3(dx, 0f, 0f)) to Q4.IDENTITY
        }
        assertEquals(1, e.totals().events)
    }

    @Test
    fun u5b_twoMajorsTwoSecondsApartAreTwoEvents() {
        val e = RunEngine()
        sequence(e, 10f) { t -> (base + V3(if ((t >= 2f && t < 3f) || (t >= 5f && t < 6f)) 0.012f else 0f, 0f, 0f)) to Q4.IDENTITY }
        assertEquals(2, e.totals().events)
    }

    @Test
    fun u6_lyingAndSittingUseTheSameFormula() {
        // the judge never looks at b: the same relative movement in another frame gives the same score
        val lie = RunEngine()
        val sit = RunEngine()
        val other = Q4.axisAngle(V3.UP, 90f)
        sequence(lie, 30f) { t -> (if (t in 5f..6.5f) base + V3(0.011f, 0f, 0f) else base) to Q4.IDENTITY }
        sequence(sit, 30f) { t -> (if (t in 5f..6.5f) base + other.rotate(V3(0.011f, 0f, 0f)) else base) to Q4.IDENTITY }
        assertEquals(score(lie, Level.L1), score(sit, Level.L1))
        val fLie = ScanFrame.from(base, Q4.axisAngle(V3.RIGHT, 90f), Posture.LIE)
        val fSit = ScanFrame.from(base, Q4.IDENTITY, Posture.SIT)
        assertEquals(1f, fLie.b.length, 1e-4f)
        assertEquals(1f, fSit.b.length, 1e-4f)
    }

    @Test
    fun u7_level3TwoDegreesInEverySequenceIs60() {
        val e = RunEngine()
        var ms = 0L
        val level = Level.L3
        for ((i, seg) in level.segments.withIndex()) {
            if (seg.type.isGap) {
                ms += seg.seconds * 1000L
                continue
            }
            ms = sequence(e, seg.seconds.toFloat(), level.startOf(i).toFloat(), ms) { turned(2f) } + 17L
        }
        val t = e.totals()
        assertEquals(268f, t.minorSec, 0.5f)
        assertEquals(60, score(e, level))
    }

    @Test
    fun u8_resumeDropsOnlyTheCurrentSequence() {
        val e = RunEngine()
        // sequence 1: one second of small movement
        val ms = sequence(e, 20f) { t -> (if (t >= 5f && t < 6f) base + V3(0.006f, 0f, 0f) else base) to Q4.IDENTITY }
        // sequence 2: a big movement, then the call: the sequence is dropped
        e.startSequence(base, Q4.IDENTITY)
        for (i in 0..120) e.sample(ms + 1000L + i * 16L, 28f + i / 60f, base + V3(if (i > 10) 0.012f else 0f, 0f, 0f), Q4.IDENTITY)
        assertEquals(1, e.reading().events)
        e.dropSequence()
        // resume: sequence 2 from the start, still this time
        sequence(e, 60f, 28f, ms + 5000L) { base to Q4.IDENTITY }
        val t = e.totals()
        assertEquals(0, t.events)
        assertEquals(1f, t.minorSec, 0.06f)
    }

    @Test
    fun u9_pureRotationAboutTheNeckHasNoPivotDisplacement() {
        val (p, q) = turned(2.5f)
        val raw = (p - base).length * 1000f
        assertEquals(3.9f, raw, 0.1f)
        val j = StillnessJudge()
        j.startSequence(base, Q4.IDENTITY)
        var r = j.sample(0, 0f, p, q)
        for (i in 1..30) r = j.sample(i * 16L, i / 60f, p, q)
        assertTrue("pivot displacement ${r.displacementMm} mm", r.displacementMm < 0.2f)
        assertEquals(2.5f, r.angleDeg, 0.02f)
        assertEquals(Motion.MINOR, r.motion)
    }

    @Test
    fun u10_postureHysteresis() {
        val j = PostureJudge(Posture.SIT)
        assertEquals(Posture.SIT, j.feed(0.35f))
        assertEquals(Posture.SIT, j.feed(0.65f))
        assertEquals(Posture.LIE, j.feed(0.75f))
        assertEquals(Posture.LIE, j.feed(0.65f))
        assertEquals(Posture.SIT, j.feed(0.55f))
    }

    @Test
    fun u11_worldOffsetPaths() {
        assertEquals(0f, Slide.callTarget(0.45f), 1e-6f)
        assertEquals(0.5f, Slide.callTarget(1.5f), 1e-5f)
        assertEquals(0.8f, Slide.callTarget(1.8f), 1e-5f)
        assertEquals(1.8f, Slide.RESUME_TARGET, 0f)
        assertEquals(0f, Slide.ABANDON_TARGET, 0f)
        // the three paths end where they should from any start
        for (start in listOf(0f, 0.45f, 1.0f, 1.5f, 1.8f)) {
            for ((target, speed) in listOf(Slide.callTarget(start) to Slide.FAST, Slide.RESUME_TARGET to Slide.FAST, Slide.ABANDON_TARGET to Slide.FAST)) {
                var s = Slide(s = start).toward(target, speed)
                var steps = 0
                while (s.moving && steps < 10_000) {
                    s = s.step(1 / 60f)
                    steps++
                }
                assertEquals("from $start to $target", target, s.s, 1e-4f)
            }
        }
    }

    @Test
    fun slideInTakesAbout12Seconds() {
        var s = Slide().toward(Slide.MAX, Slide.SLOW)
        var t = 0f
        var at6 = -1f
        while (s.moving && t < 60f) {
            s = s.step(1 / 60f)
            t += 1 / 60f
            if (at6 < 0f && t >= 6f) at6 = s.s
        }
        assertEquals(12.5f, t, 0.3f) // 12 s at 0.15 m/s plus half of the 0.5 s ease at each end
        assertEquals(0.86f, at6, 0.05f) // QA's "S04 at 6 s": the front face nearly at the top of the head
        // the call from the isocentre: 1.0 m at 0.25 m/s, 4 s plus the ease
        var c = Slide(s = 1.8f).toward(Slide.callTarget(1.8f), Slide.FAST)
        var tc = 0f
        while (c.moving && tc < 60f) {
            c = c.step(1 / 60f)
            tc += 1 / 60f
        }
        assertEquals(4.5f, tc, 0.3f)
    }

    @Test
    fun levelTimelines() {
        assertEquals(30, Level.L1.totalSec)
        assertEquals(120, Level.L2.totalSec)
        assertEquals(300, Level.L3.totalSec)
        assertEquals(30, Level.L1.sequenceSec)
        assertEquals(104, Level.L2.sequenceSec)
        assertEquals(268, Level.L3.sequenceSec)
        // gap captions: louder / same / louder / quieter in L3 (R1-14, R2-6); the last gap announces the last one
        assertEquals(NextLoudness.LOUDER, nextLoudness(Level.L3, 1))
        assertEquals(NextLoudness.SAME, nextLoudness(Level.L3, 3))
        assertEquals(NextLoudness.LOUDER, nextLoudness(Level.L3, 5))
        assertEquals(NextLoudness.QUIETER, nextLoudness(Level.L3, 7))
        assertTrue(lastAfterGap(Level.L3, 7))
        assertEquals(NextLoudness.LOUDER, nextLoudness(Level.L2, 1))
        assertEquals(NextLoudness.LOUDER, nextLoudness(Level.L2, 3))
        assertTrue(lastAfterGap(Level.L2, 3))
    }

    @Test
    fun panelFacingKeepsItsTopUpAndNeverMirrors() {
        // lying: the card is above the face, its top towards the top of the head (−b), its face down at the eyes
        val b = V3(0f, 0f, 1f)
        val q = Q4.facing(V3(0f, -1f, 0f), -b)
        val face = q.rotate(V3(0f, 0f, 1f))
        val top = q.rotate(V3(0f, 1f, 0f))
        val right = q.rotate(V3(1f, 0f, 0f))
        assertEquals(-1f, face.y, 1e-4f)
        assertEquals(-1f, top.z, 1e-4f)
        // right-handed: right × top = face (no mirror)
        val c = right.cross(top)
        assertTrue(abs(c.x - face.x) + abs(c.y - face.y) + abs(c.z - face.z) < 1e-3f)
        // degenerate up (parallel to the face) falls back to world up / ahead without NaN
        val d = Q4.facing(V3(0f, 0f, 1f), V3(0f, 0f, 1f))
        assertTrue(d.isFinite())
    }

    @Test
    fun lyingFrameUsesTheTopOfTheHead() {
        // lying on the back with the top of the head towards −Z: forward = up, head up = −Z
        val lying = Q4.axisAngle(V3.RIGHT, 90f) // turns −Z (forward) to +Y and +Y (up) to +Z... check below
        val fwd = lying.forward
        val up = lying.up
        assertEquals(1f, fwd.y, 1e-4f)
        val f = ScanFrame.from(base, lying, Posture.LIE)
        // b = −(head up projected): towards the feet
        assertEquals(-up.z, f.b.z, 1e-4f)
        // right hand of a person lying face up with the feet towards b
        val r = f.right
        assertEquals(0f, r.y, 1e-4f)
        assertEquals(1f, r.length, 1e-4f)
        // sitting: the gantry stands in front, so b points backwards
        val s = ScanFrame.from(base, Q4.IDENTITY, Posture.SIT)
        assertEquals(1f, s.b.z, 1e-4f)
        assertEquals(1f, s.right.x, 1e-4f)
    }
}
