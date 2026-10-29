package io.embodit.mrirehearsal.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
import io.embodit.mrirehearsal.debug.DebugHost
import io.embodit.mrirehearsal.platform.MriGraph
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.scene.MriFrameSystem
import io.embodit.mrirehearsal.scene.MriScene
import io.embodit.mrirehearsal.scene.PanelIds
import io.embodit.mrirehearsal.ui.card.CaptionStrip
import io.embodit.mrirehearsal.ui.card.MainCard
import io.embodit.mrirehearsal.ui.card.SettingsCard
import io.embodit.mrirehearsal.ui.card.TourTag
import io.embodit.mrirehearsal.ui.theme.AppLocale
import kotlinx.coroutines.withTimeoutOrNull

/** Sizes of the pre-built panels, dp (design package `panels[]`). */
object PanelSizes {
    val CARD = IntSize(480, 400)
    val CARD_TALL = IntSize(480, 440)
    val CAPTION = IntSize(420, 96)
    val TAG = IntSize(440, 160)
}

/**
 * The Stage content: one full-size SpatialView carrying the scan room and the panel pool. Every attachment panel is
 * declared here once with a fixed size, whether it is showing or not; the scene parents each panel under its holder
 * and shows or hides the holder (Spatial Ruler's panel pool).
 *
 * Input: the system pointer (controller ray + trigger / gaze + pinch) on the room's and the tour's colliders. A short
 * press on a tour point jumps there (T4); a press held 0.4 s anywhere on the room is the call ball (T5, pinch
 * path). The controller trigger held 0.4 s works without pointing at anything (scene, ControllerInput).
 */
@Composable
fun MriScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: MriViewModel = viewModel(factory = MriViewModel.Factory(application))
    val state by vm.state.collectAsStateWithLifecycle()
    val scene = remember(vm) { MriScene(vm, MriGraph.head, MriGraph.controller) }

    DisposableEffect(scene) {
        registerSystem<MriFrameSystem>()
        DebugHost.vm = vm
        DebugHost.scene = scene
        MriLog.i("STAGE enter")
        onDispose {
            DebugHost.vm = null
            DebugHost.scene = null
            unregisterSystem<MriFrameSystem>()
            scene.detach()
            MriLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            vm.onBackground()
                            scene.onBackground()
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            vm.onForeground()
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppLocale(state.language) {
        SpatialView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            context = context,
                            targetedToEntity = TargetEntity.any { entity -> scene.accept(entity) },
                            onPress = {
                                val hit = scene.lastHit
                                val t0 = SystemClock.elapsedRealtime()
                                val released = withTimeoutOrNull(HOLD_MS) { tryAwaitRelease() }
                                if (released == null) {
                                    MriLog.i("CALL pinch held hit=${scene.hitName(hit)}")
                                    vm.onCallHold()
                                    tryAwaitRelease()
                                } else if (released) {
                                    MriLog.i("TAP 3D ms=${SystemClock.elapsedRealtime() - t0} hit=${scene.hitName(hit)}")
                                    scene.onTap(hit)
                                }
                            },
                        )
                    }
                    // D21: drag the main card by its grab bar (a separate recognizer, as dose-distance does)
                    .pointerInput(Unit) {
                        detectSpatialDragGesture(
                            context = context,
                            targetedToEntity = TargetEntity.any { entity -> scene.isGrab(entity) },
                            onDragStart = { start, value -> scene.onCardDrag("start", start, value) },
                            onDragEnd = { value -> scene.onCardDrag("end", null, value) },
                            onDragCancel = { value -> scene.onCardDrag("cancel", null, value) },
                        ) { value -> scene.onCardDrag("move", null, value) }
                    },
            attachments = {
                AttachmentPanel(PanelIds.CARD) {
                    Gate(scene, PanelIds.CARD, PanelSizes.CARD) { MainCard(state, vm) }
                }
                AttachmentPanel(PanelIds.CARD_TALL) {
                    Gate(scene, PanelIds.CARD_TALL, PanelSizes.CARD_TALL) { SettingsCard(state, vm) }
                }
                AttachmentPanel(PanelIds.CAPTION) {
                    Gate(scene, PanelIds.CAPTION, PanelSizes.CAPTION) { CaptionStrip(state, vm) }
                }
                AttachmentPanel(PanelIds.TAG) {
                    Gate(scene, PanelIds.TAG, PanelSizes.TAG) { TourTag(state, vm) }
                }
            },
            update = { _, attachments -> scene.bind(attachments) },
            initial = { content, attachments -> scene.attach(content, attachments) },
        )
    }
}

/** A panel's content is composed only once the scene has shown the panel (drawn while parked, it stays broken). */
@Composable
private fun Gate(scene: MriScene, id: String, size: IntSize, content: @Composable () -> Unit) {
    val shown = scene.panelShown[id] == true
    Box(modifier = Modifier.size(size.width.dp, size.height.dp)) {
        if (shown) content()
    }
}

private const val HOLD_MS = 400L
