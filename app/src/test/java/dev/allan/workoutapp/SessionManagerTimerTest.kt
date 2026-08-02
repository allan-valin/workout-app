package dev.allan.workoutapp

import dev.allan.workoutapp.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the session timers Allan uses mid-set: the timed-set countdown
 * (pause/resume added 25/07 — it previously could not be paused or stopped at all) and the
 * stopwatch/rest accounting from Phase 9.
 */
class SessionManagerTimerTest {

    @Before
    fun reset() {
        SessionManager.clear()
    }

    // ---- timed-set countdown ----

    @Test
    fun `starting a set countdown records its end and owning set`() {
        SessionManager.startSetCountdown(40, templateId = 7L)
        val state = SessionManager.state.value
        assertNotNull(state.setCountdownEndAt)
        assertEquals(40, state.setCountdownDurationSecs)
        assertEquals(7L, state.setCountdownTemplateId)
        assertNull("a fresh countdown is not paused", state.setCountdownPausedSecs)
    }

    @Test
    fun `pausing freezes the remaining seconds and stops the countdown`() {
        SessionManager.startSetCountdown(40, templateId = 1L)
        SessionManager.pauseSetCountdown()
        val state = SessionManager.state.value
        assertNull("a paused countdown has no end instant", state.setCountdownEndAt)
        val remaining = state.setCountdownPausedSecs
        assertNotNull(remaining)
        assertTrue("kept roughly the full 40 s, was $remaining", remaining!! in 38..40)
    }

    @Test
    fun `resuming restores an end instant from the frozen remainder`() {
        SessionManager.startSetCountdown(30, templateId = 1L)
        SessionManager.pauseSetCountdown()
        val frozen = SessionManager.state.value.setCountdownPausedSecs!!
        val endAt = SessionManager.resumeSetCountdown()
        assertNotNull("resume returns the new end instant so the alert can be rescheduled", endAt)
        val expected = System.currentTimeMillis() + frozen * 1000L
        assertTrue("end instant near now + $frozen s", kotlin.math.abs(endAt!! - expected) < 2_000)
        assertNull(SessionManager.state.value.setCountdownPausedSecs)
    }

    @Test
    fun `resuming without a paused countdown does nothing`() {
        assertNull(SessionManager.resumeSetCountdown())
    }

    @Test
    fun `cancelling clears end, remainder and the owning set`() {
        SessionManager.startSetCountdown(40, templateId = 3L)
        SessionManager.pauseSetCountdown()
        SessionManager.cancelSetCountdown()
        val state = SessionManager.state.value
        assertNull(state.setCountdownEndAt)
        assertNull(state.setCountdownPausedSecs)
        assertNull(state.setCountdownTemplateId)
        assertEquals(0, state.setCountdownDurationSecs)
    }

    @Test
    fun `starting a second set countdown takes ownership from the first`() {
        SessionManager.startSetCountdown(40, templateId = 1L)
        SessionManager.pauseSetCountdown()
        SessionManager.startSetCountdown(20, templateId = 2L)
        val state = SessionManager.state.value
        assertEquals(2L, state.setCountdownTemplateId)
        assertNull("the new countdown is running, not paused", state.setCountdownPausedSecs)
    }

    // ---- stopwatch (Phase 9 behaviour that must keep working) ----

    @Test
    fun `stopwatch pause keeps the reading and books nothing`() {
        SessionManager.toggleStopwatch()
        SessionManager.toggleStopwatch()
        assertNull(SessionManager.state.value.stopwatchStartedAt)
        assertEquals(0, SessionManager.state.value.activeSecs)
    }

    @Test
    fun `consuming the stopwatch resets it and reports nothing when unused`() {
        assertNull(SessionManager.consumeStopwatch())
        SessionManager.toggleStopwatch()
        SessionManager.consumeStopwatch()
        assertEquals(0, SessionManager.stopwatchSecs())
    }

    @Test
    fun `rest accounting books elapsed rest and anchors the next gap`() {
        SessionManager.startRest(60)
        assertNotNull(SessionManager.state.value.restEndAt)
        SessionManager.stopRest()
        val state = SessionManager.state.value
        assertNull(state.restEndAt)
        assertTrue("elapsed rest booked (0 s is fine on an instant stop)", state.restSecs >= 0)
        assertNotNull("stopping rest anchors the active-time gap", state.lastRestEndedAt)
    }

    @Test
    fun `active seconds accumulate`() {
        SessionManager.addActiveSecs(30)
        SessionManager.addActiveSecs(12)
        assertEquals(42, SessionManager.state.value.activeSecs)
    }

    // ---- 02/08: every countdown run books, supersets share one measurement ----

    /**
     * Allan, 02/08: the same 45 s timer run twice (one leg each) booked 45 s once. Each
     * completed run books, and the set itself then books nothing more.
     */
    @Test
    fun `each completed countdown run books its own seconds`() {
        SessionManager.startSession(1, System.currentTimeMillis())
        SessionManager.startSetCountdown(45, templateId = 7L)
        SessionManager.completeSetCountdown()
        SessionManager.startSetCountdown(45, templateId = 7L)
        SessionManager.completeSetCountdown()
        assertEquals(90, SessionManager.state.value.activeSecs)
        assertEquals(90, SessionManager.bookedRunSecs(7L))
        assertNull(SessionManager.state.value.setCountdownEndAt)
        assertNull(SessionManager.state.value.setCountdownTemplateId)
    }

    @Test
    fun `completing without a running countdown books nothing`() {
        SessionManager.startSession(1, System.currentTimeMillis())
        SessionManager.completeSetCountdown()
        assertEquals(0, SessionManager.state.value.activeSecs)
        assertNull(SessionManager.bookedRunSecs(7L))
    }

    @Test
    fun `clearing booked runs forgets the set`() {
        SessionManager.startSession(1, System.currentTimeMillis())
        SessionManager.startSetCountdown(30, templateId = 7L)
        SessionManager.completeSetCountdown()
        SessionManager.clearBookedRuns(7L)
        assertNull(SessionManager.bookedRunSecs(7L))
    }

    /**
     * Superset: both exercises are done back to back, so one 2-minute stopwatch run covers
     * both sets. The first books the measurement, the second — registered seconds later —
     * books nothing at all (Allan, 02/08).
     */
    @Test
    fun `a set logged right after a measured one is already covered`() {
        SessionManager.startSession(1, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        SessionManager.recordMeasured(120, now)
        assertTrue(SessionManager.coveredByPreviousMeasure(now + 5_000))
        assertFalse(SessionManager.coveredByPreviousMeasure(now + 25_000))
    }

    @Test
    fun `nothing measured means nothing is covered`() {
        SessionManager.startSession(1, System.currentTimeMillis())
        assertFalse(SessionManager.coveredByPreviousMeasure())
    }
}
