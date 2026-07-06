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
        /** Stopwatch start instant, null = stopped. */
        val stopwatchStartedAt: Long? = null,
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

    fun toggleStopwatch() {
        val s = _state.value
        if (s.stopwatchStartedAt == null) {
            _state.value = s.copy(stopwatchStartedAt = System.currentTimeMillis())
        } else {
            val elapsed = ((System.currentTimeMillis() - s.stopwatchStartedAt) / 1000L).toInt()
            _state.value = s.copy(
                stopwatchStartedAt = null,
                activeSecs = s.activeSecs + elapsed,
            )
        }
    }

    /** Stops the stopwatch and returns its elapsed seconds without double-booking. */
    fun consumeStopwatch(): Int? {
        val s = _state.value
        val start = s.stopwatchStartedAt ?: return null
        val elapsed = ((System.currentTimeMillis() - start) / 1000L).toInt()
        _state.value = s.copy(stopwatchStartedAt = null)
        return elapsed
    }

    fun addActiveSecs(secs: Int) {
        _state.value = _state.value.copy(activeSecs = _state.value.activeSecs + secs)
    }
}
