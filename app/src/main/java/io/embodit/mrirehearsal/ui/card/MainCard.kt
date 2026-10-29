package io.embodit.mrirehearsal.ui.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.mrirehearsal.BuildConfig
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.domain.AppLanguage
import io.embodit.mrirehearsal.domain.Grade
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.Score
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.ui.MriViewModel
import io.embodit.mrirehearsal.ui.theme.MriColors
import io.embodit.mrirehearsal.ui.theme.str
import io.embodit.mrirehearsal.ui.theme.strArray
import kotlin.math.roundToInt

/**
 * The main card (480 × 400 dp, scale 0.8 → 0.31 m wide; the settings version 480 × 440): one card, its content
 * replaced by state (R1-6) — S00 loading, S01 ready, S02 scan room, S09 pause, S11 result, S13 about. Settings are
 * [SettingsCard]. At most one primary button, four list buttons and two links at a time.
 */
@Composable
fun MainCard(state: UiState, vm: MriViewModel) {
    GlassRoot {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.screen) {
                Screen.S00 -> Loading()
                Screen.S01 -> ReadyCard(state, vm)
                Screen.S02 -> RoomCard(state, vm)
                Screen.S09 -> PauseCard(state, vm)
                Screen.S11 -> ResultCard(state, vm)
                Screen.S13 -> AboutCard(vm)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Loading() {
    Text(str(R.string.app_title), style = PicoTheme.typography.headlineMedium)
    Text(str(R.string.loading), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
}

/** Title row with the settings (and mute) buttons at the top right, each with its label. */
@Composable
private fun TitleRow(title: String, state: UiState, vm: MriViewModel, mute: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = PicoTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.weight(1f))
        if (mute) MuteButton(state.settings.muted, onClick = { vm.toggleMute() })
        LabeledIconButton(R.drawable.ic_settings, str(R.string.settings), onClick = { vm.openSettings() })
    }
}

// ------------------------------------------------------------------------------------------- S01

@Composable
private fun ColumnScope.ReadyCard(state: UiState, vm: MriViewModel) {
    TitleRow(str(R.string.app_title), state, vm, mute = false)
    Text(str(R.string.disclaimer), style = PicoTheme.typography.bodyMediumMultiline, color = PicoTheme.colorScheme.labelSecondary)
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val lie = state.posture == Posture.LIE
        Icon(painter = painterResource(if (lie) R.drawable.ic_lie else R.drawable.ic_sit), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
        if (!state.headFound) {
            Text(str(R.string.posture_finding), style = PicoTheme.typography.titleMedium, maxLines = 1)
        } else {
            Text(str(R.string.posture_label) + " " + if (lie) str(R.string.posture_lie) else str(R.string.posture_sit), style = PicoTheme.typography.titleMedium, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        LinkText(if (lie) str(R.string.link_sit) else str(R.string.link_lie), onClick = { vm.setPostureManual(if (lie) Posture.SIT else Posture.LIE) })
    }
    Spacer(Modifier.weight(1f))
    PrimaryButton(str(R.string.enter), onClick = { vm.enterRoom() }, modifier = Modifier.fillMaxWidth())
}

// ------------------------------------------------------------------------------------------- S02

@Composable
private fun RoomCard(state: UiState, vm: MriViewModel) {
    TitleRow(str(R.string.room_title), state, vm, mute = true)
    val grades = strArray(R.array.grades)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (level in Level.entries) {
            val best = state.best[level]
            val right =
                when {
                    level == Level.L0 -> str(R.string.tour_stops, state.tourStops.size)
                    best == null -> str(R.string.best_none)
                    else -> str(R.string.best_fmt, grades[Score.grade(best).ordinal], best)
                }
            Button(onClick = { vm.selectLevel(level) }, modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(levelName(level), maxLines = 1, modifier = Modifier.weight(1f))
                    // the button's own content colour: labelSecondary on the dark fill read 1.5:1 (emulator shot)
                    Text(right, maxLines = 1)
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LinkText(str(R.string.recenter), onClick = { vm.recenter() })
        if (state.lost) StatusLine(str(R.string.lost_short), MriColors.Minor)
        Spacer(Modifier.weight(1f))
        // D21: one line telling how to move the card (the grab bar under it)
        Text(str(R.string.drag_hint), style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------- S09

@Composable
private fun ColumnScope.PauseCard(state: UiState, vm: MriViewModel) {
    Text(str(R.string.called), style = PicoTheme.typography.headlineMedium, maxLines = 1)
    Text(str(R.string.called_body), style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    if (state.level.isScan) {
        Text(str(R.string.done_of, clockText(state.clock.levelSec), clockText(state.level.totalSec.toFloat())), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
    if (state.lost) StatusLine(str(R.string.lost_pause), MriColors.Minor)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(str(R.string.resume), onClick = { vm.resume() }, modifier = Modifier.weight(1f))
        SecondaryButton(str(R.string.finish), onClick = { vm.finishFromPause() }, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------------------------------- S11

@Composable
private fun ColumnScope.ResultCard(state: UiState, vm: MriViewModel) {
    val result = state.result ?: return
    val grades = strArray(R.array.grades)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(str(R.string.result), style = PicoTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Text(levelName(result.level), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("${result.score}", style = PicoTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold))
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            val gradeColor =
                when (result.grade) {
                    Grade.STEADY -> MriColors.GradeGood
                    Grade.GOOD -> MriColors.Accent
                    Grade.RETRY -> MriColors.GradeRetry
                }
            StatusLine(grades[result.grade.ordinal], gradeColor, style = PicoTheme.typography.titleLarge)
            Text(str(R.string.score_label) + " · " + str(R.string.events_fmt, result.events, result.minorSec.roundToInt()), style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
    }
    Timeline(state)
    Text(str(R.string.legend), style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    Text(if (result.events > 0) str(R.string.verdict_redo) else str(R.string.verdict_ok), style = PicoTheme.typography.bodyMedium, maxLines = 2)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val last = result.level.next() == null
        if (last) {
            PrimaryButton(str(R.string.retry), onClick = { vm.retry() })
        } else {
            PrimaryButton(str(R.string.next_level), onClick = { vm.nextLevel() })
            SecondaryButton(str(R.string.retry), onClick = { vm.retry() })
        }
        Spacer(Modifier.weight(1f))
        LinkText(str(R.string.back_room), onClick = { vm.backToRoom() })
    }
}

/**
 * The run as a line: total length = the level's seconds; gaps grey (not scored), amber spans = small movement,
 * red dots = big movements (R2-5). Domain visualisation, drawn on a Canvas.
 */
@Composable
private fun Timeline(state: UiState) {
    val result = state.result ?: return
    val level = result.level
    val total = level.totalSec.toFloat().coerceAtLeast(1f)
    val track = PicoTheme.colorScheme.fillSecondary
    val gap = PicoTheme.colorScheme.fillTertiary
    Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
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

// ------------------------------------------------------------------------------------------- S12 / S13

/** Settings replace the card's content (R1-6): 480 × 440 dp. */
@Composable
fun SettingsCard(state: UiState, vm: MriViewModel) {
    val s = state.settings
    GlassRoot {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.settings_title), style = PicoTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
                SecondaryButton(str(R.string.back), onClick = { vm.closeSettings() })
            }
            SettingRow(str(R.string.language)) {
                SegmentControl {
                    for (option in AppLanguage.entries) {
                        SegmentItem(
                            selected = s.language == option,
                            onClick = { vm.setLanguage(option) },
                            modifier = Modifier.width(110.dp),
                            title = { Text(if (option == AppLanguage.ZH) str(R.string.lang_zh) else str(R.string.lang_en), maxLines = 1) },
                        )
                    }
                }
            }
            SettingRow(str(R.string.bore)) {
                SegmentControl {
                    for (cm in listOf(60, 70)) {
                        SegmentItem(
                            selected = s.boreCm == cm,
                            onClick = { vm.updateSettings { it.copy(boreCm = cm) } },
                            modifier = Modifier.width(110.dp),
                            title = { Text(if (cm == 60) str(R.string.bore_60) else str(R.string.bore_70), maxLines = 1) },
                        )
                    }
                }
            }
            SettingRow(str(R.string.hint)) { Switch(checked = s.motionHint, onCheckedChange = { on -> vm.updateSettings { it.copy(motionHint = on) } }) }
            SettingRow(str(R.string.coil)) { Switch(checked = s.headCoil, onCheckedChange = { on -> vm.updateSettings { it.copy(headCoil = on) } }) }
            SettingRow(str(R.string.skip)) { Switch(checked = s.skipSlide, onCheckedChange = { on -> vm.updateSettings { it.copy(skipSlide = on) } }) }
            SettingRow(str(R.string.volume)) {
                Slider(value = s.volume, onValueChange = { v -> vm.updateSettings { it.copy(volume = (v * 20f).roundToInt() / 20f) } }, modifier = Modifier.width(220.dp), valueRange = 0f..1f)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LinkText(str(R.string.about), onClick = { vm.openAbout() })
                LinkText(str(R.string.clear_scores), onClick = { vm.clearScores() })
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH), verticalAlignment = Alignment.CenterVertically) {
        // fixed label width: a SegmentControl takes all the width it is offered and pushed the label out (emulator S12)
        Text(label, style = PicoTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.width(150.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { control() }
    }
}

@Composable
private fun AboutCard(vm: MriViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(str(R.string.about_title), style = PicoTheme.typography.headlineMedium, modifier = Modifier.weight(1f), maxLines = 1)
        SecondaryButton(str(R.string.back), onClick = { vm.closeAbout() })
    }
    Text(str(R.string.version_fmt, BuildConfig.VERSION_NAME), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary)
    Text(str(R.string.disclaimer), style = PicoTheme.typography.bodyMediumMultiline)
    Text(str(R.string.about_ack), style = PicoTheme.typography.bodyMediumMultiline, color = PicoTheme.colorScheme.labelSecondary)
    Text(str(R.string.about_audio), style = PicoTheme.typography.bodyMediumMultiline, color = PicoTheme.colorScheme.labelSecondary)
    Text(str(R.string.about_oss), style = PicoTheme.typography.bodyMediumMultiline.copy(fontSize = 13.sp), color = PicoTheme.colorScheme.labelSecondary)
}
