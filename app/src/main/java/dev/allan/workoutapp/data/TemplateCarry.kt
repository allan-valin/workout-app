package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate

/**
 * A set template's targetWeightKg is the day-one target: sessions prefill from the last
 * finished log instead and never write the number back. Anything that COPIES templates
 * (archive "use as base", swap → "use this exercise's last config") therefore has to carry
 * the trained numbers along, or the copy restarts at 0 kg and the session forward-fill
 * spreads the warm-up weight over every set (Allan, 16/09).
 */
object TemplateCarry {

    /**
     * [templates] with each slot's weight (and set type) replaced by the most recent entry
     * for that setIndex in [logs] (any order; newest completedAt wins). Ids are untouched.
     */
    fun effective(templates: List<SetTemplate>, logs: List<SetLog>): List<SetTemplate> {
        if (logs.isEmpty()) return templates
        val latest = logs.groupBy { it.setIndex }.mapValues { (_, l) -> l.maxBy { it.completedAt } }
        return templates.map { t ->
            latest[t.setIndex]?.let { t.copy(targetWeightKg = it.weightKg, type = it.type) } ?: t
        }
    }

    /**
     * Of [candidates] (workout-exercise ids), the one whose newest log is the most recent —
     * "the last time I did this exercise". Null when none of them was ever trained.
     */
    inline fun lastTrained(candidates: List<Long>, logsOf: (Long) -> List<SetLog>): Long? =
        candidates
            .mapNotNull { id -> logsOf(id).maxOfOrNull { it.completedAt }?.let { id to it } }
            .maxByOrNull { it.second }?.first
}
