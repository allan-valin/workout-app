package dev.allan.workoutapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Row for [PlanDao.exerciseCounts]. */
data class WorkoutExerciseCount(val workoutId: Long, val count: Int)

data class PlanWorkoutCount(val planId: Long, val count: Int)

/** Row for [PlanDao.lastTrainedFlow]. */
data class WorkoutLastTrained(val workoutId: Long, val lastAt: Long)

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
    val imagePath: String?,
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

    // Backup scope: user-created customs AND imported free-exercise-db rows — everything
    // a wger-snapshot fresh install wouldn't already have.
    @Query("SELECT * FROM exercise WHERE isCustom = 1 OR id LIKE 'fed:%'")
    suspend fun customExercises(): List<Exercise>

    @Query("SELECT * FROM exercise WHERE isCustom = 0 AND id NOT LIKE 'fed:%'")
    suspend fun snapshotExercises(): List<Exercise>

    @Query("DELETE FROM exercise_translation WHERE exerciseId LIKE 'wger:%'")
    suspend fun deleteWgerTranslations()

    @Query("SELECT DISTINCT exerciseId FROM exercise_translation WHERE lang = :lang")
    suspend fun exerciseIdsWithLang(lang: String): List<String>

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

    @Query("SELECT url FROM exercise_link WHERE exerciseId = :exerciseId")
    suspend fun videoLink(exerciseId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideoLink(link: ExerciseLink)

    @Query("DELETE FROM exercise_link WHERE exerciseId = :exerciseId")
    suspend fun deleteVideoLink(exerciseId: String)

    @Query("SELECT * FROM exercise_link")
    suspend fun allVideoLinks(): List<ExerciseLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreVideoLinks(items: List<ExerciseLink>)

    // ---- favorites (own table; survives wger refresh) ----
    @Query("SELECT exerciseId FROM exercise_favorite")
    fun favoriteIdsFlow(): Flow<List<String>>

    @Query("SELECT exerciseId FROM exercise_favorite")
    suspend fun favoriteIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(fav: ExerciseFavorite)

    @Query("DELETE FROM exercise_favorite WHERE exerciseId = :exerciseId")
    suspend fun removeFavorite(exerciseId: String)

    @Query("SELECT * FROM exercise_favorite")
    suspend fun allFavorites(): List<ExerciseFavorite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreFavorites(items: List<ExerciseFavorite>)

    // ---- user-linked exercise images (v7) ----
    @Query("SELECT * FROM exercise_user_image WHERE exerciseId = :exerciseId ORDER BY id")
    fun userImagesFlow(exerciseId: String): Flow<List<ExerciseUserImage>>

    @Insert
    suspend fun insertUserImage(img: ExerciseUserImage): Long

    @Query("DELETE FROM exercise_user_image WHERE id = :id")
    suspend fun deleteUserImage(id: Long)

    @Query("SELECT path FROM exercise_image_pref WHERE exerciseId = :exerciseId")
    fun imagePrefFlow(exerciseId: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImagePref(pref: ExerciseImagePref)

    // Full-backup support.
    @Query("SELECT * FROM exercise_user_image")
    suspend fun allUserImages(): List<ExerciseUserImage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreUserImages(items: List<ExerciseUserImage>)

    @Query("SELECT * FROM exercise_image_pref")
    suspend fun allImagePrefs(): List<ExerciseImagePref>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreImagePrefs(items: List<ExerciseImagePref>)

    /**
     * Deferred search. lang = null searches all languages; muscleId filters primary OR
     * secondary; query matches name or aliases, case-insensitive.
     * Picks one row per exercise, preferring the requested language.
     */
    /** Cardio pool for workout suggestions, one row per exercise (any language). */
    @Query(
        """
        SELECT e.id AS id, t.name AS name, t.lang AS lang, e.category AS category,
               e.primaryMuscles AS primaryMuscles, e.secondaryMuscles AS secondaryMuscles,
               e.isCustom AS isCustom, e.imageUrl AS imageUrl, e.imagePath AS imagePath
        FROM exercise e
        JOIN exercise_translation t ON t.exerciseId = e.id
        WHERE e.isCardio = 1
        GROUP BY e.id
        ORDER BY t.name
        """
    )
    suspend fun cardioHits(): List<ExerciseHit>

    @Query(
        """
        SELECT e.id AS id,
               t.name AS name,
               t.lang AS lang,
               e.category AS category,
               e.primaryMuscles AS primaryMuscles,
               e.secondaryMuscles AS secondaryMuscles,
               e.isCustom AS isCustom,
               e.imageUrl AS imageUrl,
               e.imagePath AS imagePath
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

    /** All custom exercises as display rows (one per exercise, any language). */
    @Query(
        """
        SELECT e.id AS id, t.name AS name, t.lang AS lang, e.category AS category,
               e.primaryMuscles AS primaryMuscles, e.secondaryMuscles AS secondaryMuscles,
               e.isCustom AS isCustom, e.imageUrl AS imageUrl, e.imagePath AS imagePath
        FROM exercise e
        JOIN exercise_translation t ON t.exerciseId = e.id
        WHERE e.isCustom = 1
        GROUP BY e.id
        ORDER BY t.name
        """
    )
    fun customHits(): Flow<List<ExerciseHit>>

    /** Workouts referencing the exercise — a custom one may only be deleted when 0. */
    @Query("SELECT COUNT(*) FROM workout_exercise WHERE exerciseId = :id")
    suspend fun exerciseUsageCount(id: String): Int

    @Query("DELETE FROM exercise WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomExercise(id: String)

    @Query("DELETE FROM exercise_translation WHERE exerciseId = :id")
    suspend fun deleteTranslationsFor(id: String)
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

    /** The single active plan (at most one — activating a plan deactivates the rest). */
    @Query("SELECT * FROM plan WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    fun activePlanFlow(): Flow<Plan?>

    /** Suspend variant — avoids the StateFlow null-race when adding workouts to the active plan. */
    @Query("SELECT * FROM plan WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun activePlanNow(): Plan?

    /** Enforces the one-active-plan rule before activating a specific plan. */
    @Query("UPDATE plan SET isActive = 0")
    suspend fun deactivateAllPlans()

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun plan(id: Long): Plan?

    @Query("SELECT * FROM plan ORDER BY createdAt")
    suspend fun allPlans(): List<Plan>

    @Query(
        """
        SELECT w.* FROM workout w
        JOIN plan_workout pw ON pw.workoutId = w.id
        WHERE pw.planId = :planId
        ORDER BY pw.orderIndex
        """
    )
    suspend fun workoutsList(planId: Long): List<Workout>

    // ---- plan_workout membership (many-to-many) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlanWorkout(link: PlanWorkout)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restorePlanWorkouts(items: List<PlanWorkout>)

    @Query("DELETE FROM plan_workout WHERE planId = :planId AND workoutId = :workoutId")
    suspend fun deletePlanWorkout(planId: Long, workoutId: Long)

    @Query("DELETE FROM plan_workout WHERE planId = :planId")
    suspend fun deletePlanWorkoutsForPlan(planId: Long)

    @Query("DELETE FROM plan_workout WHERE workoutId = :workoutId")
    suspend fun deletePlanWorkoutsForWorkout(workoutId: Long)

    @Query("SELECT * FROM plan_workout")
    suspend fun allPlanWorkouts(): List<PlanWorkout>

    /** Live membership rows — the Archive screens expand plans↔workouts from this. */
    @Query("SELECT * FROM plan_workout ORDER BY planId, orderIndex")
    fun allPlanWorkoutsFlow(): Flow<List<PlanWorkout>>

    @Query("SELECT COALESCE(MAX(orderIndex) + 1, 0) FROM plan_workout WHERE planId = :planId")
    suspend fun nextWorkoutOrder(planId: Long): Int

    /** Workout ids that belong to the currently active plan — for labelling in Archive. */
    @Query(
        """
        SELECT pw.workoutId FROM plan_workout pw
        JOIN plan p ON p.id = pw.planId
        WHERE p.isActive = 1
        """
    )
    fun activePlanWorkoutIds(): Flow<List<Long>>

    /** Every workout, for Archive → Workouts (includes archived + unassigned ones). */
    @Query("SELECT * FROM workout ORDER BY name COLLATE NOCASE")
    fun allWorkoutsFlow(): Flow<List<Workout>>

    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY orderIndex")
    suspend fun workoutExercisesList(workoutId: Long): List<WorkoutExercise>

    @Query("SELECT * FROM set_template WHERE workoutExerciseId = :weId ORDER BY setIndex")
    suspend fun setTemplatesList(weId: Long): List<SetTemplate>

    @Query("SELECT * FROM workout_exercise WHERE id = :id")
    suspend fun workoutExercise(id: Long): WorkoutExercise?

    /** All uses of an exercise across workouts — for "load the incoming exercise's last config". */
    @Query("SELECT * FROM workout_exercise WHERE exerciseId = :exId")
    suspend fun workoutExercisesByExercise(exId: String): List<WorkoutExercise>

    @Query("DELETE FROM set_template WHERE workoutExerciseId = :weId")
    suspend fun deleteSetTemplatesForExercise(weId: Long)

    @Query("DELETE FROM plan WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT * FROM plan WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun planByName(name: String): Plan?

    // Uniqueness checks for new cycle/workout names.
    @Query("SELECT name FROM plan")
    suspend fun planNames(): List<String>

    @Query("SELECT name FROM workout")
    suspend fun workoutNames(): List<String>

    // Rename-path variants: a name only collides with OTHER rows of the same kind.
    @Query("SELECT name FROM plan WHERE id != :id")
    suspend fun planNamesExcept(id: Long): List<String>

    @Query("SELECT name FROM workout WHERE id != :id")
    suspend fun workoutNamesExcept(id: Long): List<String>

    @Query("SELECT * FROM workout ORDER BY id")
    suspend fun allWorkoutsList(): List<Workout>

    // No FK cascades in the schema — child rows must be removed explicitly.
    @Query(
        """
        DELETE FROM set_template WHERE workoutExerciseId IN
            (SELECT id FROM workout_exercise WHERE workoutId = :workoutId)
        """
    )
    suspend fun deleteSetTemplatesForWorkout(workoutId: Long)

    @Query("DELETE FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun deleteWorkoutExercisesForWorkout(workoutId: Long)

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Update
    suspend fun updateWorkout(workout: Workout)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query(
        """
        SELECT w.* FROM workout w
        JOIN plan_workout pw ON pw.workoutId = w.id
        WHERE pw.planId = :planId
        ORDER BY pw.orderIndex
        """
    )
    fun workouts(planId: Long): Flow<List<Workout>>

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: Long): Workout?

    // Reactive variant: keeps view titles fresh after renames elsewhere.
    @Query("SELECT * FROM workout WHERE id = :id")
    fun workoutFlow(id: Long): Flow<Workout?>

    @Query(
        """
        SELECT w.* FROM workout w
        JOIN plan_workout pw ON pw.workoutId = w.id
        JOIN plan p ON p.id = pw.planId
        WHERE p.isActive = 1 AND w.archived = 0
          AND (',' || w.daysOfWeek || ',') LIKE '%,' || :isoDay || ',%'
        ORDER BY pw.orderIndex
        """
    )
    fun workoutsForDay(isoDay: Int): Flow<List<Workout>>

    /** Exercise count per workout — drives the count badge on workout cards. */
    @Query("SELECT workoutId, COUNT(*) AS count FROM workout_exercise GROUP BY workoutId")
    fun exerciseCounts(): Flow<List<WorkoutExerciseCount>>

    /** Workout count per plan — the plan/workout distinction on plan cards. */
    @Query("SELECT planId, COUNT(*) AS count FROM plan_workout GROUP BY planId")
    fun workoutCounts(): Flow<List<PlanWorkoutCount>>

    /** Most recent finished-session start per workout — the "last trained" line on rows. */
    @Query(
        """
        SELECT workoutId, MAX(startedAt) AS lastAt FROM session
        WHERE status IN ('FINISHED','AUTO_ENDED') GROUP BY workoutId
        """
    )
    fun lastTrainedFlow(): Flow<List<WorkoutLastTrained>>

    @Query("SELECT COALESCE(MAX(orderIndex) + 1, 0) FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun nextExerciseOrder(workoutId: Long): Int

    @Query("SELECT DISTINCT exerciseId FROM workout_exercise")
    suspend fun referencedExerciseIds(): List<String>

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

    @Query(
        """
        SELECT * FROM set_log
        WHERE sessionId = :sessionId AND workoutExerciseId = :workoutExerciseId AND setIndex = :setIndex
        LIMIT 1
        """
    )
    suspend fun setLog(sessionId: Long, workoutExerciseId: Long, setIndex: Int): SetLog?

    @Query(
        """
        DELETE FROM set_log
        WHERE sessionId = :sessionId AND workoutExerciseId = :workoutExerciseId AND setIndex = :setIndex
        """
    )
    suspend fun deleteSetLog(sessionId: Long, workoutExerciseId: Long, setIndex: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: SessionSetDraft)

    @Query("SELECT * FROM session_set_draft WHERE sessionId = :sessionId")
    suspend fun drafts(sessionId: Long): List<SessionSetDraft>

    @Query("DELETE FROM session_set_draft WHERE sessionId = :sessionId")
    suspend fun deleteDrafts(sessionId: Long)

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

    /** Latest note text for an exercise (one persistent note per exercise). */
    @Query("SELECT text FROM exercise_note WHERE exerciseId = :exerciseId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun noteText(exerciseId: String): String?

    @Query("DELETE FROM exercise_note WHERE exerciseId = :exerciseId")
    suspend fun deleteNotesFor(exerciseId: String)
}
