package io.embodit.mrirehearsal.ui.window

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.mrirehearsal.BuildConfig
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.domain.AppLanguage
import io.embodit.mrirehearsal.domain.Grade
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.Motion
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Score
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.domain.VolumeSteps
import io.embodit.mrirehearsal.domain.WindowPage
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.ui.MriShared
import io.embodit.mrirehearsal.ui.MriViewModel
import io.embodit.mrirehearsal.ui.card.BUTTON_GAP
import io.embodit.mrirehearsal.ui.card.BUTTON_H
import io.embodit.mrirehearsal.ui.card.CaptionLine
import io.embodit.mrirehearsal.ui.card.LinkText
import io.embodit.mrirehearsal.ui.card.LocalPanelName
import io.embodit.mrirehearsal.ui.card.MIN_TOUCH
import io.embodit.mrirehearsal.ui.card.MuteButton
import io.embodit.mrirehearsal.ui.card.PrimaryButton
import io.embodit.mrirehearsal.ui.card.SecondaryButton
import io.embodit.mrirehearsal.ui.card.StatusLine
import io.embodit.mrirehearsal.ui.card.StillRing
import io.embodit.mrirehearsal.ui.card.Txt
import io.embodit.mrirehearsal.ui.card.captionText
import io.embodit.mrirehearsal.ui.card.clockText
import io.embodit.mrirehearsal.ui.card.levelName
import io.embodit.mrirehearsal.ui.card.progressText
import io.embodit.mrirehearsal.ui.theme.AppLocale
import io.embodit.mrirehearsal.ui.theme.MriColors
import io.embodit.mrirehearsal.ui.theme.str
import io.embodit.mrirehearsal.ui.theme.strArray
import kotlin.math.roundToInt

/**
 * Content of the main system window (Planar WindowContainer, `Main.kt`, 640 × 620 dp): every 2D page of the app in one
 * window, four native TabBar pages — the scan (S00–S11 by state: ready, scan room, tour, the run, the pause, the
 * result), how to (profile B19), about (S13) and settings (S12, [Bring back in front] and [Quit app] first). Mute is on
 * every scan page (D15, B31 exception). Moving / closing is the system window's (v0.1.1, native-window-move).
 */
@Composable
fun MriWindow() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm = remember(app) { MriShared.viewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()

    // the Stage shows its "open panel" chip while this window is closed, and recalls it by close + reopen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> MriShared.windowEvent(e.name) }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            MriShared.windowEvent("ON_DESTROY")
        }
    }

    // QA / contrast table (tools/contrast.py): the theme's text and fill roles as this window really has them
    val scheme = PicoTheme.colorScheme
    LaunchedEffect(scheme) {
        fun h(c: androidx.compose.ui.graphics.Color) = "#%06X".format(c.toArgb() and 0xFFFFFF) + "/a%.2f".format(c.alpha)
        MriLog.i("COLORS labelPrimary=${h(scheme.labelPrimary)} labelSecondary=${h(scheme.labelSecondary)} labelTertiary=${h(scheme.labelTertiary)} interaction=${h(scheme.interaction)} fillPrimary=${h(scheme.fillPrimary)} fillSecondary=${h(scheme.fillSecondary)} fillTertiary=${h(scheme.fillTertiary)} error=${h(scheme.error)} alert=${h(scheme.alert)}")
    }

    AppLocale(state.language) {
        CompositionLocalProvider(LocalPanelName provides "window") {
            val pages =
                listOf(
                    MainWindowPage(WindowPage.SCAN.id, str(R.string.tab_scan), R.drawable.ic_home) { ScanPage(state, vm) },
                    MainWindowPage(WindowPage.HOWTO.id, str(R.string.tab_howto), R.drawable.ic_touch_app) { HowToPage() },
                    MainWindowPage(WindowPage.ABOUT.id, str(R.string.tab_about), R.drawable.ic_info) { AboutPage() },
                    MainWindowPage(WindowPage.SETTINGS.id, str(R.string.tab_settings), R.drawable.ic_settings) { SettingsPage(state, vm) },
                )
            MainWindow(pages, state.page.id) { id -> WindowPage.fromId(id)?.let { vm.selectPage(it) } }
        }
    }
}

// --------------------------------------------------------------------------------------------- frame of a page

/** Every page: 32 / 24 dp margins; a short page spreads its blocks over the height (whitespace ≤ 25 %, D44). */
@Composable
private fun Page(spread: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = if (spread) Arrangement.SpaceEvenly else Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        content = content,
    )
}

/** Title of a scan page with mute at its right (D15: always on the main page). */
@Composable
private fun Header(title: String, state: UiState, vm: MriViewModel, sub: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
        Column(modifier = Modifier.weight(1f)) {
            Txt(title, style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
            if (sub != null) Txt(sub, style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
        }
        MuteButton(state.settings.muted, onClick = { vm.toggleMute() })
    }
}

@Composable
private fun Body(text: String, lines: Int = 3, secondary: Boolean = true) {
    Txt(text, style = PicoTheme.typography.bodyLarge, color = if (secondary) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary, maxLines = lines, modifier = Modifier.fillMaxWidth())
}

/** An inline confirmation (the question takes the page; no second panel): icon, question, what happens, two buttons. */
@Composable
private fun Confirm(icon: Int, question: String, body: String, no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit) {
    Page(spread = true) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally), tint = PicoTheme.colorScheme.labelPrimary)
        Txt(question, style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Txt(body, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(no, onClick = onNo, modifier = Modifier.weight(1f))
            PrimaryButton(yes, onClick = onYes, modifier = Modifier.weight(1f))
        }
    }
}

// --------------------------------------------------------------------------------------------- the scan page

@Composable
private fun ScanPage(state: UiState, vm: MriViewModel) {
    when (state.screen) {
        Screen.S00 -> LoadingPage(state, vm)
        Screen.S01 -> ReadyPage(state, vm)
        Screen.S02 -> RoomPage(state, vm)
        Screen.S03 -> TourPage(state, vm)
        Screen.S09 -> PausePage(state, vm)
        Screen.S11 -> ResultPage(state, vm)
        else -> RunPage(state, vm)
    }
}

@Composable
private fun LoadingPage(state: UiState, vm: MriViewModel) {
    Page(spread = true) {
        Header(str(R.string.app_title), state, vm)
        Txt(str(R.string.loading), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
        Body(str(R.string.disclaimer))
        Body(str(R.string.ready_next))
    }
}

@Composable
private fun ReadyPage(state: UiState, vm: MriViewModel) {
    Page(spread = true) {
        Header(str(R.string.app_title), state, vm)
        Body(str(R.string.disclaimer))
        val lie = state.posture == Posture.LIE
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            Icon(painter = painterResource(if (lie) R.drawable.ic_lie else R.drawable.ic_sit), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.labelPrimary)
            val posture = if (!state.headFound) str(R.string.posture_finding) else str(R.string.posture_label) + " " + if (lie) str(R.string.posture_lie) else str(R.string.posture_sit)
            Txt(posture, style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
            LinkText(if (lie) str(R.string.link_sit) else str(R.string.link_lie), onClick = { vm.setPostureManual(if (lie) Posture.SIT else Posture.LIE) })
        }
        Body(str(R.string.ready_next), 2)
        PrimaryButton(str(R.string.enter), onClick = { vm.enterRoom() }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow)
    }
}

@Composable
private fun RoomPage(state: UiState, vm: MriViewModel) {
    Page {
        Header(str(R.string.room_title), state, vm)
        val grades = strArray(R.array.grades)
        Column(verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            for (level in Level.entries) {
                val best = state.best[level]
                val right =
                    when {
                        level == Level.L0 -> str(R.string.tour_stops, state.tourStops.size)
                        best == null -> str(R.string.best_none)
                        else -> str(R.string.best_fmt, grades[Score.grade(best).ordinal], best)
                    }
                Button(onClick = { vm.selectLevel(level) }, modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_H)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Txt(levelName(level), style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        // the button's own content colour: labelSecondary on the dark fill read 1.5:1 (emulator shot)
                        Txt(right, style = PicoTheme.typography.titleMedium)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(str(R.string.recall), onClick = { vm.recall("room") }, icon = R.drawable.ic_my_location)
            if (state.lost) StatusLine(str(R.string.lost_short), MriColors.Minor)
        }
    }
}

@Composable
private fun TourPage(state: UiState, vm: MriViewModel) {
    val names = strArray(R.array.tour_names)
    val bodies = strArray(R.array.tour_bodies)
    val stops = state.tourStops
    val i = state.tourIndex.coerceIn(0, stops.lastIndex)
    val stop = stops[i]
    val demo = state.tourDemoUntilMs != 0L && SystemClock.elapsedRealtime() < state.tourDemoUntilMs
    Page(spread = true) {
        Header(names[stop.ordinal], state, vm, sub = levelName(Level.L0) + " · " + str(R.string.tour_of, i + 1, stops.size))
        Body(bodies[stop.ordinal].format(state.settings.boreCm) + if (state.tourBehind) " " + str(R.string.tour_behind) else "", 4, secondary = false)
        if (demo) StatusLine(str(R.string.tour_demo), MriColors.Accent, style = PicoTheme.typography.bodyMedium, maxLines = 2) else Body(str(R.string.tour_tap_hint), 2)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(str(R.string.prev), onClick = { vm.tourStep(-1) }, modifier = Modifier.weight(1f), enabled = i > 0)
            if (i < stops.lastIndex) {
                PrimaryButton(str(R.string.next_fmt, i + 2, stops.size), onClick = { vm.tourStep(1) }, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton(str(R.string.back_room), onClick = { vm.tourBack() }, modifier = Modifier.weight(1f))
            }
        }
        if (i < stops.lastIndex) LinkText(str(R.string.back_room), onClick = { vm.tourBack() })
    }
}

/** S04–S07 and S10: the run, the same lines as the in-bore readout (the window is behind the gantry once in the bore). */
@Composable
private fun RunPage(state: UiState, vm: MriViewModel) {
    Page(spread = true) {
        Header(levelName(state.level), state, vm)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            StillRing(state, 56.dp)
            Column(modifier = Modifier.weight(1f)) {
                Txt(progressText(state), style = PicoTheme.typography.titleLarge, color = PicoTheme.colorScheme.labelPrimary)
                val word =
                    when (state.motion) {
                        Motion.STILL -> str(R.string.motion_still)
                        Motion.MINOR -> str(R.string.motion_minor)
                        Motion.MAJOR -> str(R.string.motion_major)
                    }
                Txt(word, style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
        CaptionLine(captionText(state.caption), PicoTheme.typography.headlineSmall, 2, align = androidx.compose.ui.text.style.TextAlign.Start)
        if (state.lost) StatusLine(str(R.string.lost_short), MriColors.Minor, style = PicoTheme.typography.bodyLarge)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painter = painterResource(R.drawable.ic_pan_tool), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Txt(str(R.string.call_hint), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}

private enum class PauseAsk { RESTART, FINISH }

/** S09: called. Resume, restart this level (confirmed: it clears the run, D44), or slide out and finish (confirmed). */
@Composable
private fun PausePage(state: UiState, vm: MriViewModel) {
    var ask by remember { mutableStateOf<PauseAsk?>(null) }
    when (ask) {
        PauseAsk.RESTART -> return Confirm(R.drawable.ic_replay, str(R.string.restart_confirm), str(R.string.restart_body), str(R.string.cancel), str(R.string.restart_yes), { ask = null }) {
            ask = null
            vm.restartLevel()
        }
        PauseAsk.FINISH -> return Confirm(R.drawable.ic_stop, str(R.string.finish_confirm), str(R.string.finish_body), str(R.string.cancel), str(R.string.finish_yes), { ask = null }) {
            ask = null
            vm.finishFromPause()
        }
        null -> Unit
    }
    Page(spread = true) {
        Header(str(R.string.called), state, vm)
        Body(if (state.pauseCardReady) str(R.string.called_body) else str(R.string.called_sliding), 2, secondary = false)
        if (state.level.isScan) Txt(str(R.string.done_of, clockText(state.clock.levelSec), clockText(state.level.totalSec.toFloat())), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelSecondary)
        if (state.lost) StatusLine(str(R.string.lost_pause), MriColors.Minor, style = PicoTheme.typography.bodyLarge)
        PrimaryButton(str(R.string.resume), onClick = { vm.resume() }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            if (state.level.isScan) SecondaryButton(str(R.string.restart_level), onClick = { ask = PauseAsk.RESTART; MriLog.i("ASK restart") }, modifier = Modifier.weight(1f), icon = R.drawable.ic_replay)
            SecondaryButton(str(R.string.finish), onClick = { ask = PauseAsk.FINISH; MriLog.i("ASK finish") }, modifier = Modifier.weight(1f), icon = R.drawable.ic_stop)
        }
    }
}

@Composable
private fun ResultPage(state: UiState, vm: MriViewModel) {
    val result = state.result ?: return
    val grades = strArray(R.array.grades)
    Page {
        Header(str(R.string.result), state, vm, sub = levelName(result.level))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            Txt("${result.score}", style = PicoTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary)
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                val gradeColor =
                    when (result.grade) {
                        Grade.STEADY -> MriColors.GradeGood
                        Grade.GOOD -> MriColors.Accent
                        Grade.RETRY -> MriColors.GradeRetry
                    }
                StatusLine(grades[result.grade.ordinal], gradeColor, style = PicoTheme.typography.titleLarge)
                Txt(str(R.string.score_label) + " · " + str(R.string.events_fmt, result.events, result.minorSec.roundToInt()), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
        Timeline(state)
        Txt(str(R.string.legend), style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
        Body(if (result.events > 0) str(R.string.verdict_redo) else str(R.string.verdict_ok), 2, secondary = false)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            val last = result.level.next() == null
            if (last) {
                PrimaryButton(str(R.string.retry), onClick = { vm.retry() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_replay)
            } else {
                PrimaryButton(str(R.string.next_level), onClick = { vm.nextLevel() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_skip_next)
                SecondaryButton(str(R.string.retry), onClick = { vm.retry() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_replay)
            }
        }
        LinkText(str(R.string.back_room), onClick = { vm.backToRoom() })
    }
}

/**
 * The run as a line: total length = the level's seconds; gaps grey (not scored), amber spans = small movement,
 * red dots = big movements (R2-5). Domain visualisation, drawn on a Canvas; not clickable.
 */
@Composable
private fun Timeline(state: UiState) {
    val result = state.result ?: return
    val level = result.level
    val total = level.totalSec.toFloat().coerceAtLeast(1f)
    val track = PicoTheme.colorScheme.fillSecondary
    val gap = PicoTheme.colorScheme.fillTertiary
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val h = size.height
        val barH = h * 0.5f
        val y = (h - barH) / 2
        val w = size.width
        drawRoundRect(track, topLeft = Offset(0f, y), size = Size(w, barH), cornerRadius = CornerRadius(barH / 2, barH / 2))
        var t = 0f
        for (seg in level.segments) {
            if (seg.type.isGap) drawRect(gap, topLeft = Offset(w * t / total, y), size = Size(w * seg.seconds / total, barH))
            t += seg.seconds
        }
        for (span in result.minorSpans) {
            val x0 = w * (span.fromSec / total).coerceIn(0f, 1f)
            val x1 = w * (span.toSec / total).coerceIn(0f, 1f)
            drawRect(MriColors.Minor, topLeft = Offset(x0, y), size = Size((x1 - x0).coerceAtLeast(2f), barH))
        }
        for (mark in result.eventMarks) {
            drawCircle(MriColors.Major, radius = h * 0.42f, center = Offset(w * (mark / total).coerceIn(0f, 1f), h / 2))
        }
    }
}

// --------------------------------------------------------------------------------------------- how to

/** One row of the how-to page: icon, the action, then how with hands and how with a controller (profile B19). */
private data class HowTo(val icon: Int, val title: Int, val hands: Int, val controller: Int)

private val HOW_TO =
    listOf(
        HowTo(R.drawable.ic_touch_app, R.string.how_to_press, R.string.how_to_press_hands, R.string.how_to_press_ctrl),
        HowTo(R.drawable.ic_open_with, R.string.how_to_move, R.string.how_to_move_hands, R.string.how_to_move_ctrl),
        HowTo(R.drawable.ic_pan_tool, R.string.how_to_call, R.string.how_to_call_hands, R.string.how_to_call_ctrl),
        HowTo(R.drawable.ic_lie, R.string.how_to_still, R.string.how_to_still_hands, R.string.how_to_still_ctrl),
        HowTo(R.drawable.ic_center_focus_strong, R.string.how_to_tour, R.string.how_to_tour_hands, R.string.how_to_tour_ctrl),
        HowTo(R.drawable.ic_my_location, R.string.how_to_recall, R.string.how_to_recall_hands, R.string.how_to_recall_ctrl),
    )

@Composable
private fun HowToPage() {
    Page {
        Txt(str(R.string.how_to_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
        for (h in HOW_TO) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painter = painterResource(h.icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Column(modifier = Modifier.weight(1f)) {
                    Txt(str(h.title), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                    Txt(str(R.string.how_to_hands_line, str(h.hands)), style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                    Txt(str(R.string.how_to_ctrl_line, str(h.controller)), style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- about (S13)

@Composable
private fun AboutPage() {
    Page(spread = true) {
        Txt(str(R.string.about_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
        Txt(str(R.string.version_fmt, BuildConfig.VERSION_NAME), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelSecondary)
        Body(str(R.string.disclaimer), 3, secondary = false)
        Body(str(R.string.about_ack), 3)
        Body(str(R.string.about_audio), 3)
        Body(str(R.string.about_oss), 3)
    }
}

// --------------------------------------------------------------------------------------------- settings (S12)

private enum class SettingsAsk { QUIT, CLEAR }

/**
 * Settings: [Bring back in front] and [Quit app] side by side on the first screen (D44), then language (zh / en),
 * bore, the three switches, volume (three steps + mute, B20) and clear scores. Quitting and clearing ask first, inside
 * this page. Every row ≥ 48 dp; the page fits the window without scrolling.
 */
@Composable
private fun SettingsPage(state: UiState, vm: MriViewModel) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    var ask by remember { mutableStateOf<SettingsAsk?>(null) }
    when (ask) {
        SettingsAsk.QUIT -> return Confirm(R.drawable.ic_logout, str(R.string.quit_confirm), str(R.string.quit_keep), str(R.string.cancel), str(R.string.quit), { ask = null }) {
            MriLog.i("QUIT confirmed")
            exitApp(context, navigator)
        }
        SettingsAsk.CLEAR -> return Confirm(R.drawable.ic_replay, str(R.string.clear_scores) + "?", bestLine(state), str(R.string.cancel), str(R.string.clear_scores), { ask = null }) {
            ask = null
            vm.clearScores()
        }
        null -> Unit
    }
    val s = state.settings
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(str(R.string.settings_title), style = PicoTheme.typography.headlineMedium, color = PicoTheme.colorScheme.labelPrimary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            SecondaryButton(str(R.string.recall), onClick = { vm.recall("settings") }, modifier = Modifier.weight(1f), icon = R.drawable.ic_my_location)
            SecondaryButton(str(R.string.quit_app), onClick = { ask = SettingsAsk.QUIT }, modifier = Modifier.weight(1f), icon = R.drawable.ic_logout)
        }
        SettingRow(str(R.string.language)) {
            SegmentControl(modifier = Modifier.height(MIN_TOUCH)) {
                for (option in AppLanguage.entries) {
                    SegmentItem(
                        selected = s.language == option,
                        onClick = { vm.setLanguage(option) },
                        modifier = Modifier.width(120.dp).height(MIN_TOUCH),
                        title = { Txt(if (option == AppLanguage.ZH) str(R.string.lang_zh) else str(R.string.lang_en)) },
                    )
                }
            }
        }
        SettingRow(str(R.string.bore)) {
            SegmentControl(modifier = Modifier.height(MIN_TOUCH)) {
                for (cm in listOf(60, 70)) {
                    SegmentItem(
                        selected = s.boreCm == cm,
                        onClick = { vm.updateSettings { it.copy(boreCm = cm) } },
                        modifier = Modifier.width(120.dp).height(MIN_TOUCH),
                        title = { Txt(if (cm == 60) str(R.string.bore_60) else str(R.string.bore_70)) },
                    )
                }
            }
        }
        SwitchRow(str(R.string.hint), s.motionHint) { on -> vm.updateSettings { it.copy(motionHint = on) } }
        SwitchRow(str(R.string.coil), s.headCoil) { on -> vm.updateSettings { it.copy(headCoil = on) } }
        SwitchRow(str(R.string.skip), s.skipSlide) { on -> vm.updateSettings { it.copy(skipSlide = on) } }
        SettingRow(str(R.string.volume)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                VolumeSlider(s.volume) { v -> vm.updateSettings { it.copy(volume = v) } }
                Txt(str(R.string.volume_pct, VolumeSteps.percent(s.volume)), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(56.dp))
                MuteButton(s.muted, onClick = { vm.toggleMute() }, height = MIN_TOUCH)
            }
        }
        LinkText(str(R.string.clear_scores), onClick = { ask = SettingsAsk.CLEAR })
    }
}

@Composable
private fun bestLine(state: UiState): String {
    val grades = strArray(R.array.grades)
    val none = str(R.string.best_none)
    val parts = ArrayList<String>()
    for (l in listOf(Level.L1, Level.L2, Level.L3)) {
        val best = state.best[l]
        parts += levelName(l) + " " + (if (best == null) none else str(R.string.best_fmt, grades[Score.grade(best).ordinal], best))
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
        // fixed label width: a SegmentControl takes all the width it is offered and pushed the label out (emulator S12)
        Txt(label, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(150.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { control() }
    }
}

/** A switch inside a 48 dp row the whole of which toggles it (D44: measure the control, not the row). */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cur by rememberUpdatedState(onChange)
    Row(modifier = Modifier.fillMaxWidth().height(MIN_TOUCH).clickable { cur(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Txt(label, style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { on -> cur(on) })
    }
}

/**
 * Volume (B20): three steps (20 / 60 / 100 %), 48 dp tall, 220 dp wide. The SpatialUI Slider keeps the first
 * onValueChange it is given (spatial-ui-skills #41), so the callback goes through rememberUpdatedState; the value is a
 * local Float that snaps on every move and again on release, so a tap or a small drag lands on a step (VolumeSteps).
 */
@Composable
private fun VolumeSlider(value: Float, onSet: (Float) -> Unit) {
    val cur by rememberUpdatedState(onSet)
    val stored by rememberUpdatedState(value)
    val local = remember { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    if (!dragging.value && VolumeSteps.snap(local.floatValue) != VolumeSteps.snap(value)) local.floatValue = value
    Slider(
        value = local.floatValue,
        modifier = Modifier.width(220.dp).height(48.dp),
        onValueChange = { v ->
            dragging.value = true
            local.floatValue = v
            val snapped = VolumeSteps.snap(v)
            if (snapped != VolumeSteps.snap(stored)) cur(snapped)
        },
        valueRange = VolumeSteps.MIN..VolumeSteps.MAX,
        onValueChangeFinished = {
            dragging.value = false
            local.floatValue = VolumeSteps.snap(local.floatValue)
            cur(local.floatValue)
        },
    )
}
