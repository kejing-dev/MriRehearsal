package io.embodit.mrirehearsal.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.mrirehearsal.domain.LevelGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.embodit.mrirehearsal.platform.MriLog

/**
 * What the Stage and the main window share (factory native-window-move §3.4): two compositions in one process, so the
 * ViewModel lives here and not in either container's `viewModel()` — closing the window with its × keeps the scan
 * going. Also the window's lifecycle, the level gate every open goes through (§7 item 9, D9) and [Bring back in
 * front] (close, wait for ON_DESTROY, open again: §3.5).
 */
object MriShared {
    /** Main window container id and its fixed tag (one instance, reused). */
    const val WINDOW_ID = "MriWindow"
    const val WINDOW_TAG = "main"

    @Volatile private var vm: MriViewModel? = null

    fun viewModel(app: Application): MriViewModel = vm ?: synchronized(this) { vm ?: MriViewModel(app).also { vm = it } }

    /** The running ViewModel, if any (exit, debug). */
    val current: MriViewModel?
        get() = vm

    // ---- the window's lifecycle -------------------------------------------------------------

    /** Last lifecycle event the main window reported (`never` until it first opens). */
    var window by mutableStateOf("never")
        private set

    fun windowEvent(name: String) {
        if (window != name) MriLog.i("WINDOW $name")
        window = name
        if (name == "ON_DESTROY" && recallPending) {
            recallPending = false
            MriLog.i("WINDOW recall: destroyed, open again")
            askOpen("recall")
        }
    }

    /** The window is on screen (not closed with its ×, not minimised). */
    val windowOpen: Boolean
        get() = window == "ON_CREATE" || window == "ON_START" || window == "ON_RESUME" || window == "ON_PAUSE"

    /** Minimised (ON_STOP, not destroyed): the chip restores it where it was (native-window-move §2). */
    val windowMinimised: Boolean
        get() = window == "ON_STOP"

    /** Closed with its ×, or minimised, or never opened while no open is pending: the Stage shows its "open panel" chip. */
    val windowClosed: Boolean
        get() = (window == "ON_DESTROY" || window == "ON_STOP" || window == "never") && !gate.waiting && !recallPending && SystemClock.elapsedRealtime() - openedMs > OPENING_MS

    /** [Bring back in front] closed the window and waits for its ON_DESTROY to open it again. */
    var recallPending by mutableStateOf(false)
        private set

    // ---- opening only with an accepted head pose -------------------------------------------

    private val gate = LevelGate()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastStepMs = 0L

    /** The real open / close / restore, set by the Stage screen (it holds the navigator); null while no Stage is composed. */
    @Volatile var opener: (() -> Unit)? = null
    @Volatile var closer: (() -> Unit)? = null
    @Volatile var restorer: (() -> Boolean)? = null

    /** Exit (D26 / D44): releases the scene (trackers, entities); set by the Stage screen. */
    @Volatile var exiter: (() -> Unit)? = null

    /** The Stage's navigator (closes the window and the Stage on exit); null while no Stage is composed. */
    @Volatile var stageNavigator: SpatialNavigator? = null

    /** Process-level scope for the exit's last steps (the window's own scope dies with the window). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Told after every real open (the ViewModel lays the room out around the new window, WindowLayout.place). */
    @Volatile var onOpened: ((String) -> Unit)? = null

    /** "Look straight ahead" wanted on the line of sight (the Stage's chip slot shows it). */
    var levelHint by mutableStateOf(false)
        private set

    /** An open is waiting for an accepted head pose. */
    val openWaiting: Boolean
        get() = gate.waiting

    /** When the last open went out: the window takes 3 – 6 s to appear, the chip stays away meanwhile. */
    @Volatile var openedMs = 0L
        private set

    /** Asks for the window (Stage start, the chip, the reopen of [Bring back in front], debug). A minimised one is restored. */
    fun askOpen(why: String) {
        if (windowMinimised && restorer?.invoke() == true) {
            MriLog.i("WINDOW restore (was minimised) why=$why")
            return
        }
        MriLog.i("WINDOW ask why=$why")
        gate.ask(why, SystemClock.elapsedRealtime())
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3_000L)
    }

    /** [Bring back in front]: an open window is closed and opened again in front of the head; a closed one just opens. */
    fun recall(why: String) {
        if (windowOpen && closer != null) {
            recallPending = true
            MriLog.i("WINDOW recall why=$why: close, open after ON_DESTROY")
            closer?.invoke()
        } else {
            askOpen("recall/$why")
        }
    }

    /** Every Stage frame: the head pitch (+ = up), null without a valid head pose; [supine] = lying and looking up. */
    fun stepGate(now: Long, pitchDeg: Float?, supine: Boolean) {
        lastStepMs = now
        val hint = gate.hintWanted(pitchDeg, supine)
        if (hint != levelHint) {
            MriLog.i("LEVEL hint=$hint pitch=${pitchDeg?.let { "%.1f".format(it) }} supine=$supine")
            levelHint = hint
        }
        gate.step(now, pitchDeg, supine)?.let {
            levelHint = false
            MriLog.i("LEVEL ok pitch=${pitchDeg?.let { p -> "%.1f".format(p) }} supine=$supine -> open")
            openNow(it)
        }
    }

    fun cancelOpen() {
        gate.cancel()
        levelHint = false
        recallPending = false
        main.removeCallbacks(watchdog)
    }

    private fun openNow(why: String) {
        main.removeCallbacks(watchdog)
        MriLog.i("WINDOW open why=$why")
        openedMs = SystemClock.elapsedRealtime()
        runCatching { onOpened?.invoke(why) }.onFailure { MriLog.e("WINDOW onOpened failed", it) }
        opener?.invoke()
    }

    // a Stage without frames (not up yet, in the background) must not keep the window shut: 15 s after the ask if no
    // frame came at all, else 3 s without a frame
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000L else now - lastStepMs > 3_000L
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    openNow("$why/noframes")
                } else {
                    main.postDelayed(this, 3_000L)
                }
            }
        }

    private const val OPENING_MS = 8_000L
}
