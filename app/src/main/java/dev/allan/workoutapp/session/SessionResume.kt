package dev.allan.workoutapp.session

import dev.allan.workoutapp.data.db.Session

/**
 * Which RUNNING session a workout should resume, and what to do with the others.
 *
 * Exists because of the 2026-07-25 progress-loss bug: startOrResume() ran unserialized from
 * init and every ON_RESUME, so a single Start tap could insert two RUNNING rows, and an
 * unordered `LIMIT 1` then bound an EMPTY duplicate — Allan's logged sets looked gone. The
 * choice is pure logic, so it lives here and is unit-tested instead of only being reachable
 * through Room.
 */
object SessionResume {

    /**
     * @param keep the session to bind, null when there is nothing to resume.
     * @param delete stray ids safe to drop (no logged sets).
     * @param close stray ids that DO have logged sets — finished rather than deleted, so no
     *   logged set is ever destroyed by cleanup.
     */
    data class Decision(
        val keep: Session?,
        val delete: List<Long> = emptyList(),
        val close: List<Long> = emptyList(),
    )

    /**
     * Picks the session with the most logged sets; ties break toward the newest start.
     * [logCounts] maps session id to its number of logged sets.
     */
    fun decide(candidates: List<Session>, logCounts: Map<Long, Int>): Decision {
        if (candidates.isEmpty()) return Decision(keep = null)
        val keep = candidates.maxWithOrNull(
            compareBy({ logCounts[it.id] ?: 0 }, { it.startedAt }, { it.id })
        )!!
        val strays = candidates.filter { it.id != keep.id }
        return Decision(
            keep = keep,
            delete = strays.filter { (logCounts[it.id] ?: 0) == 0 }.map { it.id },
            close = strays.filter { (logCounts[it.id] ?: 0) > 0 }.map { it.id },
        )
    }
}
