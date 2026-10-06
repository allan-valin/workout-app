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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * The in-progress workout: state, the marker ("which set is next"), logging, timers, drafts,
 * mid-session plan edits and the end flow.
 *
 * OWNS: SessionUiState and every transition of it; the only writer of SetLog rows during a
 * session; the SupersetOrder rules (marker, "passed over", rest skip).
 * MUST NEVER:
 *  - move the marker because a number was edited — it follows LOGS only (29/08 A5);
 *  - recompute the marker from the start of the workout — it is relative to the set logged
 *    last (29/08, SupersetOrder.nextStepAfter);
 *  - treat a superset partner with no log yet as skipped — only a member whose turn was
 *    PASSED OVER drops out (30/09 S1/S3, SupersetOrder.activeMembers);
 *  - run startOrResume unserialized (25/07: duplicate RUNNING rows, logged sets "lost");
 *  - book active time twice or book a cut-short countdown (see SessionManager);
 *  - repaint a set's type from the last log instead of the plan (29/08 A2).
 * Shaped by: 25/07 (bugs B, D, progress loss), 02/08 (A2–A5 active time, chips), 29/08
 * (marker A4/A5, C1 undo, B2 cardio), 30/09 (S1/S3 hand-over, T1 gap).
 */

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
    /** One side at a time: rep estimates count twice (06/10). */
    val unilateral: Boolean = false,
    /** Science-based progression hint; applied only when the user taps it. */
    val suggestion: dev.allan.workoutapp.data.ProgressionEngine.Suggestion? = null,
    /** Pinned note text, shown as a line under the image; null = nothing pinned. */
    val pinnedNote: String? = null,
    /** Cardio exercise (wger category / custom flag): its timed sets chain hands-free (B2). */
    val isCardio: Boolean = false,
)

/** How the just-logged set compared to its cadence estimate (only "too fast" warns). */
data class PaceNote(
    val exerciseIndex: Int,
    val fast: Boolean,
    val actualSecs: Int,
    val expectedSecs: Int,
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
    /** exerciseIndex to templateId of the set logged most recently — what [currentStep] follows. */
    val lastLogged: Pair<Int, Long>? = null,
    /** Cardio auto-chain: templateId of the timed set to start as soon as the rest ends (B2). */
    val autoStartTemplateId: Long? = null,
    /** The log row an accidental un-log just removed; offers UNDO until it is consumed. */
    val undoableUnlog: dev.allan.workoutapp.data.db.SetLog? = null,
    val elapsedSecs: Int = 0,
    /** Rough time budget for the whole workout, shown next to the elapsed clock. */
    val estimatedTotalSecs: Int = 0,
    val restRemainingSecs: Int? = null,
    val setCountdownRemainingSecs: Int? = null,
    /** Remaining seconds of a paused set countdown, null = not paused. */
    val setCountdownPausedSecs: Int? = null,
    /** Which set (templateId) the running/paused countdown belongs to. */
    val setCountdownTemplateId: Long? = null,
    val stopwatchSecs: Int = 0,
    val stopwatchRunning: Boolean = false,
    /**
     * "forgot?" in the timer panel (Allan, 02/10): the user says the stopwatch/gap reading is
     * wrong (left running), so the next logged rep set books the estimate instead. Cleared
     * after every log.
     */
    val forgotTimer: Boolean = false,
    /** Live active-time total: booked seconds + the current stopwatch reading. */
    val activeSecs: Int = 0,
    val timerPanelVisible: Boolean = true,
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
    /** Show the exercise image inside the sheet (false when the pager already shows it). */
    val descriptionWithImage: Boolean = true,
    /** Exercise the sheet belongs to + its user-saved video link. */
    val descriptionExerciseId: String? = null,
    val descriptionVideoUrl: String? = null,
    val descriptionNote: String? = null,
    /** Whether that note is pinned under the exercise image during the session. */
    val descriptionNotePinned: Boolean = false,
    /** The sheet's description is an on-device machine translation. */
    val descriptionMachine: Boolean = false,
    /** Brief feedback after logging a set with a cadence, cleared a few seconds later. */
    val paceNote: PaceNote? = null,
    /** A user-requested translation is in flight for the open sheet. */
    val descriptionTranslating: Boolean = false,
    /** The shown description is in another language, so offering to translate it makes sense. */
    val descriptionCanTranslate: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Opening an exercise from the overview list. Clearing pendingSwipeTo is the fix for bug D
 * (25/07): a cancelled auto-advance animation left a stale swipe request behind, and it then
 * hijacked the next tap. An explicit tap always wins over a queued advance.
 */
fun SessionUiState.openingExercise(index: Int): SessionUiState =
    copy(currentIndex = index, showList = false, pendingSwipeTo = null)

/**
 * The timed set the panel should offer a countdown for: the current step, when it is an
 * unfinished SECS set. Null for rep sets — the panel keeps its stopwatch then (Allan, 02/08:
 * with a timed set current the panel showed the stopwatch until you pressed play on the row).
 */
fun SessionUiState.pendingTimedSet(): SessionSet? {
    val (exerciseIndex, templateId) = currentStep ?: return null
    val set = exercises.getOrNull(exerciseIndex)?.sets?.firstOrNull { it.templateId == templateId }
        ?: return null
    return set.takeIf { it.valueUnit == ValueUnit.SECS && !it.done }
}

/**
 * The sets after taking a progression suggestion. Only undone REPS working sets change.
 * Weight changes reset the reps to the plan's floor: the point of changing the load is to
 * work through the range again (Allan, 02/08).
 */
fun applySuggestedSets(
    sets: List<SessionSet>,
    suggestion: dev.allan.workoutapp.data.ProgressionEngine.Suggestion,
): List<SessionSet> {
    return sets.map { set ->
        val working = !set.done && set.valueUnit == ValueUnit.REPS &&
            (set.type == SetType.NORMAL || set.type == SetType.FAILURE)
        if (!working) return@map set
        when (suggestion.kind) {
            dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_WEIGHT -> set.copy(
                weightKg = set.weightKg + suggestion.weightIncrementKg,
                value = set.targetMin,
            )
            dev.allan.workoutapp.data.ProgressionEngine.Kind.DROP_WEIGHT -> set.copy(
                weightKg = (set.weightKg - suggestion.weightIncrementKg).coerceAtLeast(0.0),
                value = set.targetMin,
            )
            dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_REP ->
                set.copy(value = set.value + suggestion.repIncrement)
        }
    }
}

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

    /**
     * Chain members that are actually being trained. A member with no logged set is being
     * SKIPPED — and drops out of the interleaving — only once its turn was passed over: some
     * other member has a logged set that comes AFTER the member's first set in the
     * interleaved order. (A superset with one exercise skipped used to park the marker on
     * the skipped one — Allan, 29/08.)
     *
     * Why "passed over" and not "has no log yet" (the 30/09 bug): right after A1 the partner
     * B has no log either, but B1 is exactly what comes next. Treating B as skipped there kept
     * the marker on A2, so the pager never swapped, A2 then went without rest (restSkipped
     * saw B1 still open) and B was only reached after everything else (Allan, 30/09).
     */
    private fun activeMembers(exercises: List<SessionExercise>, chain: List<Int>): List<Int> {
        if (chain.size < 2) return chain
        val order = interleaved(exercises, chain)
        val lastDonePos = order.indexOfLast { it.second.done }
        if (lastDonePos < 0) return chain // nothing logged in this chain yet: everyone is in
        return chain.filter { i ->
            val trained = exercises[i].sets.any { it.done }
            // First appearance of this member in the interleaving = its first turn.
            val firstTurn = order.indexOfFirst { it.first == i }
            trained || firstTurn > lastDonePos
        }
    }

    private fun firstUndone(steps: List<Pair<Int, SessionSet>>): Pair<Int, Long>? =
        steps.firstOrNull { !it.second.done }?.let { (i, s) -> i to s.templateId }

    /**
     * THE marker rule: the next expected set is the first undone one AFTER the set logged
     * last ([last] = exerciseIndex to templateId), in superset-interleaved order; then the
     * chains ahead, wrapping round so skipped exercises and skipped chain members come
     * last. Recomputing from the start of the workout (or of the chain) put the marker back
     * on whatever was skipped every time the state was rebuilt (Allan, 29/08).
     */
    fun nextStepAfter(exercises: List<SessionExercise>, last: Pair<Int, Long>?): Pair<Int, Long>? {
        if (exercises.isEmpty()) return null
        if (last == null || last.first !in exercises.indices) return nextStep(exercises)
        val (fromIndex, templateId) = last
        val startChain = chain(exercises, fromIndex)
        val active = activeMembers(exercises, startChain)
        val ordered = interleaved(exercises, active)
        val pos = ordered.indexOfFirst { it.second.templateId == templateId }
        firstUndone(ordered.drop(pos + 1))?.let { return it }
        val deferred = mutableListOf<Pair<Int, SessionSet>>()
        var i = (startChain.last() + 1) % exercises.size
        while (i !in startChain) {
            val c = chain(exercises, i)
            val act = activeMembers(exercises, c)
            firstUndone(interleaved(exercises, act))?.let { return it }
            (c - act.toSet()).takeIf { it.isNotEmpty() }?.let { deferred += interleaved(exercises, it) }
            i = (c.last() + 1) % exercises.size
        }
        // Wrap: sets left behind in the current chain, then the skipped chain members.
        firstUndone(ordered.take(pos + 1))?.let { return it }
        (startChain - active.toSet()).takeIf { it.isNotEmpty() }?.let { deferred += interleaved(exercises, it) }
        return firstUndone(deferred)
    }

    /**
     * THE rest-skip rule: no rest after a set when a LATER chain member still has an undone
     * set in the same round (A1 → straight to B1; rest after B1). Only members after this one
     * in the chain count, so B1 with A2 open does rest. Reads the chain, not activeMembers:
     * a skipped partner has its round's set undone forever and would otherwise skip every
     * rest — the marker rule, not this one, decides who is skipped.
     */
    fun restSkipped(exercises: List<SessionExercise>, exerciseIndex: Int, set: SessionSet): Boolean {
        val chain = chain(exercises, exerciseIndex)
        if (chain.size < 2) return false
        val after = chain.dropWhile { it != exerciseIndex }.drop(1)
        val round = exercises[exerciseIndex].sets.indexOfFirst { it.templateId == set.templateId }
        return after.any { i -> exercises[i].sets.getOrNull(round)?.done == false }
    }
}

/**
 * B2 (Allan, 29/08): on a CARDIO exercise, the timed set whose countdown just finished is
 * followed by the exercise's next undone timed set — (exerciseIndex, set), or null when the
 * exercise is not cardio, the template is unknown, or nothing timed is left. Rep sets in
 * between are skipped (they cannot be timed).
 */
fun cardioChainNext(exercises: List<SessionExercise>, finishedTemplateId: Long): Pair<Int, SessionSet>? {
    val idx = exercises.indexOfFirst { e -> e.sets.any { it.templateId == finishedTemplateId } }
    if (idx < 0) return null
    val ex = exercises[idx]
    if (!ex.isCardio) return null
    val pos = ex.sets.indexOfFirst { it.templateId == finishedTemplateId }
    return ex.sets.drop(pos + 1).firstOrNull { !it.done && it.valueUnit == ValueUnit.SECS }?.let { idx to it }
}

/**
 * Rough workout time budget: per exercise a 60 s setup buffer, plus per set the work time
 * and its rest. Timed sets count their duration; rep sets use the same cadence rule the
 * booking uses (SetTiming.defaultActiveSecs) so plan and reality agree. Reference only.
 */
fun estimateWorkoutSecs(exercises: List<SessionExercise>): Int =
    exercises.sumOf { ex ->
        60 + ex.sets.sumOf { set ->
            val work = if (set.valueUnit == ValueUnit.SECS) set.value
            else dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, set.tempo, ex.unilateral)
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

    // Serializes startOrResume: it runs from init AND every ON_RESUME, and two overlapping
    // runs used to both miss the running-session check and insert duplicate sessions —
    // the later resume then bound the empty duplicate and "lost" the logged sets.
    // (Declared before init{} — the init coroutine locks it immediately.)
    private val loadMutex = Mutex()

    init {
        viewModelScope.launch {
            startOrResume()
            ticker()
        }
    }

    private suspend fun startOrResume() = loadMutex.withLock {
        val workout = db.planDao().workout(workoutId) ?: return@withLock
        // Resume this workout's own running session — never key off the globally newest
        // one, which may belong to a different workout. Among strays (duplicate-insert
        // bug), the one with logged sets wins; empty ones are deleted, non-empty extras
        // are closed as finished so no logged set is ever dropped.
        val candidates = db.sessionDao().runningSessionsFor(workoutId)
        val decision = dev.allan.workoutapp.session.SessionResume.decide(
            candidates,
            candidates.associate { it.id to db.sessionDao().setLogCount(it.id) },
        )
        val session: Session = if (decision.keep != null) {
            decision.delete.forEach { db.sessionDao().deleteSession(it) }
            decision.close.forEach { id ->
                candidates.first { it.id == id }.let { stray ->
                    db.sessionDao().updateSession(
                        stray.copy(endedAt = System.currentTimeMillis(), status = SessionStatus.FINISHED)
                    )
                }
            }
            decision.keep
        } else {
            val id = db.sessionDao().insertSession(
                Session(workoutId = workoutId, startedAt = System.currentTimeMillis())
            )
            db.sessionDao().session(id)!!
        }
        if (SessionManager.state.value.sessionId != session.id) {
            SessionManager.startSession(session.id, session.startedAt)
        }
        TimerService.start(getApplication())

        val wes = db.planDao().workoutExercises(workoutId).first()
        val templates = db.planDao().setTemplatesForWorkout(workoutId).first()
        // A persisted snapshot means the plan was edited mid-session before a process death —
        // recover the pre-edit templates (and the changed flag below) from the session row,
        // not from the already-edited live tables.
        session.templateSnapshotJson?.let { json ->
            templateSnapshot = kotlinx.serialization.json.Json.decodeFromString(json)
        }
        if (templateSnapshot.isEmpty()) templateSnapshot = templates
        val loggedSets = db.sessionDao().setLogs(session.id)
        val drafts = db.sessionDao().drafts(session.id).associateBy { it.templateId }
        // Chips already answered this session stay gone — recomputing them here is what made
        // a dismissed suggestion come back on every resume (Allan, 02/08).
        val handledSuggestions = db.sessionDao().suggestionStates(session.id)
            .filter { it.handled }.map { it.workoutExerciseId }.toSet()

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
                    // The plan's type, not the last log's: a type changed mid-session was
                    // repainted back to the old letter on every rebuild (Allan, 29/08: A2).
                    type = t.type,
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
                unilateral = we.unilateral,
                pinnedNote = db.sessionDao().pinnedNote(we.exerciseId)?.takeIf { it.isNotBlank() },
                isCardio = exercise?.isCardio == true,
                suggestion = if (we.id in handledSuggestions) null
                else dev.allan.workoutapp.data.ProgressionEngine.suggest(
                    templates = weTemplates,
                    history = previous,
                    primaryMuscles = exercise?.primaryMuscles ?: emptyList(),
                    weightMode = we.weightMode,
                ),
            )
        }
        val lastLogged = lastLoggedStep(loggedSets, exercises)
        _state.value = _state.value.copy(
            sessionId = session.id,
            workoutName = workout.name,
            exercises = exercises,
            lastLogged = lastLogged,
            currentStep = SupersetOrder.nextStepAfter(exercises, lastLogged),
            estimatedTotalSecs = estimateWorkoutSecs(exercises),
            templatesChanged = _state.value.templatesChanged || session.templateSnapshotJson != null,
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

    /** (exerciseIndex, templateId) of the newest row in [logs], or null when nothing is logged. */
    private fun lastLoggedStep(logs: List<SetLog>, exercises: List<SessionExercise>): Pair<Int, Long>? {
        val newest = logs.maxByOrNull { it.completedAt } ?: return null
        val idx = exercises.indexOfFirst { it.workoutExerciseId == newest.workoutExerciseId }
        if (idx < 0) return null
        val templateId = exercises[idx].sets.firstOrNull { it.setIndex == newest.setIndex }?.templateId ?: return null
        return idx to templateId
    }

    /**
     * 4 Hz UI clock: every countdown is recomputed from its wall-clock end instant, so a
     * backgrounded or slow process never drifts. It is also where a rest or a set countdown
     * that reached zero is acted on (stopRest / completeSetCountdown) — the notification's
     * alert fires independently in TimerService.
     */
    private suspend fun ticker() {
        while (true) {
            val timers = SessionManager.state.value
            val now = System.currentTimeMillis()
            val startedAt = timers.sessionStartedAt
            if (startedAt != null && !_state.value.finished) {
                val restRemaining = timers.restEndAt?.let { ((it - now) / 1000L).toInt() }
                if (restRemaining != null && restRemaining <= 0) {
                    SessionManager.stopRest()
                    onRestEnded()
                }
                val countdownRemaining = timers.setCountdownEndAt?.let { ((it - now) / 1000L).toInt() }
                // A run that reached zero books its seconds; only runs stopped by hand are lost.
                if (countdownRemaining != null && countdownRemaining <= 0) {
                    val finished = timers.setCountdownTemplateId
                    SessionManager.completeSetCountdown()
                    if (finished != null) onCountdownFinished(finished)
                }
                val stopwatch = SessionManager.stopwatchSecs(now)
                _state.value = _state.value.copy(
                    elapsedSecs = ((now - startedAt) / 1000L).toInt(),
                    restRemainingSecs = restRemaining?.takeIf { it > 0 },
                    setCountdownRemainingSecs = countdownRemaining?.takeIf { it > 0 },
                    setCountdownPausedSecs = timers.setCountdownPausedSecs,
                    setCountdownTemplateId = timers.setCountdownTemplateId,
                    stopwatchSecs = stopwatch,
                    stopwatchRunning = timers.stopwatchStartedAt != null,
                    activeSecs = timers.activeSecs + stopwatch,
                )
            }
            delay(250)
        }
    }

    /**
     * B2 cardio auto-chain: a finished countdown on a cardio exercise logs its set by itself
     * and lines up the next timed set — started right away when the set has no rest, else the
     * moment the rest ends ([onRestEnded]). Strength exercises are untouched.
     */
    private fun onCountdownFinished(templateId: Long) {
        val exercises = _state.value.exercises
        val idx = exercises.indexOfFirst { e -> e.sets.any { it.templateId == templateId } }
        val set = exercises.getOrNull(idx)?.sets?.firstOrNull { it.templateId == templateId } ?: return
        if (!exercises[idx].isCardio || set.done) return
        val next = cardioChainNext(exercises, templateId)
        logSet(idx, set)
        if (next == null) return
        val restStarted = SessionManager.state.value.restEndAt != null
        if (restStarted) _state.value = _state.value.copy(autoStartTemplateId = next.second.templateId)
        else startSetCountdown(next.second)
    }

    /** The rest is over (naturally or by "Stop rest"): start a queued cardio set, if any (B2). */
    private fun onRestEnded() {
        val pending = _state.value.autoStartTemplateId ?: return
        _state.value = _state.value.copy(autoStartTemplateId = null)
        val set = _state.value.exercises.firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.templateId == pending } }
        if (set != null && !set.done) startSetCountdown(set)
    }

    fun openExercise(index: Int) {
        _state.value = _state.value.openingExercise(index)
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
        // Editing numbers must not move the marker (Allan, 29/08: A5) — it only follows logs.
        _state.value = _state.value.copy(
            exercises = exercises,
            currentStep = SupersetOrder.nextStepAfter(exercises, _state.value.lastLogged),
        )
        saveDraft(set)
    }

    /**
     * A number the user typed or stepped by hand. Same write as [updateSet], plus: editing
     * the value yourself answers the progression chip, which used to keep nagging after a
     * manual change (Allan, 02/08). Logging a set goes through [updateSet] directly and
     * deliberately leaves the chip alone.
     */
    fun editSetValue(exerciseIndex: Int, set: SessionSet) {
        updateSet(exerciseIndex, set)
        if (_state.value.exercises.getOrNull(exerciseIndex)?.suggestion != null) {
            dismissSuggestion(exerciseIndex)
        }
    }

    /**
     * Weight edit with forward-fill ([forwardFillWeight]): later same-type sets that are still
     * 0 kg and undone get the same weight, so one input covers the same-weight-all-sets case.
     */
    fun updateWeight(exerciseIndex: Int, set: SessionSet, weightKg: Double) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val editedIndex = ex.sets.indexOfFirst { it.templateId == set.templateId }
        if (editedIndex < 0) return
        val newSets = forwardFillWeight(ex.sets, editedIndex, weightKg)
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(sets = newSets)
        _state.value = _state.value.copy(
            exercises = exercises,
            currentStep = SupersetOrder.nextStepAfter(exercises, _state.value.lastLogged),
        )
        newSets.filterIndexed { i, s -> i == editedIndex || s.weightKg != ex.sets[i].weightKg }
            .forEach(::saveDraft)
        // Setting the weight yourself answers a weight suggestion (only ever called from the UI).
        if (_state.value.exercises.getOrNull(exerciseIndex)?.suggestion != null) {
            dismissSuggestion(exerciseIndex)
        }
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
        val newSets = applySuggestedSets(ex.sets, s)
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(sets = newSets, suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
        // Drafts are what startOrResume() reads back — without this write the applied
        // numbers died on the next ON_RESUME or template edit (Allan, 02/08).
        newSets.forEach(::saveDraft)
        markSuggestionHandled(ex.workoutExerciseId)
    }

    fun dismissSuggestion(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
        markSuggestionHandled(ex.workoutExerciseId)
    }

    /** Remember, for this session, that the chip was answered — apply, ✕, or a manual edit. */
    private fun markSuggestionHandled(workoutExerciseId: Long) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            db.sessionDao().upsertSuggestionState(
                dev.allan.workoutapp.data.db.SessionSuggestionState(
                    sessionId = sessionId,
                    workoutExerciseId = workoutExerciseId,
                )
            )
        }
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
        // Order of business below, each step shaped by a reported bug:
        //  1. decide the active seconds (decision tree, 02/08 + 30/09 T1);
        //  2. cadence note, only from a real measurement;
        //  3. write the SetLog row and move the marker (lastLogged);
        //  4. retire this set's countdown; 5. rest or no rest (superset rule);
        //  6. auto-advance the pager relative to THIS set (29/08).

        // Active time (docs/FEEDBACK_BATCH_2026-08-02.md A2–A5). Timed sets: whatever their
        // countdown runs already booked — one run per leg counts twice, so the set itself adds
        // nothing; a timed set never run still books its nominal duration. Rep sets: the
        // stopwatch, else nothing when a measurement moments ago already spanned this set
        // (superset partner), else the gap since rest ended, else the cadence estimate.
        // Decision tree documented on SetTiming (02/10).
        val forgot = _state.value.forgotTimer
        val active: Int = when (set.valueUnit) {
            ValueUnit.SECS -> if (SessionManager.bookedRunSecs(set.templateId) != null) 0 else set.value
            ValueUnit.REPS -> {
                // Consumed even when ignored: a forgotten stopwatch must not leak into the
                // next set either.
                val stopwatch = SessionManager.consumeStopwatch()
                when {
                    // The user says the reading is wrong → the estimate, nothing recorded as
                    // measured (a bogus measurement must not "cover" the superset partner).
                    forgot -> dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, set.tempo, ex.unilateral)
                    // Stopwatch ran: its reading is the truth, however long ("timer = 6 min
                    // means I was active 6 min").
                    stopwatch != null ->
                        dev.allan.workoutapp.data.SetTiming.measuredActiveSecs(stopwatch)
                            .also { SessionManager.recordMeasured(stopwatch) }
                    // The previous set's timer covered this one too — book nothing.
                    SessionManager.coveredByPreviousMeasure() -> 0
                    // Untimed: the gap since the rest ended, capped by reps/cadence.
                    else -> SessionManager.gapActiveSecs()
                        ?.let { gap ->
                            dev.allan.workoutapp.data.SetTiming.bookFromGap(gap, set.value, set.tempo, ex.unilateral)
                                .also { SessionManager.recordMeasured(gap) }
                        }
                        ?: dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, set.tempo, ex.unilateral)
                }
            }
        }
        SessionManager.addActiveSecs(active)

        // Cadence check: only a real measurement says anything about pace (a booked default
        // would just compare the estimate with itself), and only being faster than the
        // cadence is a problem — going slower is fine (Allan, 02/08).
        val expectedSecs = dev.allan.workoutapp.data.SetTiming.expectedSecs(set.value, set.tempo, ex.unilateral)
        val paceNote = if (expectedSecs != null && set.valueUnit == ValueUnit.REPS && active > 0 &&
            active != dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, set.tempo, ex.unilateral)
        ) {
            PaceNote(
                exerciseIndex = exerciseIndex,
                fast = dev.allan.workoutapp.data.SetTiming.pace(active, expectedSecs) ==
                    dev.allan.workoutapp.data.SetTiming.Pace.FAST,
                actualSecs = active,
                expectedSecs = expectedSecs,
            )
        } else null

        // The summary shows a timed set as the time its runs really took.
        val loggedActive =
            if (set.valueUnit == ValueUnit.SECS) SessionManager.bookedRunSecs(set.templateId) ?: set.value
            else active

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
                    activeSecs = loggedActive,
                    restSecs = set.restSecs,
                    completedAt = System.currentTimeMillis(),
                )
            )
        }
        _state.value = _state.value.copy(
            lastLogged = exerciseIndex to set.templateId,
            undoableUnlog = null,
            forgotTimer = false,
            // Logging the queued set by hand retires the auto-start.
            autoStartTemplateId = _state.value.autoStartTemplateId?.takeIf { it != set.templateId },
        )
        updateSet(exerciseIndex, set.copy(done = true))

        // Superset pairs alternate without rest: A1, B1 (no pause after A1), rest after B1.
        // Legacy SUPERSET set rows keep their no-rest behavior too.
        // Logging a timed set retires its countdown (running or paused) — otherwise a
        // paused timer would linger in the panel with no set left to time. Only completed
        // runs book time (SessionManager.completeSetCountdown); a countdown cut short here
        // is discarded on purpose.
        if (set.valueUnit == ValueUnit.SECS &&
            SessionManager.state.value.setCountdownTemplateId == set.templateId
        ) {
            SessionManager.cancelSetCountdown()
            TimerService.showDefault(getApplication())
        }

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
        val next = SupersetOrder.nextStepAfter(_state.value.exercises, exerciseIndex to set.templateId)
        _state.value = when {
            next == null -> _state.value.copy(
                timerPanelVisible = true,
                showList = true,
                paceNote = paceNote,
            )
            next.first != exerciseIndex -> _state.value.copy(
                timerPanelVisible = true,
                pendingSwipeTo = next.first,
                swipeToken = _state.value.swipeToken + 1,
                paceNote = paceNote,
            )
            else -> _state.value.copy(timerPanelVisible = true, paceNote = paceNote)
        }
    }

    /** Undo a mistaken checkmark: delete the SetLog row and give back its active time. */
    private fun unlogSet(exerciseIndex: Int, set: SessionSet) {
        val sessionId = _state.value.sessionId ?: return
        val ex = _state.value.exercises[exerciseIndex]
        viewModelScope.launch {
            val removed = db.sessionDao().setLog(sessionId, ex.workoutExerciseId, set.setIndex)?.also { log ->
                // The log carries what the set really cost, including seconds booked by its
                // countdown runs — give all of it back and forget the runs.
                log.activeSecs?.let { SessionManager.addActiveSecs(-it) }
                SessionManager.clearBookedRuns(set.templateId)
                db.sessionDao().deleteSetLog(sessionId, ex.workoutExerciseId, set.setIndex)
            }
            // The marker re-anchors on whatever was logged before this one.
            val remaining = db.sessionDao().setLogs(sessionId)
            _state.value = _state.value.copy(
                lastLogged = lastLoggedStep(remaining, _state.value.exercises),
                undoableUnlog = removed,
            )
            updateSet(exerciseIndex, set.copy(done = false))
        }
    }

    /**
     * Put back the set an accidental tap un-logged: the same log row (its seconds included),
     * done again, marker after it (Allan, 29/08: C1). No-op once the offer was consumed.
     */
    fun undoUnlog() {
        val log = _state.value.undoableUnlog ?: return
        val idx = _state.value.exercises.indexOfFirst { it.workoutExerciseId == log.workoutExerciseId }
        val set = _state.value.exercises.getOrNull(idx)?.sets?.firstOrNull { it.setIndex == log.setIndex } ?: return
        _state.value = _state.value.copy(undoableUnlog = null)
        viewModelScope.launch {
            db.sessionDao().insertSetLog(log.copy(id = 0))
            log.activeSecs?.let { SessionManager.addActiveSecs(it) }
            _state.value = _state.value.copy(lastLogged = idx to set.templateId)
            updateSet(idx, set.copy(done = true))
        }
    }

    fun dismissUndoUnlog() {
        _state.value = _state.value.copy(undoableUnlog = null)
    }

    fun clearPaceNote() {
        _state.value = _state.value.copy(paceNote = null)
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
    fun openDescription(exerciseId: String, withImage: Boolean = true) {
        viewModelScope.launch {
            suspend fun load() {
                val translations = db.exerciseDao().translations(exerciseId)
                val best = translations.firstOrNull { it.lang == lang }
                    ?: translations.firstOrNull { it.lang == "en" } ?: translations.firstOrNull()
                _state.value = _state.value.copy(
                    descriptionSheet = best?.description.orEmpty(),
                    descriptionWithImage = withImage,
                    descriptionExerciseId = exerciseId,
                    descriptionVideoUrl = db.exerciseDao().videoLink(exerciseId),
                    descriptionNote = db.sessionDao().noteText(exerciseId) ?: "",
                    descriptionNotePinned = db.sessionDao().noteIsPinned(exerciseId),
                    descriptionMachine = best?.machine == true,
                )
            }
            load()
            // English-only exercise + non-English app: machine-translate and refresh, but
            // only while the sheet is still showing this exercise.
            if (dev.allan.workoutapp.data.AutoTranslate.ensure(db, exerciseId, lang) &&
                _state.value.descriptionExerciseId == exerciseId
            ) load()
            // Only offer the manual action when the text really is in another language.
            val offer = dev.allan.workoutapp.data.AutoTranslate.needsTranslation(
                _state.value.descriptionSheet.orEmpty(), lang
            )
            if (_state.value.descriptionExerciseId == exerciseId) {
                _state.value = _state.value.copy(descriptionCanTranslate = offer)
            }
        }
    }

    /**
     * User asked to translate the description on screen. Needed because an imported plan writes a
     * single row tagged with the app language holding a Portuguese name and an English
     * description, which AutoTranslate.ensure declines to touch (Allan, 26/07).
     */
    fun translateDescription() {
        val exerciseId = _state.value.descriptionExerciseId ?: return
        if (_state.value.descriptionTranslating) return
        _state.value = _state.value.copy(descriptionTranslating = true)
        viewModelScope.launch {
            val ok = dev.allan.workoutapp.data.AutoTranslate.translateDescription(db, exerciseId, lang)
            if (_state.value.descriptionExerciseId != exerciseId) return@launch
            if (ok) {
                val best = db.exerciseDao().translations(exerciseId).firstOrNull { it.lang == lang }
                _state.value = _state.value.copy(
                    descriptionSheet = best?.description ?: _state.value.descriptionSheet,
                    descriptionMachine = best?.machine == true,
                    descriptionCanTranslate = false,
                )
            }
            _state.value = _state.value.copy(descriptionTranslating = false)
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
        if (_state.value.autoStartTemplateId == set.templateId) {
            _state.value = _state.value.copy(autoStartTemplateId = null)
        }
        SessionManager.startSetCountdown(set.value, set.templateId)
        TimerService.showCountdown(
            getApplication(),
            System.currentTimeMillis() + set.value * 1000L,
            getApplication<Application>().getString(dev.allan.workoutapp.R.string.set_timer),
        )
        _state.value = _state.value.copy(timerPanelVisible = true)
    }

    /** Play on the panel with a timed set current: start that set's countdown. */
    fun startCurrentSetCountdown() {
        _state.value.pendingTimedSet()?.let(::startSetCountdown)
    }

    /** Pause the running set countdown; the notification alert is cancelled with it. */
    fun pauseSetCountdown() {
        SessionManager.pauseSetCountdown()
        TimerService.showDefault(getApplication())
    }

    /** Resume a paused set countdown and reschedule its end-of-set alert. */
    fun resumeSetCountdown() {
        val endAt = SessionManager.resumeSetCountdown() ?: return
        TimerService.showCountdown(
            getApplication(),
            endAt,
            getApplication<Application>().getString(dev.allan.workoutapp.R.string.set_timer),
        )
    }

    /** Stop/reset the set countdown without logging anything. */
    fun stopSetCountdown() {
        SessionManager.cancelSetCountdown()
        TimerService.showDefault(getApplication())
    }

    /** The "forgot?" box in the timer panel; see SessionUiState.forgotTimer. */
    fun setForgotTimer(value: Boolean) {
        _state.value = _state.value.copy(forgotTimer = value)
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
        onRestEnded()
    }

    /** @param pinned null keeps the note's current pin state. */
    fun saveNote(exerciseId: String, text: String, pinned: Boolean? = null) {
        _state.value = _state.value.copy(
            descriptionNote = text,
            descriptionNotePinned = pinned ?: _state.value.descriptionNotePinned,
        )
        viewModelScope.launch {
            PlanRepo.saveExerciseNote(db, exerciseId, text, pinned)
            val isPinned = pinned ?: db.sessionDao().noteIsPinned(exerciseId)
            val shown = text.takeIf { isPinned && it.isNotBlank() }
            _state.value = _state.value.copy(
                exercises = _state.value.exercises.map {
                    if (it.exerciseId == exerciseId) it.copy(pinnedNote = shown) else it
                }
            )
        }
    }

    /** In-session plan edits — write to the set template, then refresh the SessionSet list.
     *  Marks templatesChanged so the end flow can offer keep vs one-time. */
    private fun editTemplate(templateId: Long, transform: (dev.allan.workoutapp.data.db.SetTemplate) -> dev.allan.workoutapp.data.db.SetTemplate) {
        viewModelScope.launch {
            val t = db.planDao().setTemplatesForWorkout(workoutId).first().firstOrNull { it.id == templateId } ?: return@launch
            db.planDao().updateSetTemplate(transform(t))
            markTemplatesChanged()
            startOrResume()
        }
    }

    /** Persist the pre-edit snapshot with the session on the FIRST plan edit, so the
     *  keep-vs-one-time end prompt (and the one-time restore) survive process death. */
    private suspend fun markTemplatesChanged() {
        _state.value.sessionId?.let { sessionId ->
            val session = db.sessionDao().session(sessionId)
            if (session != null && session.templateSnapshotJson == null) {
                db.sessionDao().updateSession(
                    session.copy(
                        templateSnapshotJson =
                            kotlinx.serialization.json.Json.encodeToString(templateSnapshot)
                    )
                )
            }
        }
        _state.value = _state.value.copy(templatesChanged = true)
    }

    /** Switch how the exercise's weight is read (total / per dumbbell / per side).
     *  Plan-level like the editor's control — written straight to workout_exercise. */
    fun setWeightMode(exerciseIndex: Int, mode: dev.allan.workoutapp.data.db.WeightMode) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        viewModelScope.launch {
            db.planDao().workoutExercise(ex.workoutExerciseId)?.let {
                db.planDao().updateWorkoutExercise(it.copy(weightMode = mode))
            }
            _state.value = _state.value.copy(
                exercises = _state.value.exercises.mapIndexed { i, e ->
                    if (i == exerciseIndex) e.copy(weightMode = mode) else e
                }
            )
        }
    }

    fun setSetType(set: SessionSet, type: SetType) = editTemplate(set.templateId) { it.copy(type = type) }

    /** Cadence is per exercise (Allan, 24/07): one edit writes every set template of it. */
    fun setExerciseTempo(exerciseIndex: Int, tempo: String) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        viewModelScope.launch {
            db.planDao().setTemplatesForWorkout(workoutId).first()
                .filter { it.workoutExerciseId == ex.workoutExerciseId }
                .forEach { db.planDao().updateSetTemplate(it.copy(tempo = tempo)) }
            markTemplatesChanged()
            startOrResume()
        }
    }
    fun setSetTarget(set: SessionSet, min: Int, max: Int?) =
        editTemplate(set.templateId) { it.copy(targetValue = min, targetValueMax = max?.takeIf { m -> m > min }) }

    fun addSessionSet(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        // Duplicate the current last row, including its live-typed weight/reps (which live in the
        // draft, not the template), so the new set mirrors what's on screen before it was added.
        val lastLive = ex.sets.lastOrNull()
        viewModelScope.launch {
            val templates = db.planDao().setTemplatesForWorkout(workoutId).first()
                .filter { it.workoutExerciseId == ex.workoutExerciseId }.sortedBy { it.setIndex }
            val last = templates.lastOrNull()
            val newId = db.planDao().insertSetTemplate(
                (last ?: dev.allan.workoutapp.data.db.SetTemplate(workoutExerciseId = ex.workoutExerciseId, setIndex = -1))
                    .copy(id = 0, setIndex = (last?.setIndex ?: -1) + 1)
            )
            if (lastLive != null) {
                db.sessionDao().upsertDraft(
                    dev.allan.workoutapp.data.db.SessionSetDraft(
                        sessionId = _state.value.sessionId ?: return@launch,
                        templateId = newId,
                        weightKg = lastLive.weightKg,
                        value = lastLive.value,
                    )
                )
            }
            markTemplatesChanged()
            startOrResume()
        }
    }

    fun removeSessionSet(set: SessionSet) {
        viewModelScope.launch {
            db.planDao().deleteSetTemplate(set.templateId)
            markTemplatesChanged()
            startOrResume()
        }
    }

    /**
     * Long-press drag-reorder of a set mid-session. Renumbers the templates' setIndex AND
     * remaps any already-logged sets (SetLog keys on setIndex) so completed rows follow the move.
     * Drafts key on templateId, so they need no remap.
     */
    fun moveSessionSet(exerciseIndex: Int, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val sessionId = _state.value.sessionId ?: return
        val weId = ex.workoutExerciseId
        viewModelScope.launch {
            val templates = db.planDao().setTemplatesForWorkout(workoutId).first()
                .filter { it.workoutExerciseId == weId }.sortedBy { it.setIndex }.toMutableList()
            if (fromIndex !in templates.indices || toIndex !in templates.indices) return@launch
            // Snapshot logs by their (old) setIndex before we renumber anything.
            val logsByOld = db.sessionDao().setLogs(sessionId)
                .filter { it.workoutExerciseId == weId }.associateBy { it.setIndex }
            templates.add(toIndex, templates.removeAt(fromIndex))
            // Clear this exercise's logs, then rewrite each at its template's new position.
            logsByOld.keys.forEach { db.sessionDao().deleteSetLog(sessionId, weId, it) }
            templates.forEachIndexed { newIdx, t ->
                if (t.setIndex != newIdx) db.planDao().updateSetTemplate(t.copy(setIndex = newIdx))
                logsByOld[t.setIndex]?.let { db.sessionDao().insertSetLog(it.copy(id = 0, setIndex = newIdx)) }
            }
            markTemplatesChanged()
            startOrResume()
        }
    }

    /** Undo mid-session plan edits (the "one-time" choice) by restoring the entry snapshot. */
    private suspend fun restorePlanTemplates() {
        db.planDao().deleteSetTemplatesForWorkout(workoutId)
        db.planDao().restoreSetTemplates(templateSnapshot)
    }

    /**
     * End the session. save=false discards logged sets (status DISCARDED, rows kept).
     * The stopwatch reading is booked first so nothing measured is lost; drafts and
     * suggestion states are per-session scratch and go; the template snapshot is spent.
     */
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
                    // The keep-vs-one-time decision was just made — the snapshot is spent.
                    templateSnapshotJson = null,
                )
            )
            db.sessionDao().deleteDrafts(sessionId)
            db.sessionDao().deleteSuggestionStates(sessionId)
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
