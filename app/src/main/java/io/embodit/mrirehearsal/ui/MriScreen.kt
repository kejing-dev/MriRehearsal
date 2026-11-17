package io.embodit.mrirehearsal.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectTapGestures
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.mrirehearsal.debug.DebugHost
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.platform.MriGraph
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.scene.MriFrameSystem
import io.embodit.mrirehearsal.scene.MriScene
import io.embodit.mrirehearsal.scene.PanelIds
import io.embodit.mrirehearsal.ui.card.CaptionStrip
import io.embodit.mrirehearsal.ui.card.OpenPanelChip
import io.embodit.mrirehearsal.ui.theme.AppLocale
import kotlinx.coroutines.withTimeoutOrNull

/** Sizes of the Stage's two small panels, dp. */
object PanelSizes {
    val CAPTION = IntSize(420, 96)
    val CHIP = IntSize(360, 120)
}

/**
 * The Stage (Full Space, Progressive): the scan room in one SpatialView, the in-bore readout and the "open panel"
 * chip. Every page of the app is in the main system window (`MriWindow`), which this screen opens — only with an
 * accepted head pose (LevelGate, D9) — closes for [Bring back in front], and restores. Moving and closing the window
 * are the system's own controls; nothing here drags a panel (D21).
 *
 * Input in the room: the system pointer (controller ray + trigger / gaze + pinch) on the room's and the tour's colliders.
 * A short press on a tour point jumps there (T4); a press held 0.4 s on a collider is the call ball (T5, pinch path).
 * The controller trigger held 0.4 s works without pointing at anything (scene, ControllerInput).
 */
@Composable
fun MriScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm = remember(application) { MriShared.viewModel(application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scene = remember(vm) { MriScene(vm, MriGraph.head, MriGraph.controller) }
    val navigator = LocalSpatialNavigator.current

    // the window's open / close / restore go through the navigator held here; every open waits for the head (MriShared)
    DisposableEffect(navigator) {
        MriShared.opener = { navigator.openWindowContainer(MriShared.WINDOW_ID, MriShared.WINDOW_TAG) }
        MriShared.closer = { navigator.closeWindowContainer(MriShared.WINDOW_ID, MriShared.WINDOW_TAG) }
        MriShared.restorer = { navigator.restoreWindowContainer(MriShared.WINDOW_ID, MriShared.WINDOW_TAG) }
        MriShared.onOpened = { why -> vm.onWindowOpened(why) }
        MriShared.stageNavigator = navigator
        // D26 / D44 exit (ui/window/MainWindow.exitApp): the scene's trackers and entities go first
        MriShared.exiter = { scene.detach() }
        onDispose {
            MriShared.cancelOpen()
            MriShared.opener = null
            MriShared.closer = null
            MriShared.restorer = null
            MriShared.onOpened = null
            MriShared.exiter = null
            MriShared.stageNavigator = null
        }
    }
    LaunchedEffect(Unit) { MriShared.askOpen("stage") }

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
                    // gate:allow board-input the room's colliders are not a board: tap = jump to a tour stop, hold = call (no drag)
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
                    },
            attachments = {
                AttachmentPanel(PanelIds.CAPTION) {
                    Gate(scene, PanelIds.CAPTION, PanelSizes.CAPTION) { CaptionStrip(state, vm) }
                }
                AttachmentPanel(PanelIds.CHIP) {
                    Gate(scene, PanelIds.CHIP, PanelSizes.CHIP) { OpenPanelChip(lying = state.posture == Posture.LIE) }
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
