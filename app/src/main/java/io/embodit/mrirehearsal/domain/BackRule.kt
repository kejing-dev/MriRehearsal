package io.embodit.mrirehearsal.domain

/**
 * What BACK / controller B does (factory profile D60 补, QA #5 q5-2). Never exit, never go to the background: the main
 * window closed (its ×, minimised, never opened) = open it again; open, or an open / recall still on its way, or no
 * Stage to open it from = nothing (logged). Pure Kotlin, unit-tested (BackKeysTest).
 */
object BackRule {
    enum class Action { REOPEN, EATEN_OPEN, EATEN_OPENING, EATEN_NO_STAGE }

    fun decide(windowOpen: Boolean, openInFlight: Boolean, hasStage: Boolean): Action =
        when {
            windowOpen -> Action.EATEN_OPEN
            openInFlight -> Action.EATEN_OPENING
            !hasStage -> Action.EATEN_NO_STAGE
            else -> Action.REOPEN
        }
}
