package io.embodit.mrirehearsal.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.SeqType
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.ui.theme.str
import kotlin.math.roundToInt

internal val CardShape = RoundedCornerShape(28.dp)

/** Minimum height of every control (profile B19); main buttons are [BUTTON_H] (profile B31). */
internal val MIN_TOUCH = 48.dp
internal val BUTTON_H = 64.dp

/** Gap between buttons side by side (profile B31, factory gate button-gap). */
internal val BUTTON_GAP = 16.dp

/** Which surface a text is on, for the overflow log (`OVERFLOW <panel>/<text>`, D13). */
val LocalPanelName = staticCompositionLocalOf { "?" }

/** Glass root of the Stage's own small panels: clipped to its shape, the system's default glass (no own background). */
@Composable
fun GlassRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().clip(CardShape).backgroundMaterial(enable = true, style = Material.Regular)) { content() }
}

/**
 * A text with the overflow check (D13): a fixed-line text that does not fit logs `OVERFLOW <panel>/<text>` once, so QA
 * runs every state in English and reads the log instead of hunting for cut words. Never an ellipsis.
 */
@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
    val panel = LocalPanelName.current
    Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines, textAlign = textAlign, onTextLayout = fits(panel, text))
}

fun fits(panel: String, text: String): (TextLayoutResult) -> Unit = { r -> if (r.hasVisualOverflow && OverflowSeen.add("$panel/$text")) MriLog.i("OVERFLOW $panel/${text.take(48)}") }

private object OverflowSeen {
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    fun add(key: String): Boolean = seen.add(key)
}

/** The one emphasised action of a page: PicoTheme interaction role with white text. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null, height: Dp = BUTTON_H, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = Color.White),
    ) {
        ButtonLabel(label, icon)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null, height: Dp = BUTTON_H, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = height), enabled = enabled) { ButtonLabel(label, icon) }
}

@Composable
private fun ButtonLabel(label: String, icon: Int?) {
    // gate:allow button-gap the gap between the icon and the label inside one button, not between two buttons
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Txt(label, style = PicoTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** An icon button that always carries its label (profile B19: no bare icons). */
@Composable
fun LabeledIconButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = SecondaryButton(label, onClick, modifier, icon)

@Composable
fun LinkText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Link(onClick = onClick, modifier = modifier.heightIn(min = MIN_TOUCH)) { Txt(label) }
}

/** A status line: a small coloured dot carries the colour, the text stays labelPrimary (D13: coloured text failed on light glass). */
@Composable
fun StatusLine(text: String, dot: Color, modifier: Modifier = Modifier, style: TextStyle = PicoTheme.typography.labelMedium, maxLines: Int = 1, dotSize: Dp = 14.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // the dot keeps >= 3:1 on any glass: a labelPrimary rim around the status colour
        val rim = PicoTheme.colorScheme.labelPrimary
        androidx.compose.foundation.Canvas(modifier = Modifier.size(dotSize)) {
            drawCircle(rim)
            drawCircle(dot, radius = size.minDimension / 2 - 2.dp.toPx())
        }
        Txt(text, style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines)
    }
}

/** Mute (D15, B31 exception: always on the main page; also on the in-bore readout). Label changes with the state. */
@Composable
fun MuteButton(muted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = BUTTON_H) =
    SecondaryButton(if (muted) str(R.string.unmute) else str(R.string.mute), onClick, modifier, if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, height)

/** m:ss */
fun clockText(seconds: Float): String {
    val s = seconds.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun seqName(type: SeqType?): String =
    when (type) {
        SeqType.LOC -> str(R.string.seq_loc)
        SeqType.T1 -> str(R.string.seq_t1)
        SeqType.T2 -> str(R.string.seq_t2)
        SeqType.EPI -> str(R.string.seq_epi)
        SeqType.GAP -> str(R.string.seq_gap)
        null -> str(R.string.seq_table)
    }

@Composable
fun levelName(level: Level): String {
    val names = io.embodit.mrirehearsal.ui.theme.strArray(R.array.levels)
    return names[level.ordinal.coerceIn(0, names.lastIndex)]
}
