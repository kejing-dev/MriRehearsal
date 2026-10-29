package io.embodit.mrirehearsal

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.mrirehearsal.ui.MriScreen
import io.embodit.mrirehearsal.ui.theme.MriTheme

/**
 * App entry. One Progressive Stage (AndroidManifest: `pico.spatial.stage.style=2`, immersion 100 / 40 / 100) holds the
 * whole app; there is no WindowContainer, no volume, no Shared Space version (design package: archetype C).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            MriTheme {
                MriScreen()
            }
        }
    }
