package dev.allan.workoutapp.ui.session

/**
 * The one place that decides what a session row shows after a (re)build.
 *
 * Sources, in order: what is already ON SCREEN (kept unless it is 0), this session's unlogged
 * draft, the most recent finished-session log for the same slot, the template. The screen comes
 * first because every plan edit mid-session (goal, set type, add/remove/move set) rebuilds all
 * rows, and that rebuild used to overwrite an untyped reps input with the new goal
 * (Allan, 09/10: "correct if 0, otherwise change goal and keep last set").
 *
 * OWNS: this rule only, unit-tested (RebuildKeepsScreenTest). MUST NEVER: read the database or
 * know whether the row is logged — a logged row's numbers live in its log (see updateSet).
 */
fun prefillValue(screen: Int?, draft: Int?, previous: Int?, target: Int): Int =
    screen?.takeIf { it != 0 } ?: draft ?: previous ?: target

fun prefillWeight(screen: Double?, draft: Double?, previous: Double?, template: Double): Double =
    screen?.takeIf { it != 0.0 } ?: draft ?: previous ?: template

/**
 * Cadence shown on an exercise page: the page's OWN cadence, full stop. Phase 34 A6 showed the
 * superset partner's cadence whenever the marker sat on the partner, which read as "both
 * exercises share one cadence; change one and both change" (Allan, 10/10). [partner] is
 * accepted so the call site states the rule explicitly; it is never displayed.
 * Unit-tested (CadenceDisplayTest).
 */
@Suppress("UNUSED_PARAMETER")
fun shownCadence(own: List<SessionSet>, partner: List<SessionSet>?): String =
    own.firstOrNull { it.tempo.isNotBlank() }?.tempo ?: ""
