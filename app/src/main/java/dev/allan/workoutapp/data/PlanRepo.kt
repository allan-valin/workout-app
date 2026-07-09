package dev.allan.workoutapp.data

import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.WorkoutExercise
import java.util.UUID

/** Shared plan-editing operations used by the workout editor and the exercise picker. */
object PlanRepo {

    /** Adds an exercise to a workout with 3 default sets. Returns the WorkoutExercise id. */
    suspend fun addExerciseToWorkout(db: AppDatabase, workoutId: Long, exerciseId: String): Long {
        val weId = db.planDao().insertWorkoutExercise(
            WorkoutExercise(
                workoutId = workoutId,
                exerciseId = exerciseId,
                orderIndex = db.planDao().nextExerciseOrder(workoutId),
            )
        )
        repeat(3) { i ->
            db.planDao().insertSetTemplate(
                SetTemplate(workoutExerciseId = weId, setIndex = i)
            )
        }
        return weId
    }

    /**
     * Fully deletes a workout: its exercises, set templates, and every plan membership
     * (no FK cascades). Use for "delete forever"; to just remove it from one plan use
     * [detachWorkout].
     */
    suspend fun deleteWorkoutDeep(db: AppDatabase, workoutId: Long) {
        db.planDao().deleteSetTemplatesForWorkout(workoutId)
        db.planDao().deleteWorkoutExercisesForWorkout(workoutId)
        db.planDao().deletePlanWorkoutsForWorkout(workoutId)
        db.planDao().deleteWorkout(workoutId)
    }

    /**
     * Deletes a plan: removes its workout memberships and the plan row. The workouts
     * themselves are kept (they may be linked into other plans, and otherwise remain
     * reachable from Archive → Workouts). Sessions/logs are kept (history).
     */
    suspend fun deletePlanDeep(db: AppDatabase, planId: Long) {
        db.planDao().deletePlanWorkoutsForPlan(planId)
        db.planDao().deletePlan(planId)
    }

    /** LINK: makes an existing workout a member of [planId] (shared — edits propagate). */
    suspend fun linkWorkout(db: AppDatabase, workoutId: Long, planId: Long) {
        db.planDao().insertPlanWorkout(
            dev.allan.workoutapp.data.db.PlanWorkout(
                planId = planId,
                workoutId = workoutId,
                orderIndex = db.planDao().nextWorkoutOrder(planId),
            )
        )
        // Re-linking an archived workout brings it back into active use.
        db.planDao().workout(workoutId)?.takeIf { it.archived }?.let {
            db.planDao().updateWorkout(it.copy(archived = false))
        }
    }

    /** Removes a workout from ONE plan without deleting the workout itself. */
    suspend fun detachWorkout(db: AppDatabase, workoutId: Long, planId: Long) {
        db.planDao().deletePlanWorkout(planId, workoutId)
    }

    /**
     * Archives a workout: detaches it from [planId] and flags it archived, so it leaves
     * the active plan and lives in Archive → Workouts (history kept).
     */
    suspend fun archiveWorkout(db: AppDatabase, workoutId: Long, planId: Long) {
        db.planDao().deletePlanWorkout(planId, workoutId)
        db.planDao().workout(workoutId)?.let { db.planDao().updateWorkout(it.copy(archived = true)) }
    }

    /**
     * BASE: copies a workout (exercises + set templates) into an independent new workout
     * and links the copy into [targetPlanId]. Returns the new workout id.
     */
    suspend fun copyWorkout(db: AppDatabase, sourceWorkoutId: Long, targetPlanId: Long): Long? {
        val source = db.planDao().workout(sourceWorkoutId) ?: return null
        val newWorkoutId = db.planDao().insertWorkout(
            source.copy(id = 0, archived = false, daysOfWeek = source.daysOfWeek)
        )
        db.planDao().insertPlanWorkout(
            dev.allan.workoutapp.data.db.PlanWorkout(
                planId = targetPlanId,
                workoutId = newWorkoutId,
                orderIndex = db.planDao().nextWorkoutOrder(targetPlanId),
            )
        )
        db.planDao().workoutExercisesList(sourceWorkoutId).forEach { we ->
            val newWeId = db.planDao().insertWorkoutExercise(we.copy(id = 0, workoutId = newWorkoutId))
            db.planDao().setTemplatesList(we.id).forEach { t ->
                db.planDao().insertSetTemplate(t.copy(id = 0, workoutExerciseId = newWeId))
            }
        }
        return newWorkoutId
    }

    /** Creates a fresh empty workout and links it into [planId]. Returns the new id. */
    suspend fun createWorkout(db: AppDatabase, planId: Long, name: String, daysOfWeek: List<Int>): Long {
        val id = db.planDao().insertWorkout(
            dev.allan.workoutapp.data.db.Workout(name = name, daysOfWeek = daysOfWeek)
        )
        db.planDao().insertPlanWorkout(
            dev.allan.workoutapp.data.db.PlanWorkout(
                planId = planId,
                workoutId = id,
                orderIndex = db.planDao().nextWorkoutOrder(planId),
            )
        )
        return id
    }

    /** Creates a custom exercise plus its translation in [lang]; returns the exercise id. */
    suspend fun createCustomExercise(
        db: AppDatabase,
        name: String,
        description: String,
        primaryMuscleId: Int?,
        isCardio: Boolean,
        lang: String,
    ): String {
        val id = "custom:${UUID.randomUUID()}"
        db.exerciseDao().insertExercises(
            listOf(
                Exercise(
                    id = id,
                    category = null,
                    primaryMuscles = listOfNotNull(primaryMuscleId),
                    secondaryMuscles = emptyList(),
                    equipment = emptyList(),
                    imageUrl = null,
                    isCustom = true,
                    isCardio = isCardio,
                )
            )
        )
        db.exerciseDao().insertTranslations(
            listOf(
                ExerciseTranslation(
                    exerciseId = id, lang = lang, name = name,
                    description = description, aliases = emptyList(),
                )
            )
        )
        return id
    }

    /** Resolves an exercise display name for a language, falling back to en, then any. */
    suspend fun displayName(db: AppDatabase, exerciseId: String, lang: String): String {
        val all = db.exerciseDao().translations(exerciseId)
        return (all.firstOrNull { it.lang == lang } ?: all.firstOrNull { it.lang == "en" }
            ?: all.firstOrNull())?.name ?: exerciseId
    }
}
