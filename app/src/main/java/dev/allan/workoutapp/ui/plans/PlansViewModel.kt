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

    fun createBlankPlan(name: String, cycleWeeks: Int?, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = db.planDao().insertPlan(
                Plan(name = name, cycleWeeks = cycleWeeks, startedAt = System.currentTimeMillis(), createdAt = System.currentTimeMillis())
            )
            onCreated(id)
        }
    }

    /** Wizard path: create the plan plus one empty workout per suggested split day. */
    fun createWizardPlan(
        name: String,
        cycleWeeks: Int?,
        days: List<Pair<String, Int>>, // resolved label -> iso day
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            val planId = db.planDao().insertPlan(
                Plan(name = name, cycleWeeks = cycleWeeks, startedAt = System.currentTimeMillis(), createdAt = System.currentTimeMillis())
            )
            days.forEachIndexed { index, (label, isoDay) ->
                db.planDao().insertWorkout(
                    Workout(planId = planId, name = label, orderIndex = index, daysOfWeek = listOf(isoDay))
                )
            }
            onCreated(planId)
        }
    }

    fun setPlanActive(plan: Plan, active: Boolean) {
        viewModelScope.launch { db.planDao().updatePlan(plan.copy(isActive = active)) }
    }

    fun deletePlan(plan: Plan) {
        viewModelScope.launch { db.planDao().deletePlan(plan.id) }
    }
}
