package io.embodit.mrirehearsal.ui.card

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.ui.MriViewModel
import io.embodit.mrirehearsal.ui.theme.MriColors
import io.embodit.mrirehearsal.ui.theme.str
import io.embodit.mrirehearsal.ui.theme.strArray

/**
 * The tour tag (440 × 160 dp, scale 0.8; 440 so that the English Previous / Next (n/5) / Back to room row fits, QA q1-1), at the main card's place with a leader line to the object (R2-9): name,
 * two lines, previous / next (n/total) and "back to the room" on every stop (R1-15).
 */
@Composable
fun TourTag(state: UiState, vm: MriViewModel) {
    val names = strArray(R.array.tour_names)
    val bodies = strArray(R.array.tour_bodies)
    val stops = state.tourStops
    val i = state.tourIndex.coerceIn(0, stops.lastIndex)
    val stop = stops[i]
    val demo = state.tourDemoUntilMs != 0L && SystemClock.elapsedRealtime() < state.tourDemoUntilMs
    GlassRoot {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(names[stop.ordinal], style = PicoTheme.typography.titleMedium, maxLines = 1)
            if (demo) {
                StatusLine(str(R.string.tour_demo), MriColors.Accent, style = PicoTheme.typography.bodyMediumMultiline, maxLines = 2)
            } else {
                Text(bodies[stop.ordinal].format(state.settings.boreCm) + if (state.tourBehind) " " + str(R.string.tour_behind) else "", style = PicoTheme.typography.bodyMediumMultiline, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i > 0) SecondaryButton(str(R.string.prev), onClick = { vm.tourStep(-1) })
                if (i < stops.lastIndex) PrimaryButton(str(R.string.next_fmt, i + 2, stops.size), onClick = { vm.tourStep(1) })
                Spacer(Modifier.weight(1f))
                LinkText(str(R.string.back_room), onClick = { vm.tourBack() })
            }
        }
    }
}
