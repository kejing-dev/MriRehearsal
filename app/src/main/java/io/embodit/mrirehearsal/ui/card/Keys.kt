package io.embodit.mrirehearsal.ui.card

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.embodit.mrirehearsal.platform.BackKeys
import io.embodit.mrirehearsal.platform.MriLog

/**
 * Factory profile D60 (QA #5 q5-2): controller keys never press a window button. Controls cannot take focus (so ENTER /
 * DPAD_CENTER have nothing to click, and a tapped tab keeps no focus that could swallow BACK), and the window eats
 * A / X / Y / ENTER / DPAD_CENTER before they reach anything (logged, unassigned on this app). BACK / B go to the one
 * back handler (BackKeys → MriShared.onBack).
 */
internal val PAD_KEYS = setOf(Key.ButtonA, Key.ButtonB, Key.ButtonX, Key.ButtonY, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

internal fun Modifier.eatPadKeys(where: String): Modifier =
    onPreviewKeyEvent { e ->
        if (BackKeys.route(e.nativeKeyEvent, "compose:$where")) {
            true
        } else if (e.key in PAD_KEYS) {
            MriLog.i("KEY eaten $where ${e.key}")
            true
        } else {
            false
        }
    }

/** Every window control: never focusable (D60: [Quit app], [Leave the scan] and the like cannot be pressed by a key). */
internal val NoFocus: Modifier = Modifier.focusProperties { canFocus = false }
