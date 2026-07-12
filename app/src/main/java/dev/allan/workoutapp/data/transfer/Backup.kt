package dev.allan.workoutapp.data.transfer

import android.content.Context
import dev.allan.workoutapp.data.Settings
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.BodyMetric
import dev.allan.workoutapp.data.db.Exercise
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Session
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.Workout
import dev.allan.workoutapp.data.db.WorkoutExercise
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Full app backup for phone migration. Restore assumes a FRESH install (row ids are
 * kept verbatim; restoring on top of existing user data may collide and REPLACE rows).
 */
object Backup {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class File(
        val schema: Int = 1,
        val heightCm: Double? = null,
        val customExercises: List<Exercise> = emptyList(),
        val customTranslations: List<ExerciseTranslation> = emptyList(),
        val plans: List<Plan> = emptyList(),
        val workouts: List<Workout> = emptyList(),
        /** Plan↔workout membership. Absent in pre-v4 backups (those lose plan membership). */
        val planWorkouts: List<dev.allan.workoutapp.data.db.PlanWorkout> = emptyList(),
        val workoutExercises: List<WorkoutExercise> = emptyList(),
        val setTemplates: List<SetTemplate> = emptyList(),
        val sessions: List<Session> = emptyList(),
        val setLogs: List<SetLog> = emptyList(),
        val bodyMetrics: List<BodyMetric> = emptyList(),
        val exerciseLinks: List<dev.allan.workoutapp.data.db.ExerciseLink> = emptyList(),
        /** Starred exercises. Absent in pre-v6 backups. */
        val favorites: List<dev.allan.workoutapp.data.db.ExerciseFavorite> = emptyList(),
        val userImages: List<dev.allan.workoutapp.data.db.ExerciseUserImage> = emptyList(),
        val imagePrefs: List<dev.allan.workoutapp.data.db.ExerciseImagePref> = emptyList(),
    )

    suspend fun export(context: Context, db: AppDatabase): String {
        val customExercises = db.exerciseDao().customExercises()
        val customTranslations = customExercises.flatMap { db.exerciseDao().translations(it.id) }
        val plans = db.planDao().allPlans()
        val workouts = db.planDao().allWorkoutsList()
        val workoutExercises = workouts.flatMap { db.planDao().workoutExercisesList(it.id) }
        val setTemplates = workoutExercises.flatMap { db.planDao().setTemplatesList(it.id) }
        return json.encodeToString(
            File(
                heightCm = Settings.heightCm(context).first(),
                customExercises = customExercises,
                customTranslations = customTranslations,
                plans = plans,
                workouts = workouts,
                planWorkouts = db.planDao().allPlanWorkouts(),
                workoutExercises = workoutExercises,
                setTemplates = setTemplates,
                sessions = db.sessionDao().allSessions(),
                setLogs = db.sessionDao().allSetLogs(),
                bodyMetrics = db.sessionDao().allBodyMetrics(),
                exerciseLinks = db.exerciseDao().allVideoLinks(),
                favorites = db.exerciseDao().allFavorites(),
                userImages = db.exerciseDao().allUserImages(),
                imagePrefs = db.exerciseDao().allImagePrefs(),
            )
        )
    }

    /** Returns an error message or null on success. */
    suspend fun restore(context: Context, db: AppDatabase, text: String): String? {
        val file = try {
            json.decodeFromString<File>(text)
        } catch (e: Exception) {
            return e.message ?: "parse error"
        }
        if (file.schema != 1) return "unsupported backup schema ${file.schema}"
        db.exerciseDao().insertExercises(file.customExercises)
        db.exerciseDao().insertTranslations(file.customTranslations)
        db.planDao().restorePlans(file.plans)
        db.planDao().restoreWorkouts(file.workouts)
        db.planDao().restorePlanWorkouts(file.planWorkouts)
        db.planDao().restoreWorkoutExercises(file.workoutExercises)
        db.planDao().restoreSetTemplates(file.setTemplates)
        db.sessionDao().restoreSessions(file.sessions)
        db.sessionDao().restoreSetLogs(file.setLogs)
        db.sessionDao().restoreBodyMetrics(file.bodyMetrics)
        db.exerciseDao().restoreVideoLinks(file.exerciseLinks)
        db.exerciseDao().restoreFavorites(file.favorites)
        db.exerciseDao().restoreUserImages(file.userImages)
        db.exerciseDao().restoreImagePrefs(file.imagePrefs)
        file.heightCm?.let { Settings.setHeightCm(context, it) }
        return null
    }
}
