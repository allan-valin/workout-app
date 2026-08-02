package dev.allan.workoutapp.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide session timer state. All instants are epoch millis so every value
 * can be recomputed from the wall clock — a killed process loses nothing critical
 * (Session.startedAt is persisted in Room; rest timers are ephemeral by design).
 */
object SessionManager {

    data class TimerState(
        val sessionId: Long? = null,
        val sessionStartedAt: Long? = null,
        /** Rest countdown end instant, null = no rest running. */
        val restEndAt: Long? = null,
        val restDurationSecs: Int = 0,
        /** Timed-set countdown end instant. */
        val setCountdownEndAt: Long? = null,
        val setCountdownDurationSecs: Int = 0,
        /** Remaining seconds of a PAUSED set countdown, null = not paused. */
        val setCountdownPausedSecs: Int? = null,
        /** Template id of the set the countdown was started for (running or paused). */
        val setCountdownTemplateId: Long? = null,
        /** Start instant of the running stopwatch segment, null = paused/stopped. */
        val stopwatchStartedAt: Long? = null,
        /** Seconds accumulated by previous stopwatch segments (pause keeps them). */
        val stopwatchAccumSecs: Int = 0,
        /** Accumulated active/rest seconds for the running session. */
        val activeSecs: Int = 0,
        val restSecs: Int = 0,
        /** Instant the last rest ended — fallback active-time anchor when the
         *  stopwatch was never started for the following set. */
        val lastRestEndedAt: Long? = null,
        /** Seconds already booked per timed set, keyed by templateId (runs add up). */
        val runSecsByTemplate: Map<Long, Int> = emptyMap(),
        /** Last real measured set duration and when it was booked (superset coverage). */
        val lastMeasuredSecs: Int? = null,
        val lastMeasuredAt: Long? = null,
    )

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    fun startSession(sessionId: Long, startedAt: Long) {
        _state.value = TimerState(sessionId = sessionId, sessionStartedAt = startedAt)
    }

    fun clear() {
        _state.value = TimerState()
    }

    fun startRest(durationSecs: Int) {
        finishRestAccounting()
        _state.value = _state.value.copy(
            restEndAt = System.currentTimeMillis() + durationSecs * 1000L,
            restDurationSecs = durationSecs,
        )
    }

    fun stopRest() {
        finishRestAccounting()
    }

    /** Book elapsed rest into the accumulator and clear the countdown. */
    private fun finishRestAccounting() {
        val s = _state.value
        val endAt = s.restEndAt ?: return
        val now = System.currentTimeMillis()
        val startAt = endAt - s.restDurationSecs * 1000L
        val elapsed = ((minOf(now, endAt) - startAt) / 1000L).toInt()
        _state.value = s.copy(
            restEndAt = null,
            restDurationSecs = 0,
            restSecs = s.restSecs + elapsed.coerceAtLeast(0),
            lastRestEndedAt = minOf(now, endAt),
        )
    }

    /**
     * Fallback active seconds when no stopwatch ran: gap since the last rest ended.
     * Gaps over 3 min mean "forgot to log, was chatting" — book only 40 s. Single-use.
     */
    fun gapActiveSecs(): Int? {
        val s = _state.value
        val anchor = s.lastRestEndedAt ?: s.restEndAt?.takeIf { it <= System.currentTimeMillis() }
        anchor ?: return null
        _state.value = s.copy(lastRestEndedAt = null)
        val gap = ((System.currentTimeMillis() - anchor) / 1000L).toInt().coerceAtLeast(0)
        return if (gap > 180) 40 else gap.takeIf { it > 0 }
    }

    fun startSetCountdown(durationSecs: Int, templateId: Long? = null) {
        _state.value = _state.value.copy(
            setCountdownEndAt = System.currentTimeMillis() + durationSecs * 1000L,
            setCountdownDurationSecs = durationSecs,
            setCountdownPausedSecs = null,
            setCountdownTemplateId = templateId,
        )
    }

    /** Freeze the running set countdown, keeping the remaining seconds. */
    fun pauseSetCountdown() {
        val s = _state.value
        val endAt = s.setCountdownEndAt ?: return
        val remaining = ((endAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
        _state.value = s.copy(setCountdownEndAt = null, setCountdownPausedSecs = remaining)
    }

    /** Resume a paused set countdown; returns the new end instant for rescheduling the alert. */
    fun resumeSetCountdown(): Long? {
        val s = _state.value
        val remaining = s.setCountdownPausedSecs ?: return null
        val endAt = System.currentTimeMillis() + remaining * 1000L
        _state.value = s.copy(setCountdownEndAt = endAt, setCountdownPausedSecs = null)
        return endAt
    }

    fun cancelSetCountdown() {
        _state.value = _state.value.copy(
            setCountdownEndAt = null,
            setCountdownDurationSecs = 0,
            setCountdownPausedSecs = null,
            setCountdownTemplateId = null,
        )
    }

    /**
     * A countdown that ran to the end: book its full duration as active time and remember it
     * against the set, so running the same timer twice (left leg, right leg) counts twice and
     * logging the set afterwards doesn't book it a third time (Allan, 02/08). A countdown
     * stopped early goes through cancelSetCountdown and books nothing, on purpose.
     */
    fun completeSetCountdown(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val duration = s.setCountdownDurationSecs
        val templateId = s.setCountdownTemplateId
        if (duration <= 0 || templateId == null) {
            cancelSetCountdown()
            return
        }
        _state.value = s.copy(
            setCountdownEndAt = null,
            setCountdownDurationSecs = 0,
            setCountdownPausedSecs = null,
            setCountdownTemplateId = null,
            activeSecs = s.activeSecs + duration,
            runSecsByTemplate = s.runSecsByTemplate +
                (templateId to (s.runSecsByTemplate[templateId] ?: 0) + duration),
            lastMeasuredSecs = duration,
            lastMeasuredAt = now,
        )
    }

    /** Seconds already booked by finished countdown runs of this set, null = none. */
    fun bookedRunSecs(templateId: Long): Int? = _state.value.runSecsByTemplate[templateId]

    /** Forget a set's booked runs (used when un-logging it). */
    fun clearBookedRuns(templateId: Long) {
        _state.value = _state.value.copy(
            runSecsByTemplate = _state.value.runSecsByTemplate - templateId
        )
    }

    /** Remember when a real measurement was booked, so the set logged right after knows. */
    fun recordMeasured(secs: Int, now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(lastMeasuredSecs = secs, lastMeasuredAt = now)
    }

    /**
     * True when a measured duration was booked moments ago: in a superset both exercises are
     * done back to back with no chance to touch the phone, so that one measurement already
     * spans the set being logged now and it must not book anything of its own (Allan, 02/08).
     */
    fun coveredByPreviousMeasure(now: Long = System.currentTimeMillis()): Boolean {
        val at = _state.value.lastMeasuredAt ?: return false
        return now - at <= dev.allan.workoutapp.data.SetTiming.SHARE_WINDOW_MS
    }

    /** Current stopwatch reading: accumulated segments + the running one. */
    fun stopwatchSecs(now: Long = System.currentTimeMillis()): Int {
        val s = _state.value
        val running = s.stopwatchStartedAt?.let { ((now - it) / 1000L).toInt() } ?: 0
        return s.stopwatchAccumSecs + running
    }

    /** Play/pause: pause keeps the reading; play resumes from it. Nothing is booked here. */
    fun toggleStopwatch() {
        val s = _state.value
        if (s.stopwatchStartedAt == null) {
            _state.value = s.copy(stopwatchStartedAt = System.currentTimeMillis())
        } else {
            val elapsed = ((System.currentTimeMillis() - s.stopwatchStartedAt) / 1000L).toInt()
            _state.value = s.copy(
                stopwatchStartedAt = null,
                stopwatchAccumSecs = s.stopwatchAccumSecs + elapsed,
            )
        }
    }

    /** Stop: back to 0:00, but the reading is booked into the session's active total. */
    fun stopBookStopwatch() {
        consumeStopwatch()?.let(::addActiveSecs)
    }

    /** Stops + resets the stopwatch and returns its reading without double-booking. */
    fun consumeStopwatch(): Int? {
        val total = stopwatchSecs()
        if (total == 0 && _state.value.stopwatchStartedAt == null) return null
        _state.value = _state.value.copy(stopwatchStartedAt = null, stopwatchAccumSecs = 0)
        return total.takeIf { it > 0 }
    }

    fun addActiveSecs(secs: Int) {
        _state.value = _state.value.copy(activeSecs = _state.value.activeSecs + secs)
    }
}
