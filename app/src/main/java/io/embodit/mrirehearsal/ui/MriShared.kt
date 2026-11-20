package io.embodit.mrirehearsal.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.embodit.mrirehearsal.domain.BackRule
import io.embodit.mrirehearsal.platform.MriLog

/**
 * What the Stage and the main window share (factory native-window-move §3.4): two compositions in one process, so the
 * ViewModel lives here and not in either container's `viewModel()` — closing the window with its × keeps the scan
 * going. Also the window's lifecycle, the open (always straight away, whatever the head pitch: profile D9 改 / D10),
 * [Bring back in front] (close, wait for ON_DESTROY, open again: §3.5) and the BACK / controller B handler (D60).
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

    /** Closed with its ×, or minimised, or never opened (and no open just went out): the Stage shows its "open panel" chip. */
    val windowClosed: Boolean
        get() = (window == "ON_DESTROY" || window == "ON_STOP" || window == "never") && !recallPending && SystemClock.elapsedRealtime() - openedMs > OPENING_MS

    /** [Bring back in front] closed the window and waits for its ON_DESTROY to open it again. */
    var recallPending by mutableStateOf(false)
        private set

    // ---- opening: always straight away (profile D9 改 / D10, QA #5 q5-1) ----------------------------------------

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

    /** When the last open went out: the window takes a moment to appear, the chip stays away meanwhile. */
    @Volatile var openedMs = 0L
        private set

    /**
     * Opens the window now (Stage start, the chip, the reopen of [Bring back in front], BACK while closed, debug),
     * whatever the head pitch: the system opens it on the line of sight and the room is laid out clear of it
     * (WindowLayout.place). A minimised one is restored where it was.
     */
    fun askOpen(why: String) {
        if (windowMinimised && restorer?.invoke() == true) {
            MriLog.i("WINDOW restore (was minimised) why=$why")
            return
        }
        openNow(why)
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

    fun cancelOpen() {
        recallPending = false
    }

    /**
     * BACK / controller B (BackKeys, D60 补, QA #5 q5-2): never exits, never sends the room to the background. The window
     * closed (×, minimised, never opened) = open it again; open, or an open / recall still on its way = nothing.
     */
    fun onBack(where: String) {
        val inFlight = recallPending || (window != "ON_STOP" && SystemClock.elapsedRealtime() - openedMs < BACK_OPEN_GRACE_MS)
        when (BackRule.decide(windowOpen, inFlight, opener != null)) {
            BackRule.Action.EATEN_OPEN -> MriLog.i("BACK $where -> eaten (window open)")
            BackRule.Action.EATEN_OPENING -> MriLog.i("BACK $where -> eaten (window opening)")
            BackRule.Action.EATEN_NO_STAGE -> MriLog.i("BACK $where -> eaten (no stage)")
            BackRule.Action.REOPEN -> {
                MriLog.i("BACK $where -> reopen window")
                askOpen("back")
            }
        }
    }

    private fun openNow(why: String) {
        MriLog.i("WINDOW open why=$why")
        openedMs = SystemClock.elapsedRealtime()
        runCatching { onOpened?.invoke(why) }.onFailure { MriLog.e("WINDOW onOpened failed", it) }
        opener?.invoke()
    }

    private const val BACK_OPEN_GRACE_MS = 3_000L
    private const val OPENING_MS = 8_000L
}
