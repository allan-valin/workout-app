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

    /** Deletes a workout including its exercises and set templates (no FK cascades). */
    suspend fun deleteWorkoutDeep(db: AppDatabase, workoutId: Long) {
        db.planDao().deleteSetTemplatesForWorkout(workoutId)
        db.planDao().deleteWorkoutExercisesForWorkout(workoutId)
        db.planDao().deleteWorkout(workoutId)
    }

    /** Deletes a plan and all its workouts. Sessions/logs are kept (history). */
    suspend fun deletePlanDeep(db: AppDatabase, planId: Long) {
        db.planDao().workoutsList(planId).forEach { deleteWorkoutDeep(db, it.id) }
        db.planDao().deletePlan(planId)
    }

    /** Copies a workout (exercises + set templates) into [targetPlanId]; returns new id. */
    suspend fun copyWorkout(db: AppDatabase, sourceWorkoutId: Long, targetPlanId: Long): Long? {
        val source = db.planDao().workout(sourceWorkoutId) ?: return null
        val order = db.planDao().workoutsList(targetPlanId).size
        val newWorkoutId = db.planDao().insertWorkout(
            source.copy(id = 0, planId = targetPlanId, orderIndex = order, daysOfWeek = source.daysOfWeek)
        )
        db.planDao().workoutExercisesList(sourceWorkoutId).forEach { we ->
            val newWeId = db.planDao().insertWorkoutExercise(we.copy(id = 0, workoutId = newWorkoutId))
            db.planDao().setTemplatesList(we.id).forEach { t ->
                db.planDao().insertSetTemplate(t.copy(id = 0, workoutExerciseId = newWeId))
            }
        }
        return newWorkoutId
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
