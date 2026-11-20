package io.embodit.mrirehearsal.ui.window

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import androidx.compose.ui.platform.LocalFocusManager
import io.embodit.mrirehearsal.platform.BackKeys
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.ui.card.NoFocus
import io.embodit.mrirehearsal.ui.card.eatPadKeys
import io.embodit.mrirehearsal.ui.MriShared
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Main window template (factory prompts/snippets/MainWindowTemplate.kt; taste D8 "one window", D21 moving is the
 * system's, D26 exit in settings). Adapted as in pruning-class / too-much: the shown page is the caller's (a tab press
 * is an event, the state machine can bring the scan page forward), and the settings page is the app's own (bilingual,
 * [Bring back in front] and [Quit app] on its first screen, the confirmation inside the same page).
 *  - All 2D content is in this one system window; pages switch with the native SpatialUI TabBar only.
 *  - Moving, closing and minimising are the system window controls under the window; no drag code here.
 */

/** One page of the main window. [label]: already in the app's language (the TabBar is its own system window). */
class MainWindowPage(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val content: @Composable () -> Unit,
)

/** The app's single main window: [pages] (at most 7, the TabBar limit), [current] shown, [onSelect] for a tab press. */
@Composable
fun MainWindow(pages: List<MainWindowPage>, current: String, onSelect: (String) -> Unit) {
    require(pages.size in 1..7) { "TabBar holds at most 7 items" }
    val page = pages.firstOrNull { it.id == current } ?: pages.first()
    val focus = LocalFocusManager.current
    // the window is sized for the tallest page (settings): pages fill it, no scrolling. D60 (QA #5 q5-2): the root routes
    // BACK / B to the one back handler and eats A / X / Y / ENTER / DPAD_CENTER (ui/card/Keys.kt)
    Box(Modifier.fillMaxSize().eatPadKeys("window")) { page.content() }
    // system TabBar: its own small window outside this one (emulator 6.1.0: an icon capsule above the window). Labels and
    // accessibility names come from string resources in the app's language, resolved before they go in (gate tabbar-lang)
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        pages.forEach { p ->
            item(
                selected = page.id == p.id,
                // q5-2 (D60): a tab never keeps focus, so BACK / controller B are not swallowed by it after a tap
                modifier = NoFocus.eatPadKeys("tab"),
                onClick = {
                    MriLog.i("TAB ${p.id}")
                    onSelect(p.id)
                    focus.clearFocus(force = true)
                    BackKeys.sweep("tab")
                },
                mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                supportContent = { Text(p.label) },
            )
        }
    }
}

/**
 * D26 / D44 exit, template order: release (scene, sound, trackers), close every WindowContainer, close the Stage, remove
 * every task, then end the process. Closing this window disposes the composition that called it, so the rest runs on
 * the process-level scope ([MriShared.appScope]), not on a coroutine of this window.
 */
fun exitApp(context: Context, windowNavigator: SpatialNavigator) {
    MriLog.i("EXIT release + closeWindow + closeStage + finishAndRemoveTask + exitProcess")
    runCatching { MriShared.exiter?.invoke() }.onFailure { MriLog.e("EXIT release failed", it) }
    runCatching { MriShared.current?.release() }
    MriShared.cancelOpen()
    val navigator = MriShared.stageNavigator ?: windowNavigator
    runCatching { navigator.closeWindowContainer(MriShared.WINDOW_ID, MriShared.WINDOW_TAG) }.onFailure { MriLog.w("EXIT closeWindow: $it") }
    val app = context.applicationContext
    MriShared.appScope.launch {
        runCatching { withTimeoutOrNull(1_500L) { navigator.closeStage() } }.onFailure { MriLog.w("EXIT closeStage: $it") }
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }.onFailure { MriLog.e("EXIT finishAndRemoveTask failed", it) }
        delay(400L)
        exitProcess(0)
    }
}
