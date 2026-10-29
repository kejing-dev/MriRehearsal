package io.embodit.mrirehearsal.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. Every entity hangs below the scene root whose local space equals Stage space.
// ---------------------------------------------------------------------------------------------

internal fun V3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): V3 = V3(x, y, z)

internal fun Q4.sdk(): com.pico.spatial.core.math.Quat = com.pico.spatial.core.math.Quat(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: V3? = null, rotation: Q4? = null, scale: V3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children (fades). */
internal fun Entity.setOpacity(opacity: Float) {
    val o = opacity.coerceIn(0f, 1f)
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = o else components.set(OpacityControllerComponent(o))
}

/** A box collider of full [size] (metres), so the system pointer (controller ray / gaze + pinch) can hit the entity. */
internal fun Entity.addBoxCollider(size: V3) {
    runCatching { components.set(CollisionComponent(listOf(ShapeResource.createBox(size.sdk())), PhysicsMaterialResource())) }
}

/** A collider made of several boxes ((local centre, full size) pairs) — a wall with a hole cut out (QA q2-1). */
internal fun Entity.setBoxColliders(boxes: List<Pair<V3, V3>>) {
    runCatching {
        val shapes = boxes.map { (c, s) -> ShapeResource.createBox(s.sdk()).offsetByTranslation(c.sdk()) }
        components.set(CollisionComponent(shapes, PhysicsMaterialResource()))
    }
}

/**
 * Resources shared by several entities are made global so that destroying one user does not release them from the
 * others (Spatial Ruler finding); they are closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

internal fun unlit(
    shared: SharedResources,
    color: Color,
    blending: BlendingMode = BlendingMode.OPAQUE,
    opacity: Float = 1f,
    twoSided: Boolean = false,
    depthWrite: Boolean = true,
    texture: TextureResource? = null,
): UnlitMaterial =
    shared.keep(
        UnlitMaterial.create(blending).apply {
            setBaseColor(color.copy(alpha = 1f).color4())
            if (blending != BlendingMode.OPAQUE) setOpacity(opacity)
            runCatching { setApplyToneMapping(false) }
            if (twoSided) setCullingMode(MaterialCullingMode.NONE)
            if (!depthWrite) setDepthWrite(false)
            if (texture != null) setBaseColorTexture(texture)
        }
    )

/**
 * PBR with a little emissive of its own colour: in Full immersion the only light is the one directional light (no
 * IBL, moon-phases finding), and faces turned away from it would be black.
 */
internal fun pbr(shared: SharedResources, color: Color, roughness: Float, metallic: Float = 0f, ambient: Float = AMBIENT, twoSided: Boolean = false): PhysicallyBasedMaterial =
    shared.keep(
        PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(color.color4())
            setRoughness(roughness)
            setMetallic(metallic)
            runCatching { setEmissiveColor(Color4(color.red * ambient, color.green * ambient, color.blue * ambient, 1f)) }
            if (twoSided) setCullingMode(MaterialCullingMode.NONE)
        }
    )

private const val AMBIENT = 0.35f

/** Colour scaled by [k] (pre-shaded unlit surfaces). */
internal fun Color.shade(k: Float): Color = Color((red * k).coerceIn(0f, 1f), (green * k).coerceIn(0f, 1f), (blue * k).coerceIn(0f, 1f), alpha)

// ---------------------------------------------------------------------------------------------
// Programmatic geometry. All in metres; nothing is loaded from a file.
// ---------------------------------------------------------------------------------------------

/** Collects triangles; every quad / triangle is wound counter-clockwise as seen from its normal's side. */
internal class MeshBuilder {
    private val positions = ArrayList<Vector3>()
    private val normals = ArrayList<Vector3>()
    private val uvs = ArrayList<Vector2>()
    private val indices = ArrayList<Int>()
    private var min = V3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    private var max = V3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    private fun vertex(p: V3, n: V3, uv: Vector2 = Vector2(0f, 0f)): Int {
        positions += p.sdk()
        normals += n.sdk()
        uvs += uv
        min = V3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
        max = V3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
        return positions.size - 1
    }

    /** Quad p0 p1 p2 p3 (in order around it) with per-vertex normals; the winding follows the normals. */
    fun quad(p0: V3, p1: V3, p2: V3, p3: V3, n0: V3, n1: V3 = n0, n2: V3 = n0, n3: V3 = n0, uvFlipV: Boolean = false) {
        val face = (p1 - p0).cross(p2 - p0)
        val avg = n0 + n1 + n2 + n3
        val flip = face.dot(avg) < 0f
        val lo = if (uvFlipV) 0f else 1f
        val hi = 1f - lo
        val a = vertex(p0, n0, Vector2(0f, lo))
        val b = vertex(p1, n1, Vector2(1f, lo))
        val c = vertex(p2, n2, Vector2(1f, hi))
        val d = vertex(p3, n3, Vector2(0f, hi))
        if (!flip) {
            indices += listOf(a, b, c, a, c, d)
        } else {
            indices += listOf(a, c, b, a, d, c)
        }
    }

    /** Axis-aligned box from [lo] to [hi]. */
    fun box(lo: V3, hi: V3) {
        val (x0, y0, z0) = Triple(lo.x, lo.y, lo.z)
        val (x1, y1, z1) = Triple(hi.x, hi.y, hi.z)
        quad(V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y1, z1), V3(x0, y1, z1), V3(0f, 0f, 1f))
        quad(V3(x1, y0, z0), V3(x0, y0, z0), V3(x0, y1, z0), V3(x1, y1, z0), V3(0f, 0f, -1f))
        quad(V3(x1, y0, z1), V3(x1, y0, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(1f, 0f, 0f))
        quad(V3(x0, y0, z0), V3(x0, y0, z1), V3(x0, y1, z1), V3(x0, y1, z0), V3(-1f, 0f, 0f))
        quad(V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y1, z0), V3(x0, y1, z0), V3(0f, 1f, 0f))
        quad(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y0, z1), V3(x0, y0, z1), V3(0f, -1f, 0f))
    }

    /** A box of [size] centred at [c] whose long axis is turned by [rot]. */
    fun orientedBox(c: V3, size: V3, rot: Q4) {
        val hx = size.x / 2
        val hy = size.y / 2
        val hz = size.z / 2
        fun p(x: Float, y: Float, z: Float) = c + rot.rotate(V3(x, y, z))
        fun n(x: Float, y: Float, z: Float) = rot.rotate(V3(x, y, z))
        quad(p(-hx, -hy, hz), p(hx, -hy, hz), p(hx, hy, hz), p(-hx, hy, hz), n(0f, 0f, 1f))
        quad(p(hx, -hy, -hz), p(-hx, -hy, -hz), p(-hx, hy, -hz), p(hx, hy, -hz), n(0f, 0f, -1f))
        quad(p(hx, -hy, hz), p(hx, -hy, -hz), p(hx, hy, -hz), p(hx, hy, hz), n(1f, 0f, 0f))
        quad(p(-hx, -hy, -hz), p(-hx, -hy, hz), p(-hx, hy, hz), p(-hx, hy, -hz), n(-1f, 0f, 0f))
        quad(p(-hx, hy, hz), p(hx, hy, hz), p(hx, hy, -hz), p(-hx, hy, -hz), n(0f, 1f, 0f))
        quad(p(-hx, -hy, -hz), p(hx, -hy, -hz), p(hx, -hy, hz), p(-hx, -hy, hz), n(0f, -1f, 0f))
    }

    val isEmpty: Boolean
        get() = indices.isEmpty()

    fun build(name: String): MeshResource {
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
        val c = (min + max) * 0.5f
        val h = (max - min) * 0.5f
        val half = V3(maxOf(h.x, 0.001f), maxOf(h.y, 0.001f), maxOf(h.z, 0.001f))
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = c.sdk(), halfExtent = half.sdk()), name = name)
    }
}

internal object Meshes {
    /**
     * Unit cube (-0.5..0.5), scaled per entity. `MeshResource.createBox` renders about twice the asked size on the
     * emulator (factory finding), and one shared mesh costs one creation instead of twenty.
     */
    fun unitCube(name: String): MeshResource = MeshBuilder().apply { box(V3(-0.5f, -0.5f, -0.5f), V3(0.5f, 0.5f, 0.5f)) }.build(name)

    /**
     * Rectangle in the local XY plane, centred, facing +Z. Textured quads sample v from the bottom on this SDK (the
     * "F↑" test card came out upside down with v = 0 at the top; same finding as dose-distance), so v = 0 is the bottom.
     */
    fun quad(width: Float, height: Float, name: String): MeshResource {
        val w = width / 2f
        val h = height / 2f
        return MeshBuilder().apply { quad(V3(-w, -h, 0f), V3(w, -h, 0f), V3(w, h, 0f), V3(-w, h, 0f), V3(0f, 0f, 1f), uvFlipV = true) }.build(name)
    }

    /**
     * Outline of the gantry's rounded front (2.10 × 2.30, corner radius 0.35) as points ordered by angle around the
     * bore centre, with their outward normals. Four corner arcs and four straight edges.
     */
    private fun roundedOutline(w: Float, h: Float, r: Float, arcSteps: Int = 12, edgeSteps: Int = 6): List<Pair<V3, V3>> {
        val hw = w / 2
        val hh = h / 2
        val pts = ArrayList<Pair<V3, V3>>()
        // start on the right edge, go counter-clockwise (seen from +Z)
        val corners = listOf(V3(hw - r, hh - r, 0f), V3(-hw + r, hh - r, 0f), V3(-hw + r, -hh + r, 0f), V3(hw - r, -hh + r, 0f))
        for (k in 0 until 4) {
            val c = corners[k]
            val a0 = k * 90.0
            for (i in 0 until arcSteps) {
                val a = Math.toRadians(a0 + 90.0 * i / arcSteps)
                val n = V3(cos(a).toFloat(), sin(a).toFloat(), 0f)
                pts += (c + n * r) to n
            }
            val next = corners[(k + 1) % 4]
            val a1 = Math.toRadians(a0 + 90.0)
            val n = V3(cos(a1).toFloat(), sin(a1).toFloat(), 0f)
            val from = c + n * r
            val to = next + n * r
            for (i in 0 until edgeSteps) pts += (from + (to - from) * (i / edgeSteps.toFloat())) to n
        }
        return pts.sortedBy { (p, _) -> norm360(atan2(p.y, p.x)) }
    }

    private fun norm360(a: Float): Float {
        var x = a
        while (x < 0f) x += (2 * PI).toFloat()
        return x
    }

    /**
     * The gantry body: front and back faces (an annulus from the rounded outline to the chamfer circle r + 0.05) and
     * the outer wall. Axis along local Z, front face (towards the feet) at z = +0.80.
     */
    fun gantryBody(boreRadius: Float, name: String): MeshResource {
        val b = MeshBuilder()
        val outline = roundedOutline(GANTRY_W, GANTRY_H, GANTRY_CORNER)
        val n = outline.size
        val rc = boreRadius + CHAMFER
        val hz = GANTRY_D / 2
        for (i in 0 until n) {
            val (p0, n0) = outline[i]
            val (p1, n1) = outline[(i + 1) % n]
            val a0 = atan2(p0.y, p0.x)
            val a1 = atan2(p1.y, p1.x)
            val c0 = V3(cos(a0) * rc, sin(a0) * rc, 0f)
            val c1 = V3(cos(a1) * rc, sin(a1) * rc, 0f)
            // front (+Z) and back (-Z) annulus faces
            b.quad(V3(c0.x, c0.y, hz), V3(p0.x, p0.y, hz), V3(p1.x, p1.y, hz), V3(c1.x, c1.y, hz), V3(0f, 0f, 1f))
            b.quad(V3(c1.x, c1.y, -hz), V3(p1.x, p1.y, -hz), V3(p0.x, p0.y, -hz), V3(c0.x, c0.y, -hz), V3(0f, 0f, -1f))
            // outer wall
            b.quad(V3(p0.x, p0.y, -hz), V3(p1.x, p1.y, -hz), V3(p1.x, p1.y, hz), V3(p0.x, p0.y, hz), n0, n1, n1, n0)
        }
        return b.build(name)
    }

    /** The bore's inner wall (normals inwards) and the two 0.05 m chamfers at its ends. 64 segments. */
    fun boreInner(r: Float, name: String, segments: Int = 64): MeshResource {
        val b = MeshBuilder()
        val hz = GANTRY_D / 2
        val zIn = hz - CHAMFER
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val d0 = V3(cos(a0), sin(a0), 0f)
            val d1 = V3(cos(a1), sin(a1), 0f)
            // wall
            b.quad(d0 * r + V3(0f, 0f, -zIn), d1 * r + V3(0f, 0f, -zIn), d1 * r + V3(0f, 0f, zIn), d0 * r + V3(0f, 0f, zIn), -d0, -d1, -d1, -d0)
            // chamfers: from r at ±zIn out to r + chamfer at ±hz
            val rc = r + CHAMFER
            val nf0 = (-d0 + V3(0f, 0f, 1f)).normalized()
            val nf1 = (-d1 + V3(0f, 0f, 1f)).normalized()
            b.quad(d0 * r + V3(0f, 0f, zIn), d1 * r + V3(0f, 0f, zIn), d1 * rc + V3(0f, 0f, hz), d0 * rc + V3(0f, 0f, hz), nf0, nf1, nf1, nf0)
            val nb0 = (-d0 + V3(0f, 0f, -1f)).normalized()
            val nb1 = (-d1 + V3(0f, 0f, -1f)).normalized()
            b.quad(d1 * r + V3(0f, 0f, -zIn), d0 * r + V3(0f, 0f, -zIn), d0 * rc + V3(0f, 0f, -hz), d1 * rc + V3(0f, 0f, -hz), nb1, nb0, nb0, nb1)
        }
        return b.build(name)
    }

    /**
     * Two 0.04 m light strips on the inner wall at ±60° from the top (R2-4: the top itself stays dark so the caption
     * strip never sits on a glowing surface).
     */
    fun boreStrips(r: Float, name: String): MeshResource {
        val b = MeshBuilder()
        val rr = r - 0.003f
        val half = STRIP_W / 2 / rr
        val zIn = GANTRY_D / 2 - CHAMFER - 0.02f
        for (center in listOf(PI / 2 - PI / 3, PI / 2 + PI / 3)) {
            val steps = 3
            for (i in 0 until steps) {
                val a0 = (center - half + 2 * half * i / steps).toFloat()
                val a1 = (center - half + 2 * half * (i + 1) / steps).toFloat()
                val d0 = V3(cos(a0), sin(a0), 0f)
                val d1 = V3(cos(a1), sin(a1), 0f)
                b.quad(d0 * rr + V3(0f, 0f, -zIn), d1 * rr + V3(0f, 0f, -zIn), d1 * rr + V3(0f, 0f, zIn), d0 * rr + V3(0f, 0f, zIn), -d0, -d1, -d1, -d0)
            }
        }
        return b.build(name)
    }

    /** The pale blue band round the bore on the front face. */
    fun frontBand(r: Float, name: String, segments: Int = 64): MeshResource {
        val b = MeshBuilder()
        val r0 = r + 0.12f
        val r1 = r + 0.17f
        val z = GANTRY_D / 2 + 0.003f
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val d0 = V3(cos(a0), sin(a0), 0f)
            val d1 = V3(cos(a1), sin(a1), 0f)
            b.quad(d0 * r0 + V3(0f, 0f, z), d0 * r1 + V3(0f, 0f, z), d1 * r1 + V3(0f, 0f, z), d1 * r0 + V3(0f, 0f, z), V3(0f, 0f, 1f))
        }
        return b.build(name)
    }

    /**
     * The head coil: an open birdcage of radius 0.16 around local Z, six 0.012 m rods and two rings at z = −0.12 and
     * z = +0.18, open on the face side (+Y): 0.20 m across and the whole 0.30 m between the rings (R2-3).
     */
    fun headCoil(name: String): MeshResource {
        val b = MeshBuilder()
        val r = COIL_R
        val gapHalf = kotlin.math.asin(COIL_OPEN_W / 2 / r)
        // rods at ±50°, ±100°, ±150° from the face direction
        for (deg in listOf(50f, -50f, 100f, -100f, 150f, -150f)) {
            val a = (PI / 2).toFloat() + Math.toRadians(deg.toDouble()).toFloat()
            val c = V3(cos(a) * r, sin(a) * r, (COIL_Z0 + COIL_Z1) / 2)
            val rot = Q4.axisAngle(V3(0f, 0f, 1f), Math.toDegrees(a.toDouble()).toFloat())
            b.orientedBox(c, V3(COIL_ROD, COIL_ROD, COIL_Z1 - COIL_Z0), rot)
        }
        // rings: thin bands, broken over the face
        val steps = 40
        for (z in listOf(COIL_Z0, COIL_Z1)) {
            val start = (PI / 2 + gapHalf).toFloat()
            val span = (2 * PI - 2 * gapHalf).toFloat()
            for (i in 0 until steps) {
                val a0 = start + span * i / steps
                val a1 = start + span * (i + 1) / steps
                val d0 = V3(cos(a0), sin(a0), 0f)
                val d1 = V3(cos(a1), sin(a1), 0f)
                val w = COIL_RING_W / 2
                // outer and inner surface of the band
                b.quad(d0 * (r + 0.006f) + V3(0f, 0f, z - w), d1 * (r + 0.006f) + V3(0f, 0f, z - w), d1 * (r + 0.006f) + V3(0f, 0f, z + w), d0 * (r + 0.006f) + V3(0f, 0f, z + w), d0, d1, d1, d0)
                b.quad(d1 * (r - 0.006f) + V3(0f, 0f, z - w), d0 * (r - 0.006f) + V3(0f, 0f, z - w), d0 * (r - 0.006f) + V3(0f, 0f, z + w), d1 * (r - 0.006f) + V3(0f, 0f, z + w), -d1, -d0, -d0, -d1)
            }
        }
        return b.build(name)
    }

    /** UV sphere of radius 1 (the call ball). */
    fun sphere(name: String, rings: Int = 12, sectors: Int = 18): MeshResource {
        val b = MeshBuilder()
        for (i in 0 until rings) {
            val t0 = (PI * i / rings).toFloat()
            val t1 = (PI * (i + 1) / rings).toFloat()
            for (j in 0 until sectors) {
                val p0 = (2 * PI * j / sectors).toFloat()
                val p1 = (2 * PI * (j + 1) / sectors).toFloat()
                fun pt(t: Float, p: Float) = V3(sin(t) * cos(p), cos(t), sin(t) * sin(p))
                val a = pt(t0, p0)
                val bb = pt(t0, p1)
                val c = pt(t1, p1)
                val d = pt(t1, p0)
                if (abs(t0) < 1e-4f) b.quad(a, d, c, bb, a, d, c, bb) else b.quad(a, d, c, bb, a, d, c, bb)
            }
        }
        return b.build(name)
    }

    const val GANTRY_W = 2.10f
    const val GANTRY_H = 2.30f
    const val GANTRY_D = 1.60f
    const val GANTRY_CORNER = 0.35f
    const val CHAMFER = 0.05f
    const val STRIP_W = 0.04f
    const val COIL_R = 0.16f
    const val COIL_ROD = 0.012f
    const val COIL_RING_W = 0.02f
    const val COIL_OPEN_W = 0.20f
    const val COIL_Z0 = -0.12f
    const val COIL_Z1 = 0.18f
}

// ---------------------------------------------------------------------------------------------
// Programmatic textures.
// ---------------------------------------------------------------------------------------------

internal object Textures {
    /** The ceiling light box: a pale blue "sky" gradient, #CFE6F7 at one edge to #EAF4FB at the other. */
    fun skyPanel(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val a = intArrayOf(0xCF, 0xE6, 0xF7)
        val b = intArrayOf(0xEA, 0xF4, 0xFB)
        for (y in 0 until size) {
            val t = y / (size - 1f)
            val r = (a[0] + (b[0] - a[0]) * t).toInt()
            val g = (a[1] + (b[1] - a[1]) * t).toInt()
            val bl = (a[2] + (b[2] - a[2]) * t).toInt()
            for (x in 0 until size) {
                // a few soft cloud blobs so it reads as a sky, not a flat panel
                val cx = x / (size - 1f)
                val cloud = (0.5f + 0.5f * sin(cx * 9f + t * 4f) * sin(t * 7f - cx * 3f)).coerceIn(0f, 1f)
                val k = 0.06f * cloud
                val rr = (r + (255 - r) * k).toInt().coerceIn(0, 255)
                val gg = (g + (255 - g) * k).toInt().coerceIn(0, 255)
                val bb = (bl + (255 - bl) * k).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** The asymmetric test card ("F" and an up arrow) for the texture-orientation check (taste D10, `testtex 1`). */
    fun orientationTest(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(255, 241, 214))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(23, 50, 77)
            textSize = size * 0.55f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("F↑", size / 2f, size * 0.68f, paint)
        return bitmap
    }
}
