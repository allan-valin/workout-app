package dev.allan.workoutapp.data

/**
 * How long a set counts as "active", and whether it was rushed.
 *
 * Allan, 02/08: a rep set with no timer used to book reps × 3 s. Allan, 02/10: the flat
 * 40 s was "based on 10 reps" — the estimate now scales: [PER_REP_SECS] × reps without a
 * cadence, and with one the REALISTIC cadence × reps, where every 0 phase counts as a 1 s
 * pause ("1-1-3-0 or 2-0-4-0 and 20 reps, chances are I'm doing 1-2 s pauses instead of 0").
 * The nominal cadence ([expectedSecs]) is kept for the too-fast warning only, so the slack
 * never flags a set as rushed. Measured durations lose 5 s for getting into position.
 *
 * Booking decision (SessionViewModel.logSet, rep sets):
 *  - "forgot?" ticked in the timer panel → the estimate, whatever any timer says;
 *  - stopwatch ran → its reading as-is ("timer = 6 min means I was active 6 min");
 *  - no stopwatch → the gap since the rest ended, via [bookFromGap]: measured up to
 *    [capSecs] (5 min, or twice the estimate when that is longer — the trainer's "one set"
 *    that is really eight movements takes 3–5 min), the cap itself beyond it;
 *  - no gap either → the estimate.
 *
 * OWNS: the pure timing rules (defaults, measured correction, pace). Unit-tested; nothing
 * here reads a clock or the database. MUST NEVER: be bypassed by a literal number in the
 * view model — every booked second comes from one of these functions (30/09 T1 was a
 * literal 40 in SessionManager).
 */
object SetTiming {

    /** Seconds a rep takes without a cadence: the old flat 40 s ÷ 10 reps. */
    const val PER_REP_SECS = 4
    /** A 0 (or X) phase is really a short pause when counting work time. */
    const val PAUSE_PHASE_SECS = 1
    /** Floor of the forgot-to-log cap for an untimed gap. */
    const val MIN_CAP_SECS = 300
    /** The cap grows with long sets: this many times the estimate. */
    const val CAP_FACTOR = 2
    /** Getting under the bar / into the machine is not work time. */
    const val POSITION_SECS = 5
    /** A set logged this soon after a measured one was covered by that measurement. */
    const val SHARE_WINDOW_MS = 20_000L
    /** Under the estimate by more than this fraction = rushed. */
    const val FAST_TOLERANCE = 0.15

    enum class Pace { FAST, ON_TEMPO }

    /** Seconds one rep takes at this cadence, e.g. "3-1-2-1" → 7. Null when unusable. */
    fun tempoSecs(tempo: String): Int? {
        val parts = tempo.trim().split('-', ' ').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        // "X" means explosive/no hold — counted as zero, not as a parse failure.
        val secs = parts.map { part ->
            if (part.equals("X", ignoreCase = true)) 0 else part.toIntOrNull() ?: return null
        }
        return secs.sum()
    }

    /**
     * Seconds one rep really takes at this cadence: like [tempoSecs] but every 0/X phase is
     * booked as a [PAUSE_PHASE_SECS] pause. "1-1-3-0" → 6, "2-0-4-0" → 8. Null when unusable.
     */
    fun realisticTempoSecs(tempo: String): Int? {
        val parts = tempo.trim().split('-', ' ').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return parts.sumOf { part ->
            val n = if (part.equals("X", ignoreCase = true)) 0 else part.toIntOrNull() ?: return null
            if (n <= 0) PAUSE_PHASE_SECS else n
        }
    }

    /** NOMINAL duration of the whole set from its cadence (pace warning), null without one. */
    fun expectedSecs(reps: Int, tempo: String): Int? =
        tempoSecs(tempo)?.let { it * reps }?.takeIf { it > 0 }

    /** Active seconds to book when nothing was measured: realistic cadence, else 4 s a rep. */
    fun defaultActiveSecs(reps: Int, tempo: String): Int =
        (realisticTempoSecs(tempo) ?: PER_REP_SECS) * reps.coerceAtLeast(0)

    /** Longest untimed gap still believed to be the set itself. */
    fun capSecs(reps: Int, tempo: String): Int =
        maxOf(MIN_CAP_SECS, CAP_FACTOR * defaultActiveSecs(reps, tempo))

    /**
     * Active seconds for a set nobody timed, from the gap since the rest ended: measured
     * (minus positioning) while within [capSecs], the cap itself beyond it — Allan, 02/10:
     * "5 minutes sound about right for when I forget, but then log the calculated cap".
     */
    fun bookFromGap(gapSecs: Int, reps: Int, tempo: String): Int {
        val cap = capSecs(reps, tempo)
        return if (gapSecs > cap) cap else measuredActiveSecs(gapSecs)
    }

    /** Active seconds to book from a measured duration. */
    fun measuredActiveSecs(rawSecs: Int): Int =
        (rawSecs - POSITION_SECS).coerceAtLeast(POSITION_SECS)

    fun pace(actualSecs: Int, expectedSecs: Int): Pace =
        if (actualSecs < expectedSecs * (1 - FAST_TOLERANCE)) Pace.FAST else Pace.ON_TEMPO
}
