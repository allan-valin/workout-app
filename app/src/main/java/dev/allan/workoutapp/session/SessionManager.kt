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
        /** Start instant of the running stopwatch segment, null = paused/stopped. */
        val stopwatchStartedAt: Long? = null,
        /** Seconds accumulated by previous stopwatch segments (pause keeps them). */
        val stopwatchAccumSecs: Int = 0,
        /** Accumulated active/rest seconds for the running session. */
        val activeSecs: Int = 0,
        val restSecs: Int = 0,
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
        val startAt = endAt - s.restDurationSecs * 1000L
        val elapsed = ((minOf(System.currentTimeMillis(), endAt) - startAt) / 1000L).toInt()
        _state.value = s.copy(
            restEndAt = null,
            restDurationSecs = 0,
            restSecs = s.restSecs + elapsed.coerceAtLeast(0),
        )
    }

    fun startSetCountdown(durationSecs: Int) {
        _state.value = _state.value.copy(
            setCountdownEndAt = System.currentTimeMillis() + durationSecs * 1000L,
            setCountdownDurationSecs = durationSecs,
        )
    }

    fun cancelSetCountdown() {
        _state.value = _state.value.copy(setCountdownEndAt = null, setCountdownDurationSecs = 0)
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

    /** Stop-and-reset: back to 0:00 without booking anything. */
    fun resetStopwatch() {
        _state.value = _state.value.copy(stopwatchStartedAt = null, stopwatchAccumSecs = 0)
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
