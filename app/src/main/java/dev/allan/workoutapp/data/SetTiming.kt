package dev.allan.workoutapp.data

/**
 * How long a set counts as "active", and whether it was rushed.
 *
 * Allan, 02/08: a rep set with no timer used to book reps × 3 s. It now books the tempo
 * estimate when a cadence is defined (1-1-1-1 × 10 reps = 40 s, 1-0-1-0 × 10 = 20 s) and
 * a flat 40 s otherwise. Measured durations lose 5 s for getting into position. Moving
 * faster than the cadence is worth a warning; slower is not a problem.
 */
object SetTiming {

    const val DEFAULT_ACTIVE_SECS = 40
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

    /** Expected duration of the whole set from its cadence, null when there is none. */
    fun expectedSecs(reps: Int, tempo: String): Int? =
        tempoSecs(tempo)?.let { it * reps }?.takeIf { it > 0 }

    /** Active seconds to book when nothing was timed. */
    fun defaultActiveSecs(reps: Int, tempo: String): Int =
        expectedSecs(reps, tempo) ?: DEFAULT_ACTIVE_SECS

    /** Active seconds to book from a measured duration. */
    fun measuredActiveSecs(rawSecs: Int): Int =
        (rawSecs - POSITION_SECS).coerceAtLeast(POSITION_SECS)

    fun pace(actualSecs: Int, expectedSecs: Int): Pace =
        if (actualSecs < expectedSecs * (1 - FAST_TOLERANCE)) Pace.FAST else Pace.ON_TEMPO
}
