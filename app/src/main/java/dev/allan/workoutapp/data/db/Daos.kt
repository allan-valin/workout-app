package dev.allan.workoutapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Search result row: one exercise with its display name resolved for a language. */
data class ExerciseHit(
    val id: String,
    val name: String,
    val lang: String,
    val category: String?,
    val primaryMuscles: List<Int>,
    val secondaryMuscles: List<Int>,
    val isCustom: Boolean,
    val imageUrl: String?,
)

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercises(items: List<Exercise>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranslations(items: List<ExerciseTranslation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMuscles(items: List<Muscle>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEquipment(items: List<Equipment>)

    @Query("SELECT COUNT(*) FROM exercise WHERE isCustom = 0")
    suspend fun snapshotExerciseCount(): Int

    @Query("SELECT * FROM muscle ORDER BY nameEn")
    fun muscles(): Flow<List<Muscle>>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun exercise(id: String): Exercise?

    @Query("SELECT * FROM exercise WHERE isCustom = 1")
    suspend fun customExercises(): List<Exercise>

    @Query("SELECT * FROM exercise WHERE isCustom = 0")
    suspend fun snapshotExercises(): List<Exercise>

    @Query("DELETE FROM exercise_translation WHERE exerciseId LIKE 'wger:%'")
    suspend fun deleteWgerTranslations()

    @Query("UPDATE exercise SET imagePath = :path WHERE id = :id")
    suspend fun setImagePath(id: String, path: String)

    /** Exact case-insensitive name match in any language. */
    @Query("SELECT exerciseId FROM exercise_translation WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun exerciseIdByName(name: String): String?

    /** Candidate rows whose alias blob contains the name (verified exactly in Kotlin). */
    @Query("SELECT * FROM exercise_translation WHERE LOWER(aliases) LIKE '%' || LOWER(:name) || '%' LIMIT 20")
    suspend fun translationsWithAliasLike(name: String): List<ExerciseTranslation>

    @Query("SELECT * FROM exercise_translation WHERE exerciseId = :exerciseId")
    suspend fun translations(exerciseId: String): List<ExerciseTranslation>

    /**
     * Deferred search. lang = null searches all languages; muscleId filters primary OR
     * secondary; query matches name or aliases, case-insensitive.
     * Picks one row per exercise, preferring the requested language.
     */
    @Query(
        """
        SELECT e.id AS id,
               t.name AS name,
               t.lang AS lang,
               e.category AS category,
               e.primaryMuscles AS primaryMuscles,
               e.secondaryMuscles AS secondaryMuscles,
               e.isCustom AS isCustom,
               e.imageUrl AS imageUrl
        FROM exercise e
        JOIN exercise_translation t ON t.exerciseId = e.id
        WHERE (:lang IS NULL OR t.lang = :lang)
          AND (:query = '' OR t.name LIKE '%' || :query || '%' OR t.aliases LIKE '%' || :query || '%')
          AND (:muscleCsv IS NULL
               OR (',' || e.primaryMuscles || ',') LIKE :muscleCsv
               OR (',' || e.secondaryMuscles || ',') LIKE :muscleCsv)
        GROUP BY e.id
        ORDER BY t.name
        LIMIT 400
        """
    )
    suspend fun search(query: String, lang: String?, muscleCsv: String?): List<ExerciseHit>
}

@Dao
interface PlanDao {
    @Insert
    suspend fun insertPlan(plan: Plan): Long

    // Bulk inserts with explicit ids — used only by backup restore (fresh install).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlans(items: List<Plan>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreWorkouts(items: List<Workout>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreWorkoutExercises(items: List<WorkoutExercise>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSetTemplates(items: List<SetTemplate>)

    @Update
    suspend fun updatePlan(plan: Plan)

    @Query("SELECT * FROM plan WHERE isActive = :active ORDER BY createdAt DESC")
    fun plans(active: Boolean): Flow<List<Plan>>

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun plan(id: Long): Plan?

    @Query("SELECT * FROM plan ORDER BY createdAt")
    suspend fun allPlans(): List<Plan>

    @Query("SELECT * FROM workout WHERE planId = :planId ORDER BY orderIndex")
    suspend fun workoutsList(planId: Long): List<Workout>

    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY orderIndex")
    suspend fun workoutExercisesList(workoutId: Long): List<WorkoutExercise>

    @Query("SELECT * FROM set_template WHERE workoutExerciseId = :weId ORDER BY setIndex")
    suspend fun setTemplatesList(weId: Long): List<SetTemplate>

    @Query("DELETE FROM plan WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Update
    suspend fun updateWorkout(workout: Workout)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("SELECT * FROM workout WHERE planId = :planId ORDER BY orderIndex")
    fun workouts(planId: Long): Flow<List<Workout>>

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: Long): Workout?

    @Query(
        """
        SELECT w.* FROM workout w
        JOIN plan p ON p.id = w.planId
        WHERE p.isActive = 1 AND (',' || w.daysOfWeek || ',') LIKE '%,' || :isoDay || ',%'
        ORDER BY w.orderIndex
        """
    )
    fun workoutsForDay(isoDay: Int): Flow<List<Workout>>

    @Query("SELECT COALESCE(MAX(orderIndex) + 1, 0) FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun nextExerciseOrder(workoutId: Long): Int

    @Insert
    suspend fun insertWorkoutExercise(item: WorkoutExercise): Long

    @Update
    suspend fun updateWorkoutExercise(item: WorkoutExercise)

    @Query("DELETE FROM workout_exercise WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: Long)

    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY orderIndex")
    fun workoutExercises(workoutId: Long): Flow<List<WorkoutExercise>>

    @Insert
    suspend fun insertSetTemplate(item: SetTemplate): Long

    @Update
    suspend fun updateSetTemplate(item: SetTemplate)

    @Query("DELETE FROM set_template WHERE id = :id")
    suspend fun deleteSetTemplate(id: Long)

    @Query(
        """
        SELECT st.* FROM set_template st
        JOIN workout_exercise we ON we.id = st.workoutExerciseId
        WHERE we.workoutId = :workoutId
        ORDER BY we.orderIndex, st.setIndex
        """
    )
    fun setTemplatesForWorkout(workoutId: Long): Flow<List<SetTemplate>>
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: Session): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSessions(items: List<Session>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreSetLogs(items: List<SetLog>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreBodyMetrics(items: List<BodyMetric>)

    @Update
    suspend fun updateSession(session: Session)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun session(id: Long): Session?

    @Query("SELECT * FROM session WHERE status = 'RUNNING' LIMIT 1")
    suspend fun runningSession(): Session?

    @Query("SELECT * FROM session WHERE status = 'RUNNING' LIMIT 1")
    fun runningSessionFlow(): Flow<Session?>

    @Insert
    suspend fun insertSetLog(log: SetLog): Long

    @Query("SELECT * FROM set_log WHERE sessionId = :sessionId ORDER BY completedAt")
    suspend fun setLogs(sessionId: Long): List<SetLog>

    @Query(
        """
        SELECT sl.* FROM set_log sl
        JOIN session s ON s.id = sl.sessionId
        WHERE sl.workoutExerciseId = :workoutExerciseId AND s.status IN ('FINISHED','AUTO_ENDED')
        ORDER BY sl.completedAt DESC
        """
    )
    suspend fun previousLogs(workoutExerciseId: Long): List<SetLog>

    @Query("SELECT * FROM session WHERE status IN ('FINISHED','AUTO_ENDED') ORDER BY startedAt")
    suspend fun finishedSessions(): List<Session>

    @Query("SELECT * FROM session WHERE status IN ('FINISHED','AUTO_ENDED') ORDER BY startedAt")
    fun finishedSessionsFlow(): Flow<List<Session>>

    @Query(
        """
        SELECT sl.* FROM set_log sl
        JOIN session s ON s.id = sl.sessionId
        WHERE s.status IN ('FINISHED','AUTO_ENDED')
        ORDER BY sl.completedAt
        """
    )
    suspend fun allFinishedLogs(): List<SetLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBodyMetric(metric: BodyMetric)

    @Query("SELECT * FROM body_metric ORDER BY epochDay")
    fun bodyMetrics(): Flow<List<BodyMetric>>

    @Query("SELECT * FROM session ORDER BY startedAt")
    suspend fun allSessions(): List<Session>

    @Query("SELECT * FROM set_log ORDER BY completedAt")
    suspend fun allSetLogs(): List<SetLog>

    @Query("SELECT * FROM body_metric ORDER BY epochDay")
    suspend fun allBodyMetrics(): List<BodyMetric>

    @Insert
    suspend fun insertNote(note: ExerciseNote): Long

    @Query("SELECT * FROM exercise_note WHERE exerciseId = :exerciseId ORDER BY updatedAt DESC")
    suspend fun notes(exerciseId: String): List<ExerciseNote>
}
