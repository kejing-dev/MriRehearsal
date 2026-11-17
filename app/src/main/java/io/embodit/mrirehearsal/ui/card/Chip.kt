package io.embodit.mrirehearsal.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.ui.MriShared
import io.embodit.mrirehearsal.ui.theme.str

/**
 * The Stage's "open panel" chip (native-window-move §3.3 / §7 item 8): while the main window is closed (its ×) or
 * minimised it offers to open it again; while an open waits for the head (D9 level gate) it says "look straight ahead".
 * Placed once per appearance in front of the head (MriScene), never dragged, never followed.
 */
@Composable
fun OpenPanelChip(lying: Boolean) {
    CompositionLocalProvider(LocalPanelName provides "chip") {
        GlassRoot {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (MriShared.levelHint) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val text = str(if (lying) R.string.chip_level_lie else R.string.chip_level)
                        for (part in text.split('|')) Txt(part.trim(), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    PrimaryButton(str(R.string.chip_open), onClick = { MriShared.askOpen("chip") }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_open_in_new)
                }
            }
        }
    }
}
