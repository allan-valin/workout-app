package dev.allan.workoutapp.ui.plans

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Workout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Backs Home / Active / Inactive tabs and plan creation (blank or via wizard). */
class PlansViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    val activePlans: StateFlow<List<Plan>> = db.planDao().plans(true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val inactivePlans: StateFlow<List<Plan>> = db.planDao().plans(false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayWorkouts: StateFlow<List<Workout>> =
        db.planDao().workoutsForDay(LocalDate.now().dayOfWeek.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
