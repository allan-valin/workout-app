package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.SetLog

/**
 * MET-based energy estimate for a finished session (Allan approved 2026-07-25).
 *
 * kcal/min = MET × 3.5 × bodyweightKg / 200 (ACSM). Booked ACTIVE seconds are charged at
 * the exercise's MET; rest seconds at [REST_MET]. Idle time is deliberately ignored —
 * standing around between exercises is not training.
 *
 * Accuracy: cardio METs come from the Compendium of Physical Activities and are decent;
 * resistance-training METs are a single-number approximation of a very spiky effort, so the
 * total is ±20–30 %. The UI must show it as an estimate ("~"), never as a measurement.
 */
object CalorieCalc {

    /** Seated/standing recovery between sets. */
    const val REST_MET = 1.5

    // Compendium 2011 codes, rounded: vigorous resistance training 6.0, light/moderate 3.5,
    // stretching/mobility 2.3, elliptical 5.0, treadmill/bike see cardioMet().
    private const val MET_RESISTANCE_HEAVY = 6.0
    private const val MET_RESISTANCE_LIGHT = 3.5
    private const val MET_MOBILITY = 2.3
    private const val MET_CARDIO_DEFAULT = 5.0

    /**
     * MET for one logged set. Cardio exercises use a machine-agnostic default; weighted work
     * counts as vigorous resistance training, unloaded work as light; timed sets with no load
     * (stretches, SMR, mobility drills) are charged at the mobility rate.
     */
    fun met(exercise: Exercise?, log: SetLog): Double {
        if (exercise?.isCardio == true) return MET_CARDIO_DEFAULT
        val loaded = log.weightKg > 0.0
        return when {
            loaded -> MET_RESISTANCE_HEAVY
            log.valueUnit == dev.allan.workoutapp.data.db.ValueUnit.SECS -> MET_MOBILITY
            else -> MET_RESISTANCE_LIGHT
        }
    }

    /** kcal for [secs] seconds at [met] for a [bodyweightKg] person. */
    fun kcal(met: Double, bodyweightKg: Double, secs: Int): Double =
        met * 3.5 * bodyweightKg / 200.0 * (secs / 60.0)

    /**
     * Session total. [activeFallbackSecs] spreads the session's booked active time over the
     * logs that have no per-set activeSecs (older logs, or sets logged without a timer).
     */
    fun sessionKcal(
        logs: List<SetLog>,
        exercises: Map<String, Exercise?>,
        bodyweightKg: Double,
        restSecs: Int,
        activeFallbackSecs: Int = 0,
    ): Double {
        val logged = logs.sumOf { it.activeSecs ?: 0 }
        val missing = logs.count { it.activeSecs == null }
        val perMissing = if (missing > 0) (activeFallbackSecs - logged).coerceAtLeast(0) / missing else 0
        val work = logs.sumOf { log ->
            val secs = log.activeSecs ?: perMissing
            kcal(met(exercises[log.exerciseId], log), bodyweightKg, secs)
        }
        return work + kcal(REST_MET, bodyweightKg, restSecs)
    }
}
