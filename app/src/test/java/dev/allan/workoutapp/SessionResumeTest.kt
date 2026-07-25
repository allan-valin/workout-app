package dev.allan.workoutapp

import dev.allan.workoutapp.data.db.Session
import dev.allan.workoutapp.data.db.SessionStatus
import dev.allan.workoutapp.session.SessionResume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 2026-07-25 progress-loss bug: duplicate RUNNING sessions where an
 * unordered pick could bind the EMPTY one, making Allan's logged sets look lost.
 */
class SessionResumeTest {

    private fun session(id: Long, startedAt: Long, workoutId: Long = 1) =
        Session(id = id, workoutId = workoutId, startedAt = startedAt, status = SessionStatus.RUNNING)

    @Test
    fun `nothing running means nothing to resume`() {
        val decision = SessionResume.decide(emptyList(), emptyMap())
        assertNull(decision.keep)
        assertTrue(decision.delete.isEmpty())
        assertTrue(decision.close.isEmpty())
    }

    @Test
    fun `single running session is resumed untouched`() {
        val only = session(1, 1_000)
        val decision = SessionResume.decide(listOf(only), mapOf(1L to 3))
        assertEquals(only, decision.keep)
        assertTrue(decision.delete.isEmpty())
        assertTrue(decision.close.isEmpty())
    }

    @Test
    fun `the session with logged sets wins over a newer empty duplicate`() {
        // THE bug: duplicate inserted a second later, empty, and it used to win.
        val withLogs = session(1, 1_000)
        val emptyNewer = session(2, 2_000)
        val decision = SessionResume.decide(
            listOf(emptyNewer, withLogs),
            mapOf(1L to 4, 2L to 0),
        )
        assertEquals(withLogs, decision.keep)
        assertEquals(listOf(2L), decision.delete)
        assertTrue(decision.close.isEmpty())
    }

    @Test
    fun `two empty duplicates keep the newest and delete the rest`() {
        val older = session(1, 1_000)
        val newer = session(2, 2_000)
        val decision = SessionResume.decide(listOf(older, newer), mapOf(1L to 0, 2L to 0))
        assertEquals(newer, decision.keep)
        assertEquals(listOf(1L), decision.delete)
    }

    @Test
    fun `a stray that has logs is closed, never deleted`() {
        val keeper = session(2, 2_000)
        val strayWithLogs = session(1, 1_000)
        val decision = SessionResume.decide(
            listOf(strayWithLogs, keeper),
            mapOf(1L to 2, 2L to 5),
        )
        assertEquals(keeper, decision.keep)
        assertTrue("logged sets must never be deleted", decision.delete.isEmpty())
        assertEquals(listOf(1L), decision.close)
    }

    @Test
    fun `equal log counts break the tie toward the newer session`() {
        val older = session(1, 1_000)
        val newer = session(2, 5_000)
        val decision = SessionResume.decide(listOf(older, newer), mapOf(1L to 2, 2L to 2))
        assertEquals(newer, decision.keep)
        assertEquals(listOf(1L), decision.close)
    }

    @Test
    fun `missing log counts are treated as zero`() {
        val a = session(1, 1_000)
        val b = session(2, 2_000)
        val decision = SessionResume.decide(listOf(a, b), emptyMap())
        assertEquals(b, decision.keep)
        assertEquals(listOf(1L), decision.delete)
    }
}
