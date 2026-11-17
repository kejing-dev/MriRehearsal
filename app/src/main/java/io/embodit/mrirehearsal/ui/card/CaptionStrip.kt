package io.embodit.mrirehearsal.ui.card

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.domain.Caption
import io.embodit.mrirehearsal.domain.Motion
import io.embodit.mrirehearsal.domain.NextLoudness
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.ui.MriViewModel
import io.embodit.mrirehearsal.ui.theme.MriColors
import io.embodit.mrirehearsal.ui.theme.str
import kotlinx.coroutines.delay

/**
 * The in-bore readout (420 × 108 dp; scale 0.5 on the bore's roof lying, 1.0 on the bore's rim sitting). It is a reading
 * fixed on the scanner, not a panel: the main window carries the same lines, but in the bore the user faces the bore
 * and the window is 80° to the side / above the face (native-window-move §3.6: readings on the 3D body stay on it). Top
 * row: sequence and progress, the stillness ring, mute with its label (D15). Bottom row: the technologist's caption at
 * 24 dp, one line; a caption too long for one line is two parts shown 3 s each.
 */
@Composable
fun CaptionStrip(state: UiState, vm: MriViewModel) {
    CompositionLocalProvider(LocalPanelName provides "strip") {
        GlassRoot {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
                // the mute button is the only control on the strip; its 16 dp gap to the ring (profile B31)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                    Txt(progressText(state), style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1f))
                    StillRing(state, 24.dp)
                    MuteButton(state.settings.muted, onClick = { vm.toggleMute() }, height = MIN_TOUCH)
                }
                Spacer(Modifier.height(2.dp))
                CaptionLine(captionText(state.caption), PicoTheme.typography.bodyLarge.copy(fontSize = 24.sp, lineHeight = 30.sp), 1)
            }
        }
    }
}

@Composable
fun progressText(state: UiState): String {
    val seg = state.clock.current
    return when (state.screen) {
        Screen.S06, Screen.S07 -> str(R.string.progress_fmt, seqName(seg?.type), clockText(state.clock.elapsed), clockText((seg?.seconds ?: 0).toFloat()))
        Screen.S05 -> seqName(seg?.type)
        Screen.S04, Screen.S10 -> str(R.string.run_sliding)
        else -> levelName(state.level)
    }
}

/** The stillness ring: white / amber (minor) / red (major); breathing 4 s in, 4 s out in S05 and S07 (only a rhythm). */
@Composable
fun StillRing(state: UiState, size: Dp) {
    val color =
        when (state.motion) {
            Motion.STILL -> MriColors.Still
            Motion.MINOR -> MriColors.Minor
            Motion.MAJOR -> MriColors.Major
        }
    val breathe = state.screen == Screen.S05 || state.screen == Screen.S07
    val alpha =
        if (breathe) {
            val t = rememberInfiniteTransition(label = "breath")
            val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse), label = "breathA")
            a
        } else {
            1f
        }
    // a dark rim under the ring keeps the white "still" ring at >= 3:1 on light glass too
    val rim = PicoTheme.colorScheme.labelPrimary
    val lightRim = rim.luminance() < 0.5f
    Canvas(modifier = Modifier.size(size)) {
        val stroke = size.toPx() / 8f
        val r = this.size.minDimension / 2 - stroke / 2 - 1.dp.toPx()
        if (lightRim) drawCircle(rim.copy(alpha = 0.6f), radius = r, style = Stroke(width = stroke + 2.dp.toPx()))
        drawCircle(color.copy(alpha = alpha), radius = r, style = Stroke(width = stroke))
    }
}

/** A caption; "a|b" is two parts shown 3 s each on one line ([lines] = 1), or both at once when it may wrap. */
@Composable
fun CaptionLine(text: String, style: TextStyle, lines: Int, modifier: Modifier = Modifier.fillMaxWidth(), align: TextAlign = TextAlign.Center) {
    val parts = remember(text) { text.split('|').map { it.trim() }.filter { it.isNotEmpty() } }
    if (lines > 1) {
        Txt(parts.joinToString(if (parts.any { it.any { c -> c.code > 0x2E80 } }) "" else " "), style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = lines, textAlign = align, modifier = modifier)
        return
    }
    var index by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(parts) {
        index = 0
        if (parts.size > 1) {
            while (true) {
                delay(ROTATE_MS)
                index = (index + 1) % parts.size
            }
        }
    }
    Txt(parts.getOrNull(index) ?: "", style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, textAlign = align, modifier = modifier)
}

@Composable
fun captionText(c: Caption): String =
    when (c) {
        Caption.TableMoves -> str(R.string.cap_table)
        is Caption.Settle ->
            when (c.count) {
                3 -> str(R.string.cap_settle_3)
                2 -> str(R.string.cap_settle_2)
                else -> str(R.string.cap_settle_1)
            }
        is Caption.SeqStart -> str(R.string.cap_seq_start, str(R.string.dur_seconds, c.seconds))
        Caption.Earplugs -> str(R.string.cap_earplug)
        Caption.HoldStill -> str(R.string.cap_hold)
        is Caption.Gap -> {
            val base =
                when (c.next) {
                    NextLoudness.LOUDER -> str(R.string.cap_gap_louder)
                    NextLoudness.QUIETER -> str(R.string.cap_gap_quieter)
                    NextLoudness.SAME -> str(R.string.cap_gap_same)
                }
            if (c.last) base + "|" + str(R.string.cap_last) else base
        }
        Caption.Done -> str(R.string.cap_done)
        Caption.Called -> str(R.string.cap_called)
        Caption.SeePanel -> str(R.string.cap_see_panel)
        Caption.Lost -> str(R.string.cap_lost)
        Caption.None -> ""
    }

private const val ROTATE_MS = 3000L
