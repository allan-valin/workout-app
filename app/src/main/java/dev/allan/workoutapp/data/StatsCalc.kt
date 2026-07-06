package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode

/**
 * Volume math. Convention (see WORKOUT_PLAN_GENERATOR.md):
 * - volume = effective weight × reps, rep-sets only (timed sets carry no tonnage)
 * - PER_DUMBBELL: entered weight is per hand → ×2
 * - PER_SIDE: effective = barWeight + 2 × per-side weight
 */
object StatsCalc {

    fun effectiveWeightKg(log: SetLog): Double = when (log.weightMode) {
        WeightMode.TOTAL -> log.weightKg
        WeightMode.PER_DUMBBELL -> log.weightKg * 2
        WeightMode.PER_SIDE -> log.barWeightKg + 2 * log.weightKg
    }

    fun volumeKg(log: SetLog): Double =
        if (log.valueUnit == ValueUnit.REPS) effectiveWeightKg(log) * log.value else 0.0

    /** Total volume per primary-muscle id. */
    fun volumePerMuscle(logs: List<SetLog>, primaryMusclesOf: (String) -> List<Int>): Map<Int, Double> {
        val result = mutableMapOf<Int, Double>()
        logs.forEach { log ->
            val volume = volumeKg(log)
            if (volume <= 0) return@forEach
            primaryMusclesOf(log.exerciseId).firstOrNull()?.let { muscle ->
                result[muscle] = (result[muscle] ?: 0.0) + volume
            }
        }
        return result
    }
}
