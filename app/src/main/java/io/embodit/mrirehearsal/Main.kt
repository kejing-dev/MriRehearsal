package io.embodit.mrirehearsal

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.mrirehearsal.domain.WindowLayout
import io.embodit.mrirehearsal.ui.MriScreen
import io.embodit.mrirehearsal.ui.MriShared
import io.embodit.mrirehearsal.ui.theme.MriTheme
import io.embodit.mrirehearsal.ui.window.MriWindow

/**
 * App entry. One Progressive Stage (AndroidManifest: `pico.spatial.stage.style=2`, immersion 100 / 40 / 100) holds the
 * scan room; every 2D page is in one Planar system window (v0.1.1, factory native-window-move): native TabBar, the
 * system's move / close controls. The Stage opens the window straight away, whatever the head pitch (profile D9 / D10,
 * QA #5 q5-1), and lays the room out clear of it. No volume, no Shared Space version (design package: archetype C).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            MriTheme {
                MriScreen()
            }
        }
        WindowContainer(MriShared.WINDOW_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = WindowLayout.W_DP.dp, height = WindowLayout.H_DP.dp)) {
            MriTheme {
                MriWindow()
            }
        }
    }
