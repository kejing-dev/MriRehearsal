package io.embodit.mrirehearsal.domain

/** Lying on the back (the main path) or sitting / standing (seated mode, the emulator's default). */
enum class Posture { LIE, SIT }

/**
 * Posture judge (design `model.coordinateFrame`, U10): lying = F·worldUp ≥ 0.7 (looking up ≥ 45°), back to
 * sitting below 0.6. Evaluated every 0.5 s in S01, locked at T1.
 */
class PostureJudge(start: Posture = Posture.SIT) {
    var posture: Posture = start
        private set

    fun feed(forwardDotUp: Float): Posture {
        posture =
            when (posture) {
                Posture.SIT -> if (forwardDotUp >= LIE_AT) Posture.LIE else Posture.SIT
                Posture.LIE -> if (forwardDotUp < SIT_BELOW) Posture.SIT else Posture.LIE
            }
        return posture
    }

    fun set(p: Posture) {
        posture = p
    }

    companion object {
        const val LIE_AT = 0.7f
        const val SIT_BELOW = 0.6f
    }
}

/**
 * The scan room's frame, taken from the head pose at T1 (and again at T10, D9):
 *
 * - [h0] the head position, the isocentre when s = 1.8;
 * - [b] the horizontal unit vector towards the feet. Lying: b = −(head up projected on the floor) (the top of the
 *   head points to −b). Sitting: b = −(head forward projected), so "the top of the head" = straight ahead and the
 *   gantry stands in front (R1-8);
 * - [up] world up; [right] the user's right hand (lying face up: b × up; sitting: up × b).
 *
 * Everything the room builds is laid out in this frame: axial coordinate along b, lateral along right, vertical up.
 */
data class ScanFrame(val h0: V3, val b: V3, val posture: Posture) {
    val up: V3
        get() = V3.UP

    val right: V3
        get() = if (posture == Posture.LIE) b.cross(V3.UP).normalized() else V3.UP.cross(b).normalized()

    /** Point at axial [a] (towards the feet), lateral [l] (to the right), height [y] above the eyes. */
    fun at(a: Float, l: Float, y: Float): V3 = h0 + b * a + right * l + up * y

    /** Rotation of an entity built with local +Z = axial (towards the feet), +Y = up, +X = lateral (b × up). */
    val rotation: Q4
        get() {
            val z = b
            val y = V3.UP
            val x = y.cross(z).normalized()
            return Q4.fromBasis(x, y, z)
        }

    /** Floor height below the eyes (lying: H0.y − 1.15, R2-10; sitting: H0.y − 1.2). */
    val floorBelow: Float
        get() = if (posture == Posture.LIE) 1.15f else 1.2f

    companion object {
        /**
         * Frame from a head pose. Lying uses the head's up vector; if that is (nearly) vertical — a forced
         * `posture=lie` on the emulator, whose camera looks level — the head's forward stands in (b = forward),
         * so the geometry and the flow stay the same (design `verification`).
         */
        fun from(position: V3, rotation: Q4, posture: Posture): ScanFrame {
            val f = rotation.forward.horizontal()
            val u = rotation.up.horizontal()
            val b =
                when (posture) {
                    Posture.LIE -> if (u.length > 0.3f) -u.normalized() else if (f.length > 1e-3f) f.normalized() else V3(0f, 0f, 1f)
                    Posture.SIT -> if (f.length > 1e-3f) -f.normalized() else if (u.length > 1e-3f) u.normalized() else V3(0f, 0f, 1f)
                }
            return ScanFrame(position, b, posture)
        }
    }
}
