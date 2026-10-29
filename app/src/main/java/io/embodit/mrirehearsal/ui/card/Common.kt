package io.embodit.mrirehearsal.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import io.embodit.mrirehearsal.ui.theme.str
import kotlin.math.roundToInt

internal val CardShape = RoundedCornerShape(28.dp)

/** Minimum height of every control (profile B19). */
internal val MIN_TOUCH = 48.dp

/** Glass root of every panel: clipped to its shape, the system's default glass (no own background). */
@Composable
fun GlassRoot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().clip(CardShape).backgroundMaterial(enable = true, style = Material.Regular)) { content() }
}

/** The one emphasised action of a card: PicoTheme interaction role with white text. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MIN_TOUCH),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = Color.White),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = MIN_TOUCH)) { Text(label, maxLines = 1) }
}

/** An icon button that always carries its label (profile B19: no bare icons). */
@Composable
fun LabeledIconButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = MIN_TOUCH)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, maxLines = 1, style = PicoTheme.typography.labelLarge)
        }
    }
}

@Composable
fun LinkText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Link(onClick = onClick, modifier = modifier.heightIn(min = MIN_TOUCH)) { Text(label, maxLines = 1) }
}

/** A status line: a small coloured dot carries the colour, the text stays labelPrimary (D13: coloured text failed on light glass). */
@Composable
fun StatusLine(text: String, dot: Color, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = PicoTheme.typography.labelMedium, maxLines: Int = 1) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // the dot keeps >= 3:1 on any glass: a labelPrimary rim around the status colour
        val rim = PicoTheme.colorScheme.labelPrimary
        androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
            drawCircle(rim)
            drawCircle(dot, radius = size.minDimension / 2 - 2.dp.toPx())
        }
        Text(text, style = style, color = PicoTheme.colorScheme.labelPrimary, maxLines = maxLines)
    }
}

@Composable
fun MuteButton(muted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LabeledIconButton(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume, if (muted) str(R.string.unmute) else str(R.string.mute), onClick, modifier)
}

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
