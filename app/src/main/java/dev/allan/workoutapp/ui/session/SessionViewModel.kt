package dev.allan.workoutapp.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.Session
import dev.allan.workoutapp.data.db.SessionStatus
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.session.SessionManager
import dev.allan.workoutapp.session.TimerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Auto-end threshold: a RUNNING session older than this is flagged as ended. */
const val SESSION_AUTO_END_MS = 5L * 3600 * 1000

data class SessionSet(
    val templateId: Long,
    val setIndex: Int,
    val type: SetType,
    val weightKg: Double,
    val value: Int,
    val valueUnit: ValueUnit,
    val restSecs: Int,
    /** Target rep range from the template (reference; [value] is what was really done). */
    val targetMin: Int,
    val targetMax: Int? = null,
    val done: Boolean = false,
)

data class SessionExercise(
    val workoutExerciseId: Long,
    val exerciseId: String,
    val name: String,
    val weightMode: WeightMode,
    val barWeightKg: Double,
    val imagePath: String?,
    val sets: List<SessionSet>,
    /** Alternates with the previous exercise (A1, B1, rest, A2, B2, …). */
    val supersetWithPrev: Boolean = false,
    /** Science-based progression hint; applied only when the user taps it. */
    val suggestion: dev.allan.workoutapp.data.ProgressionEngine.Suggestion? = null,
)

data class SessionUiState(
    val sessionId: Long? = null,
    val workoutName: String = "",
    val exercises: List<SessionExercise> = emptyList(),
    val currentIndex: Int = 0,
    /** null = exercise-list mode, else pager mode. */
    val showList: Boolean = true,
    /** exerciseIndex to templateId of the next expected set (superset-interleaved order). */
    val currentStep: Pair<Int, Long>? = null,
    val elapsedSecs: Int = 0,
    val restRemainingSecs: Int? = null,
    val setCountdownRemainingSecs: Int? = null,
    val stopwatchSecs: Int = 0,
    val stopwatchRunning: Boolean = false,
    val timerPanelVisible: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Superset-aware set order. Exercises marked supersetWithPrev form a chain with their
 * predecessor; a chain's sets interleave by round: A1, B1, A2, B2, … Rest only happens
 * after the last chain member of a round.
 */
object SupersetOrder {

    /** Indices of the chain containing [index] (singleton list when not paired). */
    fun chain(exercises: List<SessionExercise>, index: Int): List<Int> {
        var first = index
        while (first > 0 && exercises[first].supersetWithPrev) first--
        var last = index
        while (last + 1 < exercises.size && exercises[last + 1].supersetWithPrev) last++
        return (first..last).toList()
    }

    /** All (exerciseIndex, set) of a chain in execution order. */
    fun interleaved(exercises: List<SessionExercise>, chain: List<Int>): List<Pair<Int, SessionSet>> {
        val rounds = chain.maxOf { exercises[it].sets.size }
        return (0 until rounds).flatMap { round ->
            chain.mapNotNull { i -> exercises[i].sets.getOrNull(round)?.let { i to it } }
        }
    }

    /** Next expected set across the whole workout: first undone in chain-interleaved order. */
    fun nextStep(exercises: List<SessionExercise>): Pair<Int, Long>? {
        var i = 0
        while (i < exercises.size) {
            val chain = chain(exercises, i)
            interleaved(exercises, chain).firstOrNull { !it.second.done }?.let { (idx, set) ->
                return idx to set.templateId
            }
            i = chain.last() + 1
        }
        return null
    }

    /** True when another chain member still has an undone set in this round → skip rest. */
    fun restSkipped(exercises: List<SessionExercise>, exerciseIndex: Int, set: SessionSet): Boolean {
        val chain = chain(exercises, exerciseIndex)
        if (chain.size < 2) return false
        val after = chain.dropWhile { it != exerciseIndex }.drop(1)
        val round = exercises[exerciseIndex].sets.indexOfFirst { it.templateId == set.templateId }
        return after.any { i -> exercises[i].sets.getOrNull(round)?.done == false }
    }
}

class SessionViewModel(app: Application, private val workoutId: Long, private val lang: String) :
    AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    init {
        viewModelScope.launch {
            startOrResume()
            ticker()
        }
    }

    private suspend fun startOrResume() {
        val workout = db.planDao().workout(workoutId) ?: return
        val existing = db.sessionDao().runningSession()
        val session: Session = when {
            existing != null && existing.workoutId == workoutId -> existing
            else -> {
                val id = db.sessionDao().insertSession(
                    Session(workoutId = workoutId, startedAt = System.currentTimeMillis())
                )
                db.sessionDao().session(id)!!
            }
        }
        if (SessionManager.state.value.sessionId != session.id) {
            SessionManager.startSession(session.id, session.startedAt)
        }
        TimerService.start(getApplication())

        val wes = db.planDao().workoutExercises(workoutId).first()
        val templates = db.planDao().setTemplatesForWorkout(workoutId).first()
        val loggedSets = db.sessionDao().setLogs(session.id)

        val exercises = wes.map { we ->
            val previous = db.sessionDao().previousLogs(we.id)
            val weTemplates = templates.filter { it.workoutExerciseId == we.id }.sortedBy { it.setIndex }
            val sets = weTemplates.map { t ->
                // Prefill with the most recent finished-session log for the same set slot.
                val prev = previous.firstOrNull { it.setIndex == t.setIndex }
                val already = loggedSets.any { it.workoutExerciseId == we.id && it.setIndex == t.setIndex }
                SessionSet(
                    templateId = t.id,
                    setIndex = t.setIndex,
                    type = prev?.type ?: t.type,
                    weightKg = prev?.weightKg ?: t.targetWeightKg,
                    value = prev?.value ?: t.targetValue,
                    valueUnit = prev?.valueUnit ?: t.valueUnit,
                    restSecs = t.restSecs,
                    targetMin = t.targetValue,
                    targetMax = t.targetValueMax,
                    done = already,
                )
            }
            val exercise = db.exerciseDao().exercise(we.exerciseId)
            SessionExercise(
                workoutExerciseId = we.id,
                exerciseId = we.exerciseId,
                name = PlanRepo.displayName(db, we.exerciseId, lang),
                weightMode = we.weightMode,
                barWeightKg = we.barWeightKg,
                imagePath = exercise?.imagePath,
                sets = sets,
                supersetWithPrev = we.supersetWithPrev,
                suggestion = dev.allan.workoutapp.data.ProgressionEngine.suggest(
                    templates = weTemplates,
                    history = previous,
                    primaryMuscles = exercise?.primaryMuscles ?: emptyList(),
                    weightMode = we.weightMode,
                ),
            )
        }
        _state.value = _state.value.copy(
            sessionId = session.id,
            workoutName = workout.name,
            exercises = exercises,
            currentStep = SupersetOrder.nextStep(exercises),
        )
        // Backfill any missing images (e.g. exercise added before the media pipeline existed).
        exercises.filter { it.imagePath == null }.forEach { ex ->
            viewModelScope.launch {
                val path = dev.allan.workoutapp.data.MediaStore.ensureImage(getApplication(), db, ex.exerciseId)
                    ?: return@launch
                _state.value = _state.value.copy(
                    exercises = _state.value.exercises.map {
                        if (it.workoutExerciseId == ex.workoutExerciseId) it.copy(imagePath = path) else it
                    }
                )
            }
        }
    }

    /** 1 Hz UI clock: recompute all countdowns from wall-clock instants. */
    private suspend fun ticker() {
        while (true) {
            val timers = SessionManager.state.value
            val now = System.currentTimeMillis()
            val startedAt = timers.sessionStartedAt
            if (startedAt != null && !_state.value.finished) {
                val restRemaining = timers.restEndAt?.let { ((it - now) / 1000L).toInt() }
                if (restRemaining != null && restRemaining <= 0) SessionManager.stopRest()
                val countdownRemaining = timers.setCountdownEndAt?.let { ((it - now) / 1000L).toInt() }
                if (countdownRemaining != null && countdownRemaining <= 0) SessionManager.cancelSetCountdown()
                _state.value = _state.value.copy(
                    elapsedSecs = ((now - startedAt) / 1000L).toInt(),
                    restRemainingSecs = restRemaining?.takeIf { it > 0 },
                    setCountdownRemainingSecs = countdownRemaining?.takeIf { it > 0 },
                    stopwatchSecs = timers.stopwatchStartedAt?.let { ((now - it) / 1000L).toInt() } ?: 0,
                    stopwatchRunning = timers.stopwatchStartedAt != null,
                )
            }
            delay(250)
        }
    }

    fun openExercise(index: Int) {
        _state.value = _state.value.copy(currentIndex = index, showList = false)
    }

    fun showList() {
        _state.value = _state.value.copy(showList = true)
    }

    fun setCurrentIndex(index: Int) {
        _state.value = _state.value.copy(currentIndex = index)
    }

    fun toggleTimerPanel() {
        _state.value = _state.value.copy(timerPanelVisible = !_state.value.timerPanelVisible)
    }

    fun updateSet(exerciseIndex: Int, set: SessionSet) {
        val exercises = _state.value.exercises.toMutableList()
        val ex = exercises[exerciseIndex]
        exercises[exerciseIndex] = ex.copy(sets = ex.sets.map { if (it.templateId == set.templateId) set else it })
        _state.value = _state.value.copy(
            exercises = exercises,
            currentStep = SupersetOrder.nextStep(exercises),
        )
    }

    /** Apply the progression hint to all undone working sets of the exercise, then clear it. */
    fun applySuggestion(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val s = ex.suggestion ?: return
        val newSets = ex.sets.map { set ->
            val working = !set.done && set.valueUnit == ValueUnit.REPS &&
                (set.type == SetType.NORMAL || set.type == SetType.FAILURE)
            when {
                !working -> set
                s.kind == dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_WEIGHT ->
                    set.copy(weightKg = set.weightKg + s.weightIncrementKg)
                else -> set.copy(value = set.value + 1)
            }
        }
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(sets = newSets, suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
    }

    fun dismissSuggestion(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
    }

    /** Log a set: write SetLog, mark done, start its rest countdown, show timer panel. */
    fun logSet(exerciseIndex: Int, set: SessionSet) {
        val sessionId = _state.value.sessionId ?: return
        val ex = _state.value.exercises[exerciseIndex]
        if (set.done) return

        // Active time: timed sets count their duration; rep sets use the running
        // stopwatch when present, otherwise a 3 s/rep estimate.
        val active = when (set.valueUnit) {
            ValueUnit.SECS -> set.value
            ValueUnit.REPS -> SessionManager.consumeStopwatch() ?: (set.value * 3)
        }
        SessionManager.addActiveSecs(active)

        viewModelScope.launch {
            db.sessionDao().insertSetLog(
                SetLog(
                    sessionId = sessionId,
                    workoutExerciseId = ex.workoutExerciseId,
                    exerciseId = ex.exerciseId,
                    setIndex = set.setIndex,
                    type = set.type,
                    weightKg = set.weightKg,
                    weightMode = ex.weightMode,
                    barWeightKg = ex.barWeightKg,
                    value = set.value,
                    valueUnit = set.valueUnit,
                    activeSecs = active,
                    restSecs = set.restSecs,
                    completedAt = System.currentTimeMillis(),
                )
            )
        }
        updateSet(exerciseIndex, set.copy(done = true))

        // Superset pairs alternate without rest: A1, B1 (no pause after A1), rest after B1.
        // Legacy SUPERSET set rows keep their no-rest behavior too.
        val skipRest = set.type == SetType.SUPERSET ||
            SupersetOrder.restSkipped(_state.value.exercises, exerciseIndex, set)
        if (!skipRest) {
            SessionManager.startRest(set.restSecs)
            TimerService.showCountdown(
                getApplication(),
                System.currentTimeMillis() + set.restSecs * 1000L,
                getApplication<Application>().getString(dev.allan.workoutapp.R.string.rest),
            )
        }
        _state.value = _state.value.copy(timerPanelVisible = true)
    }

    fun startSetCountdown(set: SessionSet) {
        SessionManager.startSetCountdown(set.value)
        TimerService.showCountdown(
            getApplication(),
            System.currentTimeMillis() + set.value * 1000L,
            getApplication<Application>().getString(dev.allan.workoutapp.R.string.set_timer),
        )
        _state.value = _state.value.copy(timerPanelVisible = true)
    }

    fun toggleStopwatch() {
        SessionManager.toggleStopwatch()
    }

    fun stopRest() {
        SessionManager.stopRest()
        TimerService.showDefault(getApplication())
    }

    fun saveNote(exerciseId: String, text: String) {
        val sessionId = _state.value.sessionId
        viewModelScope.launch {
            db.sessionDao().insertNote(
                dev.allan.workoutapp.data.db.ExerciseNote(
                    exerciseId = exerciseId,
                    sessionId = sessionId,
                    text = text,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** End the session. save=false discards logged sets. Returns via onDone(sessionId). */
    fun endSession(save: Boolean, onDone: (Long) -> Unit) {
        val sessionId = _state.value.sessionId ?: return
        SessionManager.stopRest()
        SessionManager.consumeStopwatch()?.let { SessionManager.addActiveSecs(it) }
        val timers = SessionManager.state.value
        viewModelScope.launch {
            val session = db.sessionDao().session(sessionId) ?: return@launch
            db.sessionDao().updateSession(
                session.copy(
                    endedAt = System.currentTimeMillis(),
                    status = if (save) SessionStatus.FINISHED else SessionStatus.DISCARDED,
                    activeSecs = timers.activeSecs,
                    restSecs = timers.restSecs,
                )
            )
            SessionManager.clear()
            TimerService.stop(getApplication())
            _state.value = _state.value.copy(finished = true)
            onDone(sessionId)
        }
    }

    class Factory(private val app: Application, private val workoutId: Long, private val lang: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionViewModel(app, workoutId, lang) as T
    }
}
