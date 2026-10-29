package io.embodit.mrirehearsal.platform

import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The call ball on the controller (design T5): the trigger of either controller held for 0.4 s, wherever it points.
 * Only the trigger is read (`ControllerActionListener`, same as dance-steps / bianzhong); a short press stays a UI
 * click (R1-5). The listener runs on the data source's thread and only copies a flag; the frame system reads it.
 *
 * Nothing here aims or selects: pointing at panels and tour points is the system pointer's job.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val down = AtomicBoolean(false)

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            val right = runCatching { action.right.triggerPressed || action.right.triggerValue > PRESS_AT }.getOrDefault(false)
            val left = runCatching { action.left.triggerPressed || action.left.triggerValue > PRESS_AT }.getOrDefault(false)
            down.set(right || left)
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        MriLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        down.set(false)
    }

    /** True while a trigger is held. */
    val triggerDown: Boolean
        get() = down.get()

    private companion object {
        const val PRESS_AT = 0.7f
    }
}
