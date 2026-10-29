package io.embodit.mrirehearsal.scene

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.mrirehearsal.domain.DragLine
import io.embodit.mrirehearsal.domain.Occlusion
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.ScanFrame
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.TourStop
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.toRad
import io.embodit.mrirehearsal.platform.ControllerInput
import io.embodit.mrirehearsal.platform.HeadPose
import io.embodit.mrirehearsal.platform.HeadTracker
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.ui.MriViewModel
import io.embodit.mrirehearsal.ui.PanelSizes
import io.embodit.mrirehearsal.ui.SlideCommand
import io.embodit.mrirehearsal.ui.theme.MriColors
import kotlin.math.cos
import kotlin.math.sin

/** Ids of the pre-built attachment panels (declared once in MriScreen, with fixed sizes). */
object PanelIds {
    const val CARD = "card"
    const val CARD_TALL = "cardTall"
    const val CAPTION = "caption"
    const val TAG = "tag"
    val ALL = listOf(CARD, CARD_TALL, CAPTION, TAG)
}

/** The one per-frame system of the app (registered while the Stage screen is composed). */
class MriFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context.deltaTime)
    }
}

object ActiveScene {
    @Volatile var scene: MriScene? = null
}

/**
 * The scan room in 3D (design `entities3d`, `ecs.systems`): room, gantry with its bore, table, head coil, call ball,
 * technologist's window, tour highlight, and the holders of the attachment panels. Everything is written from
 * [onFrame] (the ECS system) and never goes through Compose; the ViewModel gets events and a reading ten times a
 * second (design `ecs.rule`).
 *
 * Layout: the [ScanFrame] taken at T1 / T10 (H0 = the head, b = towards the feet). The room group (room, gantry,
 * window) sits at H0 + s·b in that frame and is laid out for s = 0 (parked); the user group (table, pillow, coil,
 * call ball) stays at H0. Local axes of both groups: +Z = b, +Y = up, +X = up × b.
 *
 * Resources are built from a work queue paced by the frame rate (factory finding: a dozen meshes in one frame is a
 * start-up ANR on the idle emulator): two per frame when frames are fast, one a second when they are slow.
 */
class MriScene(private val vm: MriViewModel, private val head: HeadTracker, private val controller: ControllerInput) {
    /** Compose composes a panel's content only while this says so (a panel drawn while parked stays broken). */
    val panelShown = mutableStateMapOf<String, Boolean>()

    private var root: Entity? = null
    private var attachments: SpatialViewAttachments? = null
    private val shared = SharedResources()
    private var attached = false
    private var paused = false
    private val meshWork = ArrayDeque<Pair<String, () -> Unit>>()
    private var lastMeshMs = 0L
    private var buildStartMs = 0L
    private var builtReported = false

    // --- groups and entities
    private var roomGroup: Entity? = null
    private var userGroup: Entity? = null
    private var gantryGroup: Entity? = null
    private var light: Entity? = null
    private var unitCube: MeshResource? = null
    private var unitQuad: MeshResource? = null
    private var roomParts = ArrayList<Pair<Entity, String>>()
    private var gantryMeshes = ArrayList<Entity>()
    private var builtBoreCm = -1
    private var stripMaterial: UnlitMaterial? = null
    private var plinth: Entity? = null
    private var ceilingPanel: ModelEntity? = null
    private var ceilingMaterial: UnlitMaterial? = null
    private var testMaterial: UnlitMaterial? = null
    private var testQuad: ModelEntity? = null
    private var windowGlass: ModelEntity? = null
    private var windowWarm: ModelEntity? = null
    private var tableTop: ModelEntity? = null
    private var tableBase: ModelEntity? = null
    private var pillow: ModelEntity? = null
    private var coil: ModelEntity? = null
    private var coilMaterial: PhysicallyBasedMaterial? = null
    private var coilHighlight: UnlitMaterial? = null
    private var ball: ModelEntity? = null
    private var hose: ModelEntity? = null
    private var shell: ModelEntity? = null
    private var leader: ModelEntity? = null

    /** Entities the system pointer can hit, with the tour stop they stand for (null: the room, for the call hold). */
    private val targets = HashMap<Long, TourStop?>()

    /** The room's hit boxes (walls, floor, ceiling) for the pinch-held call, laid out with the room. */
    private val roomHits = HashMap<String, Entity>()

    /**
     * The tour stops' hit boxes. They exist only in S03 and are stowed otherwise: in the bore the head is inside the
     * gantry's box and the coil's, and the controller sits on the call ball — a ray starting inside a collider
     * would take every click away from the caption strip.
     */
    private val tourHits = HashMap<TourStop, Entity>()

    // --- panel slots
    private inner class Slot(val id: String, val holder: Entity) {
        var panel: Entity? = null
        var shown = false
        var shownFrame = 0L
        var lastPos: V3? = null
        var lastRot: Q4? = null
        var lastScale = 0f

        /**
         * The holder is never disabled: a panel entity parented under a disabled holder never appears (Spatial Ruler
         * finding). Hidden = stowed 40 m below the floor at a thousandth of its size.
         */
        fun place(position: V3, rotation: Q4, scale: Float) {
            // write the holder only when it moved: a panel rewritten every frame lost presses to a collider behind
            // it now and then (QA q2-1); the guard in updateHits is the actual fix, this keeps the engine quiet
            val lp = lastPos
            val lr = lastRot
            if (lp == null || lr == null || (lp - position).length > 1e-4f || kotlin.math.abs(lr.dot(rotation)) < 0.999999f || lastScale != scale) {
                holder.setLocal(position = position, rotation = rotation, scale = V3(scale, scale, scale))
                lastPos = position
                lastRot = rotation
                lastScale = scale
            }
            if (!shown) {
                shown = true
                shownFrame = frame
            }
            val visible = frame - shownFrame >= SHOW_FRAMES
            if (panelShown[id] != visible) panelShown[id] = visible
        }

        fun hide() {
            if (shown || frame == 0L) {
                shown = false
                panelShown[id] = false
                holder.setLocal(position = STOW, scale = V3(PARK_SCALE, PARK_SCALE, PARK_SCALE))
                lastPos = null
                lastRot = null
            }
        }

        /** The panel's rectangle in the world while shown, for the line-of-sight guard. */
        fun rect(): Occlusion.PanelRect? {
            if (!shown) return null
            val p = lastPos ?: return null
            val r = lastRot ?: return null
            val size = sizeDp(id)
            return Occlusion.PanelRect(p, r, size.first * DP_M * lastScale, size.second * DP_M * lastScale)
        }
    }

    private fun sizeDp(id: String): Pair<Float, Float> =
        when (id) {
            PanelIds.CARD -> PanelSizes.CARD
            PanelIds.CARD_TALL -> PanelSizes.CARD_TALL
            PanelIds.CAPTION -> PanelSizes.CAPTION
            else -> PanelSizes.TAG
        }.let { it.width.toFloat() to it.height.toFloat() }

    private val slots = LinkedHashMap<String, Slot>()

    // --- per-frame state
    private var frame = 0L
    private var scanFrame: ScanFrame? = null
    private var frameEpoch = -1
    private var layoutPosture: Posture? = null
    private var slide = Slide()
    private var appliedSlide = -1
    private var arrivedReported = true
    private var fadeIn = 1f
    private var roomOpacity = 0f
    private var trigDownSinceMs = -1L
    private var trigFired = false
    private var lastPostureMs = 0L
    private var lastStillMs = 0L
    private var lastLost = false
    private var headReported = false
    private var cardEpoch = -1
    private var cardPos: V3? = null

    // --- D21: the main card's grab bar (system pointer drag: pinch or controller ray + trigger)
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var grabBar: ModelEntity? = null
    private var grabHit: Entity? = null
    private var grabVisible = false

    private class CardDrag(val press: V3) {
        var dir: V3? = null
        var depth = 0f
        var distance = 0f
        var totalPx = V3.ZERO
        var hand = V3.ZERO
        var prevHand = V3.ZERO
        var begun = false
        var moves = 0
    }

    @Volatile private var cardDrag: CardDrag? = null
    private var cardScale = CARD_SCALE
    private var cardVisibleLast = false
    private var pauseCardPlaced = false
    private var tagPos: V3? = null
    private var tagShownLast = false
    private var lastStripWarm: Boolean? = null
    private var lastCoilLit: Boolean? = null
    private var lastHighlight: TourStop? = null
    private var lastTestTex: Boolean? = null
    private var tourHitsOn = true
    private var lastBehind = false

    /** The entity the system pointer hit last (set by the gesture filter), for a tap on a tour point. */
    @Volatile var lastHit: Entity? = null

    // ------------------------------------------------------------------------------------------ setup

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached) detach()
        val t0 = SystemClock.elapsedRealtime()
        buildStartMs = t0
        this.attachments = attachments
        val root = Entity()
        runCatching { root.setName("mriRoot") }
        content.addEntity(root)
        this.content = content
        contentProbe = Entity().also { runCatching { it.setName("contentProbe") }; content.addEntity(it) }
        // An entity added to the SpatialView is not at the Stage origin; move the root once so that its local space
        // equals Stage space (Spatial Ruler's finding, 0.256 m off in z).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        this.root = root
        roomGroup = Entity().also { root.addChild(it); it.setOpacity(0f) }
        userGroup = Entity().also { root.addChild(it); it.setOpacity(0f) }
        gantryGroup = Entity().also { roomGroup?.addChild(it) }
        light = Entity().also {
            it.components.set(DirectionalLightComponent(Color4(1f, 0.97f, 0.92f, 1f), LIGHT_LUX, false))
            root.addChild(it)
        }
        queueBuild()
        for (id in PanelIds.ALL) {
            val holder = Entity()
            runCatching { holder.setName("holder:$id") }
            root.addChild(holder)
            slots[id] = Slot(id, holder).also { it.hide() }
        }
        bind(attachments)
        head.start()
        controller.start()
        ActiveScene.scene = this
        attached = true
        MriLog.i("SCENE attached setup_ms=${SystemClock.elapsedRealtime() - t0} queued=${meshWork.size}")
    }

    /** Parents freshly composed panel entities under their holders; true when none is missing. */
    fun bind(attachments: SpatialViewAttachments): Boolean {
        var complete = true
        for (slot in slots.values) {
            if (slot.panel != null) continue
            val panel = attachments.entity(slot.id)
            if (panel == null) {
                complete = false
                continue
            }
            slot.holder.addChild(panel)
            runCatching { panel.setName("panel:${slot.id}") }
            slot.panel = panel
            MriLog.i("PANEL bound id=${slot.id}")
        }
        return complete
    }

    fun detach() {
        ActiveScene.scene = null
        attached = false
        meshWork.clear()
        head.stop()
        controller.stop()
        for (slot in slots.values) {
            runCatching { slot.holder.removeAllChildren() }
            slot.panel = null
        }
        slots.clear()
        panelShown.clear()
        targets.clear()
        roomHits.clear()
        tourHits.clear()
        roomParts.clear()
        gantryMeshes.clear()
        runCatching { root?.destroy(true) }
        root = null
        runCatching { contentProbe?.destroy(true) }
        contentProbe = null
        content = null
        grabBar = null
        grabHit = null
        cardDrag = null
        shared.close()
        MriLog.i("SCENE detached")
    }

    /** T13: the app went to the background — trackers off, panels stowed. */
    fun onBackground() {
        paused = true
        head.stop()
        controller.stop()
        for (s in slots.values) s.hide()
        MriLog.i("SCENE paused")
    }

    fun onForeground() {
        if (!attached) return
        head.start()
        controller.start()
        paused = false
        cardEpoch = -1
        MriLog.i("SCENE resumed")
    }

    /**
     * A collider the system pointer can hit: an unscaled entity of its own with a box of the real [size], so the
     * collider never depends on how the visible mesh is scaled.
     */
    private fun hitBox(parent: Entity?, size: V3, stop: TourStop?, name: String): Entity {
        val e = Entity()
        runCatching { e.setName("hit:$name") }
        e.addBoxCollider(size)
        runCatching { e.components.set(InteractableComponent()) }
        parent?.addChild(e)
        targets[e.id] = stop
        return e
    }

    /** Gesture filter: records the hit and accepts the room's and the tour's colliders. */
    fun accept(e: Entity): Boolean {
        lastHit = e
        return targets.containsKey(e.id)
    }

    /** `hits 0|1`: room hit boxes on / off (the pinch-held call needs them; QA checks they never take panel clicks). */
    fun setRoomHits(on: Boolean) {
        roomHitsOn = on
        MriLog.i("HITS room=$on")
    }

    @Volatile private var roomHitsOn = true

    /** `guard 0`: QA control only — the room boxes stay out in every state with no line-of-sight check (build 13 behaviour). */
    @Volatile var guardOn = true

    /** Local position (in the room group) and size of each room hit box, from layout(). */
    private val roomHitLocal = HashMap<String, Pair<V3, V3>>()

    /** The hole currently cut into each room hit box ([Occlusion.hole]), null = whole box. */
    private val roomHoles = HashMap<String, FloatArray?>()

    /** Which hit boxes are out right now (logged on change only). */
    private var hitsLog = ""

    fun hitName(e: Entity?): String = runCatching { e?.getName() }.getOrNull() ?: "none"

    /** A short pinch / trigger click on a 3D target (T4: jump to that tour point). */
    fun onTap(e: Entity?) {
        val stop = e?.let { targets[it.id] } ?: return
        vm.tourJump(stop)
    }

    /** One work item per resource creation, in the order the room needs them. */
    private fun queueBuild() {
        meshWork += "shared" to {
            unitCube = shared.keep(Meshes.unitCube("unitCube"))
            unitQuad = shared.keep(Meshes.quad(1f, 1f, "unitQuad"))
        }
        meshWork += "room" to {
            val cube = unitCube!!
            val wall = unlit(shared, MriColors.Wall.shade(0.86f))
            val wallSide = unlit(shared, MriColors.Wall.shade(0.78f))
            val floor = unlit(shared, MriColors.Floor.shade(0.82f))
            val ceiling = unlit(shared, MriColors.Wall.shade(0.94f))
            for ((name, mat) in listOf("floor" to floor, "ceiling" to ceiling, "wallHead" to wall, "wallFeet" to wall, "wallL" to wallSide, "wallR" to wallSide)) {
                val e = ModelEntity(cube, mat)
                runCatching { e.setName(name) }
                roomGroup?.addChild(e)
                roomParts += e to name
                val size = if (name == "floor" || name == "ceiling") V3(ROOM_W, 0.05f, ROOM_L) else if (name == "wallL" || name == "wallR") V3(0.05f, ROOM_H, ROOM_L) else V3(ROOM_W, ROOM_H, 0.05f)
                roomHits[name] = hitBox(roomGroup, size, null, name)
            }
            for (stop in TourStop.entries) {
                val (_, size) = stopSize(stop)
                tourHits[stop] = hitBox(root, size, stop, stop.name).also { it.setLocal(position = STOW) }
            }
            layoutPosture = null
        }
        meshWork += "ceilingPanel" to {
            val tex = shared.keep(TextureResource.create(Textures.skyPanel(256)))
            ceilingMaterial = unlit(shared, Color.White, texture = tex)
            ceilingPanel = ModelEntity(unitQuad!!, ceilingMaterial!!).also { roomGroup?.addChild(it) }
            layoutPosture = null
        }
        queueBore()
        meshWork += "gantryParts" to {
            val g = gantryGroup!!
            val cube = unitCube!!
            ModelEntity(cube, pbr(shared, MriColors.Speaker, 0.6f)).also {
                it.setLocal(position = V3(0f, Meshes.GANTRY_H / 2 - 0.14f, Meshes.GANTRY_D / 2 + 0.06f), scale = V3(0.12f, 0.12f, 0.12f))
                g.addChild(it)
            }
            ModelEntity(cube, unlit(shared, MriColors.Brand)).also {
                it.setLocal(position = V3(-0.72f, 0.86f, Meshes.GANTRY_D / 2 + 0.006f), scale = V3(0.22f, 0.07f, 0.01f))
                g.addChild(it)
            }
            plinth = ModelEntity(cube, pbr(shared, MriColors.Gantry.shade(0.9f), 0.5f)).also {
                it.setLocal(position = V3(0f, -Meshes.GANTRY_H / 2 - 0.025f, 0f), scale = V3(1.8f, 0.05f, 1.5f))
                g.addChild(it)
            }
            layoutPosture = null
        }
        meshWork += "table" to {
            val cube = unitCube!!
            val ug = userGroup!!
            val white = pbr(shared, MriColors.Table, 0.5f)
            tableTop = ModelEntity(cube, white).also { ug.addChild(it) }
            tableBase = ModelEntity(cube, pbr(shared, MriColors.Table.shade(0.85f), 0.6f)).also { ug.addChild(it) }
            pillow = ModelEntity(cube, pbr(shared, MriColors.Pillow, 0.9f)).also { ug.addChild(it) }
            layoutPosture = null
        }
        meshWork += "coil" to {
            coilMaterial = pbr(shared, MriColors.Coil, 0.45f, twoSided = true)
            coilHighlight = unlit(shared, MriColors.TourHighlight, twoSided = true)
            coil = ModelEntity(shared.keep(Meshes.headCoil("headCoil")), coilMaterial!!).also { userGroup?.addChild(it) }
            lastCoilLit = null
            layoutPosture = null
        }
        meshWork += "ball" to {
            ball = ModelEntity(shared.keep(Meshes.sphere("ball")), pbr(shared, MriColors.CallBall, 0.85f)).also { userGroup?.addChild(it) }
            hose = ModelEntity(unitCube!!, pbr(shared, MriColors.CallBall.shade(0.8f), 0.85f)).also { userGroup?.addChild(it) }
            layoutPosture = null
        }
        meshWork += "window" to {
            val quad = unitQuad!!
            windowWarm = ModelEntity(quad, unlit(shared, MriColors.WindowWarm)).also { roomGroup?.addChild(it) }
            windowGlass = ModelEntity(quad, unlit(shared, MriColors.WindowGlass, BlendingMode.TRANSPARENT, opacity = 0.8f, twoSided = true)).also { roomGroup?.addChild(it) }
            layoutPosture = null
        }
        meshWork += "tour" to {
            val cube = unitCube!!
            shell = ModelEntity(cube, unlit(shared, MriColors.TourHighlight, BlendingMode.TRANSPARENT, opacity = 0.28f, depthWrite = false)).also { root?.addChild(it); it.setVisible(false) }
            leader = ModelEntity(cube, unlit(shared, MriColors.TourHighlight)).also { root?.addChild(it); it.setVisible(false) }
            testMaterial = unlit(shared, Color.White, texture = shared.keep(TextureResource.create(Textures.orientationTest(128))), twoSided = false)
            testQuad = ModelEntity(unitQuad!!, testMaterial!!).also { root?.addChild(it); it.setVisible(false) }
            // D21 grab bar under the main card: a pale pill, its own unscaled hit box a little larger
            grabBar = ModelEntity(cube, unlit(shared, Color.White, BlendingMode.TRANSPARENT, opacity = 0.85f)).also { root?.addChild(it); it.setLocal(position = STOW) }
            grabHit = Entity().also {
                runCatching { it.setName("hit:grab") }
                it.addBoxCollider(V3(GRAB_HIT_W, GRAB_HIT_H, 0.03f))
                runCatching { it.components.set(InteractableComponent()) }
                root?.addChild(it)
                it.setLocal(position = STOW)
            }
        }
    }

    /** Gantry body, bore wall, light strips and the front band for the current bore diameter (T9 rebuilds them). */
    private fun queueBore() {
        val cm = vm.state.value.settings.boreCm
        builtBoreCm = cm
        val r = cm / 200f
        val made = ArrayList<Entity>()
        meshWork += "gantryBody" to {
            made += ModelEntity(shared.keep(Meshes.gantryBody(r, "gantryBody$cm")), pbr(shared, MriColors.Gantry, 0.35f))
        }
        meshWork += "bore" to {
            made += ModelEntity(shared.keep(Meshes.boreInner(r, "bore$cm")), unlit(shared, MriColors.BoreInner.shade(0.82f)))
        }
        meshWork += "strips" to {
            val mat = stripMaterial ?: unlit(shared, MriColors.BoreLight).also { stripMaterial = it }
            made += ModelEntity(shared.keep(Meshes.boreStrips(r, "strips$cm")), mat)
            lastStripWarm = null
        }
        meshWork += "band" to {
            made += ModelEntity(shared.keep(Meshes.frontBand(r, "band$cm")), unlit(shared, MriColors.GantryBand))
            // swap in the new set at once
            val g = gantryGroup
            for (e in gantryMeshes) runCatching { g?.removeChild(e) ; e.destroy(true) }
            gantryMeshes = ArrayList(made)
            for (e in made) g?.addChild(e)
            MriLog.i("SCENE bore built ${cm}cm")
        }
    }

    /** Two resources per frame while frames are fast (headset), one a second while they are slow (idle emulator). */
    private fun runMeshWork(dt: Float, now: Long) {
        if (meshWork.isEmpty()) return
        val n = if (dt < FAST_FRAME_SEC) 2 else if (now - lastMeshMs >= SLOW_MESH_GAP_MS) 1 else 0
        repeat(n) {
            val (name, work) = meshWork.removeFirstOrNull() ?: return
            val t0 = SystemClock.elapsedRealtime()
            runCatching { work() }.onFailure { MriLog.e("SCENE build '$name' failed: $it", it) }
            lastMeshMs = SystemClock.elapsedRealtime()
            MriLog.i("SCENE built $name ms=${lastMeshMs - t0} left=${meshWork.size}")
        }
        if (meshWork.isEmpty()) {
            MriLog.i("SCENE build done total_ms=${now - buildStartMs}")
            if (!builtReported) {
                builtReported = true
                vm.onBuilt()
            }
        }
    }

    // ------------------------------------------------------------------------------------------- frame

    fun onFrame(deltaTime: Float) {
        if (!attached) return
        val dt = if (deltaTime.isFinite() && deltaTime > 0f) deltaTime.coerceAtMost(0.1f) else 0.016f
        frame++
        val now = SystemClock.elapsedRealtime()
        runMeshWork(if (deltaTime.isFinite() && deltaTime > 0f) deltaTime else 1f, now)
        attachments?.let { if (slots.values.any { s -> s.panel == null }) bind(it) }
        if (paused) return
        val pose = head.pose() ?: return
        if (!headReported) {
            headReported = true
            vm.onHeadFound()
        }
        val state = vm.state.value

        // the call ball on the controller: trigger held 0.4 s (T5)
        if (controller.triggerDown) {
            if (trigDownSinceMs < 0) trigDownSinceMs = now
            if (!trigFired && now - trigDownSinceMs >= HOLD_MS) {
                trigFired = true
                MriLog.i("CALL trigger held")
                vm.onCallHold()
            }
        } else {
            trigDownSinceMs = -1L
            trigFired = false
        }

        // posture (T2) every 0.5 s in S01: F·worldUp
        if (state.screen == Screen.S01 && now - lastPostureMs >= POSTURE_MS) {
            lastPostureMs = now
            vm.onPostureReading(pose.forward.y)
        }

        // the room's frame: T1, T10, posture changes in S01 (D9: from the current head pose)
        if (state.frameEpoch != frameEpoch || scanFrame == null) {
            frameEpoch = state.frameEpoch
            scanFrame = ScanFrame.from(pose.position, pose.rotation, state.posture)
            MriLog.i("SCENE frame h0=${scanFrame?.h0} b=${scanFrame?.b} posture=${state.posture}")
        }
        val f = scanFrame ?: return
        if (layoutPosture != f.posture) layout(f)
        if (state.settings.boreCm != builtBoreCm) queueBore()

        // world offset s
        val cmd = vm.slideCommand
        if (cmd.seq != appliedSlide) {
            appliedSlide = cmd.seq
            val target = if (cmd.mode == SlideCommand.Mode.CALL) Slide.callTarget(slide.s) else cmd.target
            slide = slide.toward(target, cmd.speed)
            if (cmd.snap) {
                if (state.settings.skipSlide && kotlin.math.abs(slide.s - target) > 0.05f) fadeIn = 0f
                slide = slide.snap()
            }
            arrivedReported = false
            MriLog.i("SLIDE cmd=${cmd.seq} ${cmd.mode} target=$target speed=${cmd.speed} snap=${cmd.snap}")
        }
        if (!state.frozen && !state.lost) slide = slide.step(dt * vm.speed)
        if (slide.arrived && !arrivedReported) {
            arrivedReported = true
            vm.onArrived(appliedSlide, slide.s)
        }
        vm.worldOffset = slide.s
        fadeIn = (fadeIn + dt / FADE_SEC).coerceAtMost(1f)

        // room fade-in after start (S00: black, then the room)
        val targetOpacity = if (now - buildStartMs < FIRST_FADE_DELAY_MS) 0f else 1f
        roomOpacity = (roomOpacity + dt / FADE_SEC * if (targetOpacity > roomOpacity) 1f else -1f).coerceIn(0f, 1f)
        val op = roomOpacity * fadeIn
        roomGroup?.setOpacity(op)
        userGroup?.setOpacity(op)

        val rot = f.rotation
        roomGroup?.setLocal(position = f.h0 + f.b * slide.s, rotation = rot)
        userGroup?.setLocal(position = f.h0, rotation = rot)
        // light: from above and from the feet side, towards the head (lights the gantry's front face and the table)
        val lightDir = (V3(0f, -1f, 0f) - f.b * 0.6f).normalized()
        light?.setLocal(position = f.h0, rotation = Q4.facing(-lightDir, f.b))

        // tracking lost (S14) in S04–S10
        if (state.screen.watchesTracking) {
            val lost = head.lost(now)
            if (lost != lastLost) {
                lastLost = lost
                vm.onLost(lost)
            }
        } else if (lastLost) {
            lastLost = false
        }

        // stillness judge in S06, a reading for the panels ten times a second
        if (state.screen == Screen.S06 && !state.lost && vm.engine.active) {
            val reading = vm.engine.sample(now, state.clock.levelSec, pose.position, pose.rotation)
            if (now - lastStillMs >= READING_MS) {
                lastStillMs = now
                vm.onStill(reading)
            }
        }

        // light strips turn warm while called
        val warm = state.screen == Screen.S09
        if (warm != lastStripWarm) {
            lastStripWarm = warm
            stripMaterial?.setBaseColor((if (warm) MriColors.BoreLightWarm else MriColors.BoreLight).color4())
        }
        coil?.setVisible(state.settings.headCoil)

        placePanels(state, pose, f)
        placeTour(state, pose, f)
        placeTestQuad(state, pose, f)
        updateHits(state, pose, f)
    }

    /**
     * QA q2-1: UI presses always win over 3D colliders. Every frame each room / tour hit box is either placed or
     * stowed: it is out when it has no job in this state (the room's boxes only serve the pinch-held call, S03–S07 and
     * a lost S10; the tour's only S03) or when a line of sight through any shown panel runs into it (Occlusion).
     * With nothing behind a panel, the panel takes every press on it (Spatial Ruler's measurement).
     */
    private fun updateHits(state: UiState, pose: HeadPose, f: ScanFrame) {
        val panels = slots.values.mapNotNull { it.rect() }
        val callable = state.screen == Screen.S03 || state.screen.inRun || (state.lost && state.screen == Screen.S10)
        val roomOn = roomHitsOn && (callable || !guardOn)
        val out = StringBuilder()
        val roomOrigin = f.h0 + f.b * slide.s
        for ((name, e) in roomHits) {
            val (local, size) = roomHitLocal[name] ?: continue
            val center = roomOrigin + f.rotation.rotate(local)
            val box = Occlusion.Box(center, f.rotation, size)
            e.setLocal(position = if (roomOn) local else STOW)
            if (!roomOn) {
                out.append(name).append(' ')
                continue
            }
            // a wall stays (the pinch-held call can aim anywhere) but gets a hole where a panel is in front of it
            val hole = if (guardOn) Occlusion.hole(pose.position, panels, box) else null
            val last = roomHoles[name]
            val changed =
                if (hole == null || last == null) (hole == null) != (last == null) || !roomHoles.containsKey(name)
                else (0..3).any { kotlin.math.abs(hole[it] - last[it]) > 0.01f }
            if (changed) {
                roomHoles[name] = hole
                e.setBoxColliders(Occlusion.cut(size, hole))
            }
            if (hole != null) out.append(name).append("(hole) ")
        }
        val touring = state.screen == Screen.S03
        for ((s, e) in tourHits) {
            val (center, _) = stopBounds(s, f)
            val box = Occlusion.Box(center, f.rotation, stopSize(s).second)
            val keep = touring && (f.posture == Posture.LIE || s != TourStop.TABLE) && panels.none { Occlusion.behind(pose.position, it, box) }
            e.setLocal(position = if (keep) center else STOW, rotation = f.rotation)
            if (!keep && touring) out.append(s.name).append(' ')
        }
        val line = out.toString().trim()
        if (line != hitsLog) {
            hitsLog = line
            MriLog.i("HITS out=[$line] screen=${state.screen} panels=${panels.size}")
        }
    }

    /** Positions that depend only on the frame's posture (room heights, table, coil axis). */
    private fun layout(f: ScanFrame) {
        layoutPosture = f.posture
        val lie = f.posture == Posture.LIE
        val floorY = -f.floorBelow
        val ceilY = floorY + ROOM_H
        for ((e, name) in roomParts) {
            val (pos, size) =
                when (name) {
                    "floor" -> V3(0f, floorY - 0.025f, 0f) to V3(ROOM_W, 0.05f, ROOM_L)
                    "ceiling" -> V3(0f, ceilY + 0.025f, 0f) to V3(ROOM_W, 0.05f, ROOM_L)
                    "wallHead" -> V3(0f, floorY + ROOM_H / 2, -ROOM_L / 2 - 0.025f) to V3(ROOM_W, ROOM_H, 0.05f)
                    "wallFeet" -> V3(0f, floorY + ROOM_H / 2, ROOM_L / 2 + 0.025f) to V3(ROOM_W, ROOM_H, 0.05f)
                    "wallL" -> V3(-ROOM_W / 2 - 0.025f, floorY + ROOM_H / 2, 0f) to V3(0.05f, ROOM_H, ROOM_L)
                    else -> V3(ROOM_W / 2 + 0.025f, floorY + ROOM_H / 2, 0f) to V3(0.05f, ROOM_H, ROOM_L)
                }
            e.setLocal(position = pos, scale = size)
            roomHitLocal[name] = pos to size
        }
        // ceiling light box at H0 + 1.5 b, facing down (R2-4: not behind the main card)
        ceilingPanel?.setLocal(position = V3(0f, ceilY - 0.006f, 1.5f), rotation = Q4.facing(V3(0f, -1f, 0f), V3(0f, 0f, -1f)), scale = V3(1.2f, 1.2f, 1f))
        // gantry parked: centre 1.8 m towards the head; the bore centre at eye height
        gantryGroup?.setLocal(position = V3(0f, 0f, -Slide.MAX))
        plinth?.setVisible(!lie)
        // technologist's window on the feet wall, 1.5 m above the floor
        val windowY = floorY + 1.5f
        windowWarm?.setLocal(position = V3(0f, windowY, ROOM_L / 2 - 0.004f), rotation = Q4.facing(V3(0f, 0f, -1f), V3.UP), scale = V3(1.5f, 0.9f, 1f))
        windowGlass?.setLocal(position = V3(0f, windowY, ROOM_L / 2 - 0.02f), rotation = Q4.facing(V3(0f, 0f, -1f), V3.UP), scale = V3(1.6f, 1.0f, 1f))
        // user group: lateral sign of the right hand in local X (+X = up × b)
        val rs = if (lie) -1f else 1f
        tableTop?.setLocal(position = V3(0f, TABLE_TOP - 0.04f, 0.6f), scale = V3(0.55f, 0.08f, 2.10f))
        val baseH = (TABLE_TOP - 0.08f) - floorY
        tableBase?.setLocal(position = V3(0f, floorY + baseH / 2, 1.3f), scale = V3(0.30f, baseH, 0.60f))
        pillow?.setLocal(position = V3(0f, TABLE_TOP + 0.03f, 0f), scale = V3(0.30f, 0.06f, 0.25f))
        for (e in listOfNotNull(tableTop, tableBase, pillow, hose)) e.setVisible(lie)
        if (lie) {
            ball?.setLocal(position = V3(rs * 0.22f, TABLE_TOP + BALL_R, 0.55f), scale = V3(BALL_R, BALL_R, BALL_R))
            hose?.setLocal(position = V3(rs * 0.22f, TABLE_TOP + 0.008f, 0.55f + 0.25f + BALL_R), scale = V3(0.012f, 0.012f, 0.5f))
            coil?.setLocal(position = V3(0f, -0.06f, 0f), rotation = Q4.IDENTITY)
        } else {
            ball?.setLocal(position = V3(rs * 0.18f, -0.55f, -0.25f), scale = V3(BALL_R, BALL_R, BALL_R))
            coil?.setLocal(position = V3(0f, 0f, 0.06f), rotation = Q4.axisAngle(V3.RIGHT, -90f))
        }
        MriLog.i("SCENE layout posture=${f.posture} floorY=$floorY")
    }

    // ------------------------------------------------------------------------------------------ panels

    /** The panels' "up": lying = towards the top of the head (−b), sitting = world up (R1-1). */
    private fun panelUp(f: ScanFrame): V3 = if (f.posture == Posture.LIE) -f.b else V3.UP

    /** In front of the head at [distance], [downDeg] below the line of sight ("below" = towards the feet lying). */
    private fun inFront(pose: HeadPose, f: ScanFrame, distance: Float, downDeg: Float): V3 {
        val fwd = pose.forward.normalized()
        val up = panelUp(f)
        var u = up - fwd * up.dot(fwd)
        if (u.length < 0.15f) u = V3.UP - fwd * fwd.y
        u = u.normalized()
        val dir = (fwd * cos(downDeg.toRad()) - u * sin(downDeg.toRad())).normalized()
        return pose.position + dir * distance
    }

    private fun facingHead(at: V3, pose: HeadPose, f: ScanFrame): Q4 = Q4.facing(pose.position - at, panelUp(f))

    private fun placePanels(state: UiState, pose: HeadPose, f: ScanFrame) {
        val screen = state.screen
        // main card: one at a time (S12 / S13 replace its content, R1-6); the pause card only once the world is out
        val cardId: String? =
            when (screen) {
                Screen.S00, Screen.S01, Screen.S02, Screen.S11, Screen.S13 -> PanelIds.CARD
                Screen.S12 -> PanelIds.CARD_TALL
                Screen.S09 -> if (state.pauseCardReady) PanelIds.CARD else null
                else -> null
            }
        if (cardId != null) {
            val pause = screen == Screen.S09
            if (!cardVisibleLast || state.cardEpoch != cardEpoch || cardPos == null || pause != pauseCardPlaced) {
                cardEpoch = state.cardEpoch
                pauseCardPlaced = pause
                // D9: every appearance in front of the current head pose
                cardPos = if (pause) inFront(pose, f, PAUSE_DISTANCE, 0f) else inFront(pose, f, CARD_DISTANCE, CARD_DOWN_DEG)
                cardScale = if (pause) PAUSE_SCALE else CARD_SCALE
            }
            val pos = cardPos!!
            val rot = facingHead(pos, pose, f)
            slots[cardId]?.place(pos, rot, cardScale)
            placeGrab(if (screen == Screen.S09) null else pos, rot, if (cardId == PanelIds.CARD_TALL) 440f else 400f)
            for (id in listOf(PanelIds.CARD, PanelIds.CARD_TALL)) if (id != cardId) slots[id]?.hide()
        } else {
            slots[PanelIds.CARD]?.hide()
            slots[PanelIds.CARD_TALL]?.hide()
            placeGrab(null, Q4.IDENTITY, 0f)
        }
        cardVisibleLast = cardId != null

        // tour tag at the main card's place (R2-9)
        if (screen == Screen.S03) {
            if (!tagShownLast || state.cardEpoch != cardEpoch || tagPos == null) {
                cardEpoch = state.cardEpoch
                tagPos = inFront(pose, f, CARD_DISTANCE, CARD_DOWN_DEG)
            }
            val pos = tagPos!!
            slots[PanelIds.TAG]?.place(pos, facingHead(pos, pose, f), TAG_SCALE)
        } else {
            slots[PanelIds.TAG]?.hide()
        }
        tagShownLast = screen == Screen.S03

        // caption strip: fixed with the user, never with the head (S05 onwards it stays on the bore's roof)
        val captionOn =
            when (screen) {
                Screen.S04, Screen.S05, Screen.S06, Screen.S07 -> true
                Screen.S10 -> !state.abandoning || state.lost
                Screen.S09 -> !state.pauseCardReady
                else -> false
            }
        if (captionOn) {
            val (pos, scale) = captionPlace(f)
            slots[PanelIds.CAPTION]?.place(pos, facingHead(pos, pose, f), scale)
        } else {
            slots[PanelIds.CAPTION]?.hide()
        }
    }

    /** The grab bar 2.5 cm under the card's bottom edge, turned with the card; [cardPos] null = stowed. */
    private fun placeGrab(cardPos: V3?, rot: Q4, heightDp: Float) {
        val bar = grabBar ?: return
        val hit = grabHit ?: return
        if (cardPos == null) {
            if (grabVisible) {
                bar.setLocal(position = STOW)
                hit.setLocal(position = STOW)
                grabVisible = false
            }
            return
        }
        val half = heightDp * DP_M * cardScale / 2f
        val at = cardPos - rot.rotate(V3.UP) * (half + GRAB_GAP_M)
        bar.setLocal(position = at, rotation = rot, scale = V3(GRAB_W, GRAB_H, 0.004f))
        hit.setLocal(position = at, rotation = rot)
        grabVisible = true
    }

    fun isGrab(e: Entity): Boolean = grabHit?.let { it.id == e.id } == true

    /**
     * D21: the system pointer drags the grab bar (pinch, or controller ray + trigger). The card follows the pointing
     * line at the distance it had when grabbed, keeps facing the user every frame, and stays where it is released;
     * "Re-centre" still brings it back in front.
     */
    fun onCardDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val pose = head.last ?: return
        when (phase) {
            "start" -> {
                val at = cardPos ?: return
                val press = start?.let { toStage(it.x, it.y, it.z) } ?: at
                cardDrag = CardDrag(press)
                MriLog.i("DRAG card start press=$press card=$at")
            }
            "move" -> {
                val d = cardDrag ?: return
                val a = value.dragAmount
                d.moves++
                d.totalPx = V3(d.totalPx.x + a.x, d.totalPx.y + a.y, d.totalPx.z + a.z)
                d.hand = handDelta(d.totalPx) ?: d.hand
                if (!d.begun && d.hand.length >= DRAG_START_M) {
                    val at = cardPos ?: return
                    val to = at - pose.position
                    if (to.length < 1e-3f) return
                    d.begun = true
                    d.dir = to.normalized()
                    d.distance = to.length
                    val pitch = Math.toDegrees(kotlin.math.asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
                    d.depth = DragLine.equivalentDepth((d.press - pose.position).length, pitch)
                    d.prevHand = d.hand
                    MriLog.i("DRAG card begin dist=${"%.2f".format(d.distance)}")
                }
                if (d.begun) {
                    val dir = d.dir ?: return
                    val right = pose.rotation.rotate(V3.RIGHT)
                    val turned = DragLine.turn(dir, d.hand - d.prevHand, d.depth, right, pose.rotation.up, pose.forward)
                    d.prevHand = d.hand
                    d.dir = turned
                    cardPos = pose.position + turned * d.distance
                    if (d.moves % 10 == 1) MriLog.i("DRAG card move at=$cardPos")
                }
            }
            "end", "cancel" -> {
                val d = cardDrag ?: return
                cardDrag = null
                MriLog.i("DRAG card $phase moved=${d.begun} at=$cardPos")
            }
        }
    }

    private fun handDelta(px: V3): V3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler / dose-distance). */
    private fun toStage(x: Float, y: Float, z: Float): V3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    /** `dragcard dx dy` (metres, the view's right / up): moves the card as a drag would (QA without a pointer). */
    fun debugMoveCard(dx: Float, dy: Float) {
        val pose = head.last ?: return
        val at = cardPos ?: return
        val dist = (at - pose.position).length
        val moved = at + pose.rotation.rotate(V3.RIGHT) * dx + pose.rotation.up * dy
        cardPos = pose.position + (moved - pose.position).normalized() * dist
        MriLog.i("DRAG card debug at=$cardPos")
    }

    /**
     * Where the caption strip is. Lying: on the bore's roof once the front face has passed the head (s ≥ 1.0, R2-7),
     * radius − 0.015, [MriViewModel.captionOffset] towards the feet, scale 0.5; before that 0.6 m up in view, scale
     * 0.8. Sitting: on the bore's far rim, 0.80 m ahead, 0.19 up (design 0.24), scale 1.0 (R2-2) — visible through the bore all
     * the way.
     */
    private fun captionPlace(f: ScanFrame): Pair<V3, Float> {
        val r = vm.state.value.settings.boreRadius
        return if (f.posture == Posture.LIE) {
            if (slide.s >= Slide.FACE_AT_HEAD) f.at(vm.captionOffset, 0f, r - 0.015f) to CAPTION_SCALE_LIE else f.at(0.13f, 0f, 0.6f) to CAPTION_SCALE_PRE
        } else {
            // 0.19 up, not the design's 0.24: at scale 1.0 the strip is 0.336 × 0.077 m and its top corners reached a
            // radius of 0.33 m, into the 0.30 m bore wall (emulator shot S04-t6)
            f.at(-0.80f, 0f, 0.19f) to CAPTION_SCALE_SIT
        }
    }

    // ------------------------------------------------------------------------------------------ tour

    private fun placeTour(state: UiState, pose: HeadPose, f: ScanFrame) {
        val touring = state.screen == Screen.S03
        val stop = if (touring) state.tourStop else null
        val coilLit = stop == TourStop.COIL
        if (coilLit != lastCoilLit) {
            lastCoilLit = coilLit
            (if (coilLit) coilHighlight else coilMaterial)?.let { coil?.setMaterial(it) }
        }
        val sh = shell ?: return
        val ld = leader ?: return
        if (stop == null) {
            if (lastHighlight != null) {
                sh.setVisible(false)
                ld.setVisible(false)
                lastHighlight = null
            }
            return
        }
        lastHighlight = stop
        val (center, size) = stopBounds(stop, f)
        // K1: nothing at the head (the coil) is "behind you"
        val behind = (center - pose.position).length > 2f * LEADER_MIN_HEAD_M && (center - pose.position).normalized().dot(pose.forward) < -0.2f
        if (behind != lastBehind) {
            lastBehind = behind
            vm.onTourBehind(behind)
        }
        // highlight shell 2% larger than the object (the coil is lit itself: the user's head is inside it)
        if (stop == TourStop.COIL) {
            sh.setVisible(false)
        } else {
            sh.setLocal(position = center, rotation = f.rotation, scale = size * 1.02f)
            sh.setVisible(true)
        }
        // leader line from the tag to the object
        val from = tagPos ?: return
        val d = center - from
        val len = d.length
        // no leader that passes the eyes (the coil at the head; sitting, the window behind the user): it would swell
        // into a wedge across the view and cover the tag (emulator shots S03-3 / S03-4)
        val t = if (len > 1e-4f) ((pose.position - from).dot(d) / (len * len)).coerceIn(0f, 1f) else 0f
        val nearest = (from + d * t - pose.position).length
        if (len > 0.05f && nearest > LEADER_MIN_HEAD_M) {
            val dir = d * (1f / len)
            val start = from + dir * 0.12f
            val l = (len - 0.12f).coerceAtLeast(0.01f)
            ld.setLocal(position = start + dir * (l / 2f), rotation = Q4.facing(dir, panelUp(f)), scale = V3(LEADER_W, LEADER_W, l))
            ld.setVisible(true)
        } else {
            ld.setVisible(false)
        }
    }

    /** Size (in the frame's axes) of a tour stop; the hit box is a little smaller than the highlight. */
    private fun stopSize(stop: TourStop): Pair<TourStop, V3> =
        stop to
            when (stop) {
                TourStop.GANTRY -> V3(Meshes.GANTRY_W, Meshes.GANTRY_H, Meshes.GANTRY_D)
                TourStop.TABLE -> V3(0.55f, 0.08f, 2.10f)
                TourStop.COIL -> V3(0.30f, 0.30f, 0.30f)
                TourStop.CALL_BALL -> V3(0.07f, 0.07f, 0.07f)
                TourStop.WINDOW -> V3(1.6f, 1.0f, 0.05f)
            }

    /** World centre and size (in the frame's axes) of a tour stop. */
    private fun stopBounds(stop: TourStop, f: ScanFrame): Pair<V3, V3> {
        val lie = f.posture == Posture.LIE
        val rs = if (lie) -1f else 1f
        val roomAt = { local: V3 -> f.h0 + f.b * slide.s + f.rotation.rotate(local) }
        val userAt = { local: V3 -> f.h0 + f.rotation.rotate(local) }
        return when (stop) {
            TourStop.GANTRY -> roomAt(V3(0f, 0f, -Slide.MAX)) to V3(Meshes.GANTRY_W, Meshes.GANTRY_H, Meshes.GANTRY_D)
            TourStop.TABLE -> userAt(V3(0f, TABLE_TOP - 0.04f, 0.6f)) to V3(0.55f, 0.08f, 2.10f)
            TourStop.COIL -> userAt(if (lie) V3(0f, -0.06f, 0.03f) else V3(0f, 0.03f, 0.06f)) to V3(0.34f, 0.34f, 0.34f)
            TourStop.CALL_BALL -> userAt(if (lie) V3(rs * 0.22f, TABLE_TOP + BALL_R, 0.55f) else V3(rs * 0.18f, -0.55f, -0.25f)) to V3(0.09f, 0.09f, 0.09f)
            TourStop.WINDOW -> roomAt(V3(0f, -f.floorBelow + 1.5f, ROOM_L / 2 - 0.02f)) to V3(1.6f, 1.0f, 0.05f)
        }
    }

    /** `testtex 1`: the "F↑" card beside the main card, and on the ceiling light box (taste D10). */
    private fun placeTestQuad(state: UiState, pose: HeadPose, f: ScanFrame) {
        val on = state.testTexture
        if (on != lastTestTex) {
            lastTestTex = on
            (if (on) testMaterial else ceilingMaterial)?.let { ceilingPanel?.setMaterial(it) }
            testQuad?.setVisible(on)
        }
        if (!on) return
        val base = cardPos ?: inFront(pose, f, CARD_DISTANCE, CARD_DOWN_DEG)
        val side = (pose.position - base).cross(panelUp(f)).normalized()
        val pos = base - side * 0.30f
        testQuad?.setLocal(position = pos, rotation = facingHead(pos, pose, f), scale = V3(0.12f, 0.12f, 1f))
    }

    /** `dump`: one log line with the head pose, the frame, s and where every panel holder is. */
    fun dumpLine(): String {
        val pose = head.last
        val sb = StringBuilder()
        sb.append("DUMP head=").append(pose?.position).append(" fwd=").append(pose?.forward).append(" frame=").append(scanFrame)
        sb.append(" s=").append("%.3f".format(slide.s)).append(" target=").append(slide.target).append(" meshLeft=").append(meshWork.size)
        sb.append(" screen=").append(vm.state.value.screen).append(" targets=").append(targets.size)
        for (slot in slots.values) {
            val engine = runCatching { slot.holder.convertPositionTo(Vector3.ZERO, root).domain() }.getOrNull()
            sb.append(" | ").append(slot.id).append(" shown=").append(slot.shown).append(" bound=").append(slot.panel != null).append(" composed=").append(panelShown[slot.id]).append(" at=").append(engine)
        }
        return sb.toString()
    }

    companion object {
        const val LIGHT_LUX = 1800f
        const val ROOM_W = 5.0f
        const val ROOM_L = 6.0f
        const val ROOM_H = 2.9f
        const val TABLE_TOP = -0.12f
        const val BALL_R = 0.03f
        const val CARD_DISTANCE = 0.9f
        const val CARD_DOWN_DEG = 8f
        const val CARD_SCALE = 0.8f
        const val PAUSE_DISTANCE = 0.6f
        const val PAUSE_SCALE = 0.6f
        const val TAG_SCALE = 0.8f
        const val CAPTION_SCALE_LIE = 0.5f
        const val CAPTION_SCALE_PRE = 0.8f
        const val CAPTION_SCALE_SIT = 1.0f
        const val LEADER_W = 0.004f
        const val DP_M = 0.0008f
        const val GRAB_W = 0.10f
        const val GRAB_H = 0.012f
        const val GRAB_GAP_M = 0.025f
        const val GRAB_HIT_W = 0.16f
        const val GRAB_HIT_H = 0.045f
        const val DRAG_START_M = 0.01f
        const val LEADER_MIN_HEAD_M = 0.3f
        const val HOLD_MS = 400L
        const val POSTURE_MS = 500L
        const val READING_MS = 100L
        const val SHOW_FRAMES = 2L
        const val FAST_FRAME_SEC = 0.04f
        const val SLOW_MESH_GAP_MS = 1000L
        const val FADE_SEC = 0.5f
        const val FIRST_FADE_DELAY_MS = 500L
        private val STOW = V3(0f, -40f, 0f)
        const val PARK_SCALE = 0.001f
    }
}
