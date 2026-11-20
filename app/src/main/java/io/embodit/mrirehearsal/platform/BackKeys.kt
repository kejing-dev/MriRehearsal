package io.embodit.mrirehearsal.platform

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.Window

/**
 * Factory profile D60 补 (QA #5 q5-2): BACK and controller B are caught at the earliest layer of every window of the app
 * and all go to one handler ([handler] = MriShared.onBack: the window closed = open it again, open = nothing). They never
 * exit the app or send the scan room to the background. Ported from the factory's reference (glow-bench v0.1.3), where
 * an OnBackPressedCallback alone was not enough: after a tap on the native TabBar the key went into the tab's own window
 * and never reached the dispatcher. Layers:
 *  1. every Activity window (the Stage, the main window): its Window.Callback is wrapped, dispatchKeyEvent sees
 *     BACK / BUTTON_B before any view (KEYCODE_BACK, KEYCODE_BUTTON_B, KEYCODE_ESCAPE);
 *  2. every other window of the process (TabBar, system-window pieces the SDK adds through WindowManager): its root view
 *     gets an unhandled-key listener, swept on each Activity event and on every tab tap;
 *  3. the main window's Compose root and every TabBar item route BACK / B in onPreviewKeyEvent (ui/card/Keys.kt), and
 *     eat A / X / Y / ENTER / DPAD_CENTER there (unassigned on this app).
 * An OnBackPressedCallback on every ComponentActivity stays as the last fallback (Platform.kt).
 * DOWN is consumed silently, UP fires the handler once; a second layer seeing the same press (same downTime) is dropped.
 */
object BackKeys {
    @Volatile var handler: (String) -> Unit = {}

    private var lastFiredDownTime = -1L

    fun isBack(code: Int) = code == KEYCODE_BACK || code == KEYCODE_BUTTON_B || code == KEYCODE_ESCAPE

    /** True if the event was BACK / B (consumed). The handler runs on the UP of a press, once per press. */
    fun route(e: KeyEvent, where: String): Boolean = route(e.keyCode, e.action, e.downTime, e.isCanceled, where)

    /** Same as [route] with the raw fields (unit-tested without android.view.KeyEvent). */
    fun route(code: Int, action: Int, downTime: Long, canceled: Boolean, where: String): Boolean {
        if (action == ACTION_UP) trace(code, where)
        if (!isBack(code)) return false
        if (action == ACTION_UP && !canceled) fire(downTime, "$where ${codeName(code)}")
        return true
    }

    private fun codeName(code: Int) =
        when (code) {
            KEYCODE_BACK -> "BACK"
            KEYCODE_BUTTON_B -> "BUTTON_B"
            else -> "ESCAPE"
        }

    // KeyEvent constants (stable since API 1 / 9), kept here so the JVM unit test needs no android stubs
    const val KEYCODE_BACK = 4
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_ESCAPE = 111
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    // one log line per key press and layer (QA / ui-audit evidence of which layer saw the key); off in unit tests
    @Volatile var traceOn = false

    private fun trace(code: Int, where: String) {
        if (traceOn) runCatching { MriLog.i("KEY up code=$code at $where") }
    }

    @Synchronized
    internal fun reset() {
        lastFiredDownTime = -1L
    }

    @Synchronized
    private fun fire(downTime: Long, where: String) {
        if (downTime == lastFiredDownTime) return
        lastFiredDownTime = downTime
        handler(where)
    }

    /** Layer 1: wrap the Activity window's callback (idempotent). */
    fun wrap(a: Activity) {
        val w = a.window ?: return
        val cb = w.callback ?: return
        if (cb is Wrapped) return
        w.callback = Wrapped(cb, a.javaClass.simpleName)
        MriLog.i("BACK keys wrapped on ${a.javaClass.simpleName}")
    }

    private class Wrapped(private val d: Window.Callback, private val name: String) : Window.Callback by d {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = route(event, "key:$name") || d.dispatchKeyEvent(event)
    }

    private val hooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var lastSweepMs = 0L

    /**
     * Layer 2 again from any thread, at most once a second (the scene's frame calls it): roots the SDK adds later (the
     * Stage's panels, the TabBar after a page change, the system window's pieces) get hooked too.
     */
    fun sweepSoon(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSweepMs < 1_000L) return
        lastSweepMs = now
        main.post { sweep(reason) }
    }

    /** Layer 2: every root view of this process (WindowManagerGlobal), so a focused TabBar window cannot swallow BACK. */
    fun sweep(reason: String) {
        val roots =
            runCatching {
                val c = Class.forName("android.view.WindowManagerGlobal")
                val g = c.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                val names = c.getMethod("getViewRootNames").invoke(g) as Array<String>
                names.mapNotNull { n -> runCatching { c.getMethod("getRootView", String::class.java).invoke(g, n) as? View }.getOrNull()?.let { n to it } }
            }.getOrElse {
                MriLog.w("BACK sweep failed: $it")
                emptyList()
            }
        for ((n, v) in roots) hook(v, n, reason)
    }

    private fun hook(v: View, name: String, reason: String) {
        synchronized(hooked) { if (!hooked.add(v)) return }
        val short = name.substringAfterLast('/').take(48)
        // runs when no view in that window handled the key (a window without an Activity never reaches the dispatcher)
        v.addOnUnhandledKeyEventListener { _, e -> route(e, "unhandled:$short") }
        // a dialog-like root (the TabBar) handles BACK itself before anything is "unhandled": wrap its window's callback
        // too, the same way as an Activity's (DecorView.mWindow; reflection, logged if the platform refuses it)
        val wrapped =
            runCatching {
                val f = v.javaClass.getDeclaredField("mWindow")
                f.isAccessible = true
                val w = f.get(v) as? Window ?: return@runCatching "no window"
                val cb = w.callback ?: return@runCatching "no callback"
                if (cb is Wrapped) return@runCatching "already"
                w.callback = Wrapped(cb, "root:$short")
                "wrapped ${cb.javaClass.simpleName}"
            }.getOrElse { "not wrapped (${it.javaClass.simpleName})" }
        MriLog.i("BACK keys hooked root $short ${v.javaClass.simpleName} ($reason) callback $wrapped")
    }
}
