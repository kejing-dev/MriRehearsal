package io.embodit.mrirehearsal.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun Float.toRad(): Float = this * (PI.toFloat() / 180f)

fun Float.toDeg(): Float = this * (180f / PI.toFloat())

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Small vector type of the domain, in Stage metres (x right, y up, -z ahead at start). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)

    operator fun times(k: Float) = V3(x * k, y * k, z * k)

    operator fun unaryMinus() = V3(-x, -y, -z)

    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    fun normalized(): V3 {
        val l = length
        return if (l < 1e-6f) this else V3(x / l, y / l, z / l)
    }

    fun dot(o: V3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: V3): V3 = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** This vector with its vertical part removed. */
    fun horizontal(): V3 = V3(x, 0f, z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    override fun toString(): String = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
        val AHEAD = V3(0f, 0f, -1f)
        val RIGHT = V3(1f, 0f, 0f)
    }
}

/** Unit quaternion; rotations compose as `a * b` = apply b first, then a. */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q4): Q4 =
        Q4(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )

    fun rotate(v: V3): V3 {
        // v' = v + 2 w (q x v) + 2 q x (q x v)
        val qv = V3(x, y, z)
        val t = qv.cross(v) * 2f
        return v + t * w + qv.cross(t)
    }

    fun dot(o: Q4): Float = x * o.x + y * o.y + z * o.z + w * o.w

    fun normalized(): Q4 {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-6f) IDENTITY else Q4(x / l, y / l, z / l, w / l)
    }

    /** Head forward (-Z of the head). */
    val forward: V3
        get() = rotate(V3.AHEAD)

    /** Head up (+Y of the head). */
    val up: V3
        get() = rotate(V3.UP)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

    companion object {
        val IDENTITY = Q4(0f, 0f, 0f, 1f)

        /** Rotation of [deg] about the unit [axis]. */
        fun axisAngle(axis: V3, deg: Float): Q4 {
            val a = axis.normalized()
            val h = deg.toRad() / 2f
            val s = sin(h)
            return Q4(a.x * s, a.y * s, a.z * s, cos(h))
        }

        /**
         * Rotation whose local axes map to the given orthonormal world axes ([right] = +X, [up] = +Y, [back] = +Z).
         */
        fun fromBasis(right: V3, up: V3, back: V3): Q4 {
            val m00 = right.x
            val m10 = right.y
            val m20 = right.z
            val m01 = up.x
            val m11 = up.y
            val m21 = up.z
            val m02 = back.x
            val m12 = back.y
            val m22 = back.z
            val trace = m00 + m11 + m22
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Q4((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Q4(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Q4((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Q4((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }.normalized()
        }

        /**
         * Rotation of a panel (or any entity whose face is +Z) so that the face points along [face] and the
         * panel's top is as close as possible to [up]. Falls back to world up, then to world ahead, when [up]
         * is (nearly) parallel to [face]; never a mirror.
         */
        fun facing(face: V3, up: V3): Q4 {
            val z = face.normalized()
            var u = up - z * up.dot(z)
            if (u.length < 0.15f) u = V3.UP - z * z.y
            if (u.length < 0.15f) u = V3.AHEAD - z * V3.AHEAD.dot(z)
            val y = u.normalized()
            val x = y.cross(z).normalized()
            return fromBasis(x, y, z)
        }
    }
}

/** Angle between two orientations, degrees: θ = 2·acos(|q·q0|). */
fun angleBetweenDeg(a: Q4, b: Q4): Float {
    val d = abs(a.normalized().dot(b.normalized())).coerceIn(0f, 1f)
    return (2f * acos(d)).toDeg()
}
