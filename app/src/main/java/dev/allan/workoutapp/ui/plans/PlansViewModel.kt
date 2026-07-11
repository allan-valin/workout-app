package dev.allan.workoutapp.ui.plans

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Session
import dev.allan.workoutapp.data.db.Workout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Backs Home / Active / Inactive tabs and plan creation (blank or via wizard). */
class PlansViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    val activePlans: StateFlow<List<Plan>> = db.planDao().plans(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inactivePlans: StateFlow<List<Plan>> = db.planDao().plans(false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every plan, active first — the Archive workout expander lists memberships from this. */
    val allPlans: StateFlow<List<Plan>> =
        kotlinx.coroutines.flow.combine(activePlans, inactivePlans) { a, b -> a + b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The single active plan (one-active-plan rule) — drives the Active tab. */
    val activePlan: StateFlow<Plan?> = db.planDao().activePlanFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Non-archived workouts of the active plan, in plan order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePlanWorkouts: StateFlow<List<Workout>> = db.planDao().activePlanFlow()
        .flatMapLatest { plan ->
            if (plan == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else db.planDao().workouts(plan.id)
        }
        .map { it.filter { w -> !w.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every workout (Archive → Workouts). */
    val allWorkouts: StateFlow<List<Workout>> = db.planDao().allWorkoutsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** EXPERIMENTAL: muscle-map load (wger muscle id -> load) unioned across the active cycle. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activePlanMuscleLoad: StateFlow<Map<Int, Float>> =
        activePlanWorkouts.map { workouts ->
            val pairs = mutableListOf<Pair<List<Int>, List<Int>>>()
            workouts.forEach { w ->
                db.planDao().workoutExercisesList(w.id).forEach { we ->
                    val ex = db.exerciseDao().exercise(we.exerciseId)
                    pairs += (ex?.primaryMuscles ?: emptyList()) to (ex?.secondaryMuscles ?: emptyList())
                }
            }
            dev.allan.workoutapp.data.MuscleMap.muscleLoad(pairs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Workout ids currently in the active plan — labels them in Archive/Add screens. */
    val activeWorkoutIds: StateFlow<Set<Long>> = db.planDao().activePlanWorkoutIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Add an existing workout to the active plan: LINK (shared) or BASE (independent copy). */
    fun addWorkoutsToActivePlan(ids: Set<Long>, asCopy: Boolean) {
        viewModelScope.launch {
            // Read the active plan straight from the DB — a fresh nav-scoped VM's activePlan
            // StateFlow can still be null (async Room emit) when the button is tapped.
            val planId = db.planDao().activePlanNow()?.id ?: return@launch
            addToPlan(planId, ids, asCopy)
        }
    }

    /** Add existing workouts to a SPECIFIC plan (used from the plan editor, planId known). */
    fun addWorkoutsToPlan(planId: Long, ids: Set<Long>, asCopy: Boolean) {
        viewModelScope.launch { addToPlan(planId, ids, asCopy) }
    }

    private suspend fun addToPlan(planId: Long, ids: Set<Long>, asCopy: Boolean) {
        ids.forEach { id ->
            if (asCopy) dev.allan.workoutapp.data.PlanRepo.copyWorkout(db, id, planId)
            else dev.allan.workoutapp.data.PlanRepo.linkWorkout(db, id, planId)
        }
    }

    /** Archive "import as-is": move workouts into the Archive (detached from every plan). */
    fun moveWorkoutsToArchive(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { dev.allan.workoutapp.data.PlanRepo.archiveWorkoutFully(db, it) }
        }
    }

    /** Archive "use as base": independent archived copies, no plan link. */
    fun copyWorkoutsToArchive(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { dev.allan.workoutapp.data.PlanRepo.copyWorkout(db, it, targetPlanId = null) }
        }
    }

    /** Create a standalone workout straight into the Archive (no plan link, archived=true). */
    fun createArchivedWorkout(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = db.planDao().insertWorkout(
                Workout(
                    name = dev.allan.workoutapp.data.PlanRepo.uniqueWorkoutName(db, name),
                    daysOfWeek = emptyList(),
                    archived = true,
                )
            )
            onCreated(id)
        }
    }

    fun archiveWorkout(workoutId: Long) {
        viewModelScope.launch {
            val planId = db.planDao().activePlanNow()?.id ?: return@launch
            dev.allan.workoutapp.data.PlanRepo.archiveWorkout(db, workoutId, planId)
        }
    }

    fun deleteWorkout(workoutId: Long) {
        viewModelScope.launch { dev.allan.workoutapp.data.PlanRepo.deleteWorkoutDeep(db, workoutId) }
    }

    fun exportWorkout(workoutId: Long, uri: android.net.Uri) {
        viewModelScope.launch {
            val text = dev.allan.workoutapp.data.transfer.PlanTransfer.exportWorkout(db, workoutId)
                ?: return@launch
            getApplication<Application>().contentResolver.openOutputStream(uri)
                ?.use { it.write(text.toByteArray()) }
        }
    }

    /** Non-null while a session is RUNNING — drives the Home "resume" card. */
    val runningSession: StateFlow<Session?> = db.sessionDao().runningSessionFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val todayWorkouts: StateFlow<List<Workout>> =
        db.planDao().workoutsForDay(LocalDate.now().dayOfWeek.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** workoutId -> exercise count, for the badges on Today cards. */
    val exerciseCounts: StateFlow<Map<Long, Int>> = db.planDao().exerciseCounts()
        .map { rows -> rows.associate { it.workoutId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** workoutId -> last finished-session epoch millis, for the "last trained" row line. */
    val lastTrained: StateFlow<Map<Long, Long>> = db.planDao().lastTrainedFlow()
        .map { rows -> rows.associate { it.workoutId to it.lastAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** planId -> ordered member workoutIds; drives the Archive expanders + plan last-trained. */
    val planWorkoutIds: StateFlow<Map<Long, List<Long>>> = db.planDao().allPlanWorkoutsFlow()
        .map { rows -> rows.groupBy({ it.planId }, { it.workoutId }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** planId -> last trained millis = max over its member workouts. */
    val planLastTrained: StateFlow<Map<Long, Long>> =
        kotlinx.coroutines.flow.combine(planWorkoutIds, lastTrained) { members, trained ->
            members.mapNotNull { (planId, ids) ->
                ids.mapNotNull { trained[it] }.maxOrNull()?.let { planId to it }
            }.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** ISO days (1=Mon..7=Sun) of the current week with a finished session — week circles. */
    val completedWeekDays: StateFlow<Set<Int>> = db.sessionDao().finishedSessionsFlow()
        .map { sessions ->
            val today = LocalDate.now()
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            sessions.mapNotNull { s ->
                val day = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                day.dayOfWeek.value.takeIf { !day.isBefore(monday) && !day.isAfter(today) }
            }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * A newly created plan becomes THE active one (one-active-plan rule) unless
     * [activate] is false (Archive → Cycles creation keeps it archived on request).
     * Names are made unique ("MM/yy" suffix on collision).
     */
    fun createBlankPlan(name: String, cycleWeeks: Int?, activate: Boolean = true, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            if (activate) db.planDao().deactivateAllPlans()
            val id = db.planDao().insertPlan(
                Plan(
                    name = dev.allan.workoutapp.data.PlanRepo.uniquePlanName(db, name),
                    isActive = activate,
                    cycleWeeks = cycleWeeks,
                    startedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            onCreated(id)
        }
    }

    /** Wizard path: create the plan plus one empty workout per suggested split day. */
    fun createWizardPlan(
        name: String,
        cycleWeeks: Int?,
        days: List<Pair<String, Int>>, // resolved label -> iso day
        activate: Boolean = true,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            if (activate) db.planDao().deactivateAllPlans()
            val planId = db.planDao().insertPlan(
                Plan(
                    name = dev.allan.workoutapp.data.PlanRepo.uniquePlanName(db, name),
                    isActive = activate,
                    cycleWeeks = cycleWeeks,
                    startedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            // Workout names must be unique too — including within this batch.
            val taken = db.planDao().workoutNames().toMutableList()
            days.forEachIndexed { index, (label, isoDay) ->
                val wName = dev.allan.workoutapp.data.PlanRepo.uniqueName(taken, label)
                taken += wName
                val wId = db.planDao().insertWorkout(Workout(name = wName, daysOfWeek = listOf(isoDay)))
                db.planDao().insertPlanWorkout(
                    dev.allan.workoutapp.data.db.PlanWorkout(planId = planId, workoutId = wId, orderIndex = index)
                )
            }
            onCreated(planId)
        }
    }

    /** At most one plan is active — activating one deactivates the rest. */
    fun setPlanActive(plan: Plan, active: Boolean) {
        viewModelScope.launch {
            db.planDao().deactivateAllPlans()
            if (active) db.planDao().updatePlan(plan.copy(isActive = true))
        }
    }

    /** planId -> workout count, for the plan-card subtitle. */
    val workoutCounts: StateFlow<Map<Long, Int>> = db.planDao().workoutCounts()
        .map { rows -> rows.associate { it.planId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun deletePlans(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { dev.allan.workoutapp.data.PlanRepo.deletePlanDeep(db, it) }
        }
    }
}
