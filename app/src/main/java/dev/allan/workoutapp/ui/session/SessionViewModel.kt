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
    /** Cadence reminder (e.g. "4-0-2-0"), shown big above the sets. */
    val tempo: String = "",
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
    /** Rough time budget for the whole workout, shown next to the elapsed clock. */
    val estimatedTotalSecs: Int = 0,
    val restRemainingSecs: Int? = null,
    val setCountdownRemainingSecs: Int? = null,
    val stopwatchSecs: Int = 0,
    val stopwatchRunning: Boolean = false,
    /** Live active-time total: booked seconds + the current stopwatch reading. */
    val activeSecs: Int = 0,
    val timerPanelVisible: Boolean = false,
    /** True once the user edits the plan mid-session (drives the keep/one-time prompt). */
    val templatesChanged: Boolean = false,
    /** One-shot: pager should animate to this exercise index (superset/auto-advance). */
    val pendingSwipeTo: Int? = null,
    /**
     * Bumped on every swipe request so the pager's LaunchedEffect re-fires even when two
     * successive auto-advances target the same page index (bug B: value-keyed effect
     * silently skipped identical targets, killing auto-advance).
     */
    val swipeToken: Int = 0,
    /** Exercise description shown in the bottom sheet, null = hidden. */
    val descriptionSheet: String? = null,
    /** Exercise the sheet belongs to + its user-saved video link. */
    val descriptionExerciseId: String? = null,
    val descriptionVideoUrl: String? = null,
    val descriptionNote: String? = null,
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

    /**
     * Next expected set relative to [fromIndex]: the exercise's own chain first, then the
     * chains after it, wrapping around so earlier skipped exercises come last. Keeps the
     * pager from jumping back to a skipped exercise while the current one has open sets.
     */
    fun nextStepFrom(exercises: List<SessionExercise>, fromIndex: Int): Pair<Int, Long>? {
        if (exercises.isEmpty()) return null
        val startChain = chain(exercises, fromIndex.coerceIn(exercises.indices))
        interleaved(exercises, startChain).firstOrNull { !it.second.done }?.let { (idx, set) ->
            return idx to set.templateId
        }
        var i = (startChain.last() + 1) % exercises.size
        while (i !in startChain) {
            val c = chain(exercises, i)
            interleaved(exercises, c).firstOrNull { !it.second.done }?.let { (idx, set) ->
                return idx to set.templateId
            }
            i = (c.last() + 1) % exercises.size
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

/**
 * Rough workout time budget: per exercise a 60 s setup buffer, plus per set the work time
 * (set seconds for timed sets, else 40 s) and its rest. Reference only — not exact.
 */
fun estimateWorkoutSecs(exercises: List<SessionExercise>): Int =
    exercises.sumOf { ex ->
        60 + ex.sets.sumOf { set ->
            val work = if (set.valueUnit == ValueUnit.SECS) set.value else 40
            work + set.restSecs
        }
    }

class SessionViewModel(app: Application, private val workoutId: Long, private val lang: String) :
    AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    // Set templates as they were when this session view loaded — restored if the user
    // chooses "one-time" for mid-session edits (add/remove set, change type/target/tempo).
    private var templateSnapshot: List<dev.allan.workoutapp.data.db.SetTemplate> = emptyList()

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
        if (templateSnapshot.isEmpty()) templateSnapshot = templates
        val loggedSets = db.sessionDao().setLogs(session.id)
        val drafts = db.sessionDao().drafts(session.id).associateBy { it.templateId }

        val exercises = wes.map { we ->
            val previous = db.sessionDao().previousLogs(we.id)
            val weTemplates = templates.filter { it.workoutExerciseId == we.id }.sortedBy { it.setIndex }
            val sets = weTemplates.map { t ->
                // Prefill order: this session's unlogged draft → most recent finished-session
                // log for the same slot → template target.
                val prev = previous.firstOrNull { it.setIndex == t.setIndex }
                val draft = drafts[t.id]
                val already = loggedSets.any { it.workoutExerciseId == we.id && it.setIndex == t.setIndex }
                SessionSet(
                    templateId = t.id,
                    setIndex = t.setIndex,
                    type = prev?.type ?: t.type,
                    weightKg = draft?.weightKg ?: prev?.weightKg ?: t.targetWeightKg,
                    value = draft?.value ?: prev?.value ?: t.targetValue,
                    valueUnit = prev?.valueUnit ?: t.valueUnit,
                    restSecs = t.restSecs,
                    targetMin = t.targetValue,
                    targetMax = t.targetValueMax,
                    tempo = t.tempo,
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
            estimatedTotalSecs = estimateWorkoutSecs(exercises),
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
                val stopwatch = SessionManager.stopwatchSecs(now)
                _state.value = _state.value.copy(
                    elapsedSecs = ((now - startedAt) / 1000L).toInt(),
                    restRemainingSecs = restRemaining?.takeIf { it > 0 },
                    setCountdownRemainingSecs = countdownRemaining?.takeIf { it > 0 },
                    stopwatchSecs = stopwatch,
                    stopwatchRunning = timers.stopwatchStartedAt != null,
                    activeSecs = timers.activeSecs + stopwatch,
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
            currentStep = SupersetOrder.nextStepFrom(exercises, exerciseIndex),
        )
        saveDraft(set)
    }

    /**
     * Weight edit with forward-fill: sets after this one that are still 0 kg and undone
     * get the same weight, so one input covers the usual same-weight-all-sets case.
     */
    fun updateWeight(exerciseIndex: Int, set: SessionSet, weightKg: Double) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val editedIndex = ex.sets.indexOfFirst { it.templateId == set.templateId }
        if (editedIndex < 0) return
        val newSets = ex.sets.mapIndexed { i, s ->
            when {
                i == editedIndex -> s.copy(weightKg = weightKg)
                i > editedIndex && !s.done && s.weightKg == 0.0 && weightKg > 0.0 ->
                    s.copy(weightKg = weightKg)
                else -> s
            }
        }
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(sets = newSets)
        _state.value = _state.value.copy(
            exercises = exercises,
            currentStep = SupersetOrder.nextStepFrom(exercises, exerciseIndex),
        )
        newSets.filterIndexed { i, s -> i == editedIndex || s.weightKg != ex.sets[i].weightKg }
            .forEach(::saveDraft)
    }

    private fun saveDraft(set: SessionSet) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            db.sessionDao().upsertDraft(
                dev.allan.workoutapp.data.db.SessionSetDraft(
                    sessionId = sessionId,
                    templateId = set.templateId,
                    weightKg = set.weightKg,
                    value = set.value,
                )
            )
        }
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

    /** Log a set: write SetLog, mark done, start its rest countdown, show timer panel.
     *  Tapping an already-done set un-logs it (checkmark toggle). */
    fun logSet(exerciseIndex: Int, set: SessionSet) {
        val sessionId = _state.value.sessionId ?: return
        val ex = _state.value.exercises[exerciseIndex]
        if (set.done) {
            unlogSet(exerciseIndex, set)
            return
        }

        // Active time: timed sets count their duration; rep sets use the running
        // stopwatch when present, else the gap since the last rest ended (>3 min
        // means "forgot to log" and books only 40 s), else a 3 s/rep estimate.
        val active = when (set.valueUnit) {
            ValueUnit.SECS -> set.value
            ValueUnit.REPS -> SessionManager.consumeStopwatch()
                ?: SessionManager.gapActiveSecs()
                ?: (set.value * 3)
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

        // Auto-advance: follow the superset-aware next step relative to the exercise just
        // logged — skipped earlier exercises come last, not first. All done → back to the
        // exercise list (that's where "End workout" lives).
        val next = SupersetOrder.nextStepFrom(_state.value.exercises, exerciseIndex)
        _state.value = when {
            next == null -> _state.value.copy(timerPanelVisible = true, showList = true)
            next.first != exerciseIndex -> _state.value.copy(
                timerPanelVisible = true,
                pendingSwipeTo = next.first,
                swipeToken = _state.value.swipeToken + 1,
            )
            else -> _state.value.copy(timerPanelVisible = true)
        }
    }

    /** Undo a mistaken checkmark: delete the SetLog row and give back its active time. */
    private fun unlogSet(exerciseIndex: Int, set: SessionSet) {
        val sessionId = _state.value.sessionId ?: return
        val ex = _state.value.exercises[exerciseIndex]
        viewModelScope.launch {
            db.sessionDao().setLog(sessionId, ex.workoutExerciseId, set.setIndex)?.let { log ->
                log.activeSecs?.let { SessionManager.addActiveSecs(-it) }
                db.sessionDao().deleteSetLog(sessionId, ex.workoutExerciseId, set.setIndex)
            }
            updateSet(exerciseIndex, set.copy(done = false))
        }
    }

    fun clearPendingSwipe() {
        _state.value = _state.value.copy(pendingSwipeTo = null)
    }

    /** Jump the pager to an exercise (story-bar segment tap). */
    fun requestSwipe(index: Int) {
        _state.value = _state.value.copy(
            pendingSwipeTo = index,
            swipeToken = _state.value.swipeToken + 1,
        )
    }

    /** Show the current exercise's description (localized, en fallback) in a sheet. */
    fun openDescription(exerciseId: String) {
        viewModelScope.launch {
            val translations = db.exerciseDao().translations(exerciseId)
            val best = translations.firstOrNull { it.lang == lang }
                ?: translations.firstOrNull { it.lang == "en" } ?: translations.firstOrNull()
            _state.value = _state.value.copy(
                descriptionSheet = best?.description.orEmpty(),
                descriptionExerciseId = exerciseId,
                descriptionVideoUrl = db.exerciseDao().videoLink(exerciseId),
                descriptionNote = db.sessionDao().noteText(exerciseId) ?: "",
            )
        }
    }

    fun closeDescription() {
        _state.value = _state.value.copy(
            descriptionSheet = null,
            descriptionExerciseId = null,
            descriptionVideoUrl = null,
            descriptionNote = null,
        )
    }

    /** Save (or clear, when blank) the user's video link for an exercise. */
    fun saveVideoLink(exerciseId: String, url: String) {
        val trimmed = url.trim()
        _state.value = _state.value.copy(descriptionVideoUrl = trimmed.ifBlank { null })
        viewModelScope.launch {
            if (trimmed.isBlank()) db.exerciseDao().deleteVideoLink(exerciseId)
            else db.exerciseDao().upsertVideoLink(
                dev.allan.workoutapp.data.db.ExerciseLink(exerciseId = exerciseId, url = trimmed)
            )
        }
    }

    /** Re-read templates/logs/drafts, e.g. after editing the workout mid-session. */
    fun refresh() {
        viewModelScope.launch { startOrResume() }
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

    fun resetStopwatch() {
        // Stop books the reading into the active total, then shows 0:00 (Allan's spec).
        SessionManager.stopBookStopwatch()
    }

    fun stopRest() {
        SessionManager.stopRest()
        TimerService.showDefault(getApplication())
    }

    fun saveNote(exerciseId: String, text: String) {
        _state.value = _state.value.copy(descriptionNote = text)
        viewModelScope.launch { PlanRepo.saveExerciseNote(db, exerciseId, text) }
    }

    /** In-session plan edits — write to the set template, then refresh the SessionSet list.
     *  Marks templatesChanged so the end flow can offer keep vs one-time. */
    private fun editTemplate(templateId: Long, transform: (dev.allan.workoutapp.data.db.SetTemplate) -> dev.allan.workoutapp.data.db.SetTemplate) {
        viewModelScope.launch {
            val t = db.planDao().setTemplatesForWorkout(workoutId).first().firstOrNull { it.id == templateId } ?: return@launch
            db.planDao().updateSetTemplate(transform(t))
            _state.value = _state.value.copy(templatesChanged = true)
            startOrResume()
        }
    }

    fun setSetType(set: SessionSet, type: SetType) = editTemplate(set.templateId) { it.copy(type = type) }
    fun setSetTarget(set: SessionSet, min: Int, max: Int?) =
        editTemplate(set.templateId) { it.copy(targetValue = min, targetValueMax = max?.takeIf { m -> m > min }) }

    fun addSessionSet(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        viewModelScope.launch {
            val templates = db.planDao().setTemplatesForWorkout(workoutId).first()
                .filter { it.workoutExerciseId == ex.workoutExerciseId }.sortedBy { it.setIndex }
            val last = templates.lastOrNull()
            db.planDao().insertSetTemplate(
                (last ?: dev.allan.workoutapp.data.db.SetTemplate(workoutExerciseId = ex.workoutExerciseId, setIndex = -1))
                    .copy(id = 0, setIndex = (last?.setIndex ?: -1) + 1)
            )
            _state.value = _state.value.copy(templatesChanged = true)
            startOrResume()
        }
    }

    fun removeSessionSet(set: SessionSet) {
        viewModelScope.launch {
            db.planDao().deleteSetTemplate(set.templateId)
            _state.value = _state.value.copy(templatesChanged = true)
            startOrResume()
        }
    }

    /** Undo mid-session plan edits (the "one-time" choice) by restoring the entry snapshot. */
    private suspend fun restorePlanTemplates() {
        db.planDao().deleteSetTemplatesForWorkout(workoutId)
        db.planDao().restoreSetTemplates(templateSnapshot)
    }

    /** End the session. save=false discards logged sets. Returns via onDone(sessionId). */
    fun endSession(save: Boolean, keepPlanChanges: Boolean = true, onDone: (Long) -> Unit) {
        val sessionId = _state.value.sessionId ?: return
        SessionManager.stopRest()
        SessionManager.consumeStopwatch()?.let { SessionManager.addActiveSecs(it) }
        val timers = SessionManager.state.value
        viewModelScope.launch {
            if (!keepPlanChanges && _state.value.templatesChanged) restorePlanTemplates()
            val session = db.sessionDao().session(sessionId) ?: return@launch
            db.sessionDao().updateSession(
                session.copy(
                    endedAt = System.currentTimeMillis(),
                    status = if (save) SessionStatus.FINISHED else SessionStatus.DISCARDED,
                    activeSecs = timers.activeSecs,
                    restSecs = timers.restSecs,
                )
            )
            db.sessionDao().deleteDrafts(sessionId)
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
