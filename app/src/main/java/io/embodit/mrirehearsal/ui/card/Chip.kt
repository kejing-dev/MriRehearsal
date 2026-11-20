package io.embodit.mrirehearsal.ui.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.embodit.mrirehearsal.R
import io.embodit.mrirehearsal.ui.MriShared
import io.embodit.mrirehearsal.ui.theme.str

/**
 * The Stage's "open panel" chip (native-window-move §3.3 / §7 item 8): while the main window is closed (its ×) or
 * minimised, a button that opens it again straight away (profile D10: a button opens the panel, the system window is
 * dragged with its own controls). Placed once per appearance in front of the head (MriScene), never dragged, never
 * followed.
 */
@Composable
fun OpenPanelChip() {
    CompositionLocalProvider(LocalPanelName provides "chip") {
        GlassRoot {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                PrimaryButton(str(R.string.chip_open), onClick = { MriShared.askOpen("chip") }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_open_in_new)
            }
        }
    }
}
