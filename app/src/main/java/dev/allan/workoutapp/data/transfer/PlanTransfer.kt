package dev.allan.workoutapp.data.transfer

import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.data.db.Workout
import dev.allan.workoutapp.data.db.WorkoutExercise
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Plan JSON import/export. The schema contract lives in docs/WORKOUT_PLAN_GENERATOR.md —
 * browser Claude generates these files; never change field names without bumping
 * schema_version and keeping this importer backward-compatible.
 */
object PlanTransfer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ---- Schema v1 DTOs ----

    /**
     * A file carries either a whole plan or a single workout (additive v1 extension;
     * plan files from the LLM contract keep working unchanged).
     */
    @Serializable
    data class File(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        val plan: PlanDto? = null,
        val workout: WorkoutDto? = null,
    )

    @Serializable
    data class PlanDto(
        val name: String,
        val active: Boolean = true,
        @SerialName("cycle_weeks") val cycleWeeks: Int? = null,
        val workouts: List<WorkoutDto> = emptyList(),
    )

    @Serializable
    data class WorkoutDto(
        val name: String,
        @SerialName("days_of_week") val daysOfWeek: List<String> = emptyList(),
        val exercises: List<ExerciseDto> = emptyList(),
    )

    @Serializable
    data class ExerciseDto(
        val match: MatchDto,
        @SerialName("custom_fallback") val customFallback: CustomFallbackDto? = null,
        @SerialName("weight_mode") val weightMode: String = "TOTAL",
        @SerialName("bar_weight_kg") val barWeightKg: Double = 20.0,
        /** Alternate with the previous exercise (A1, B1, rest, A2, B2, …). */
        @SerialName("superset_with_previous") val supersetWithPrevious: Boolean = false,
        val note: String = "",
        val sets: List<SetDto> = emptyList(),
    )

    @Serializable
    data class MatchDto(
        @SerialName("wger_id") val wgerId: Long? = null,
        val names: List<String> = emptyList(),
    )

    @Serializable
    data class CustomFallbackDto(
        @SerialName("primary_muscle") val primaryMuscle: String? = null,
        @SerialName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
        @SerialName("is_cardio") val isCardio: Boolean = false,
        val description: String = "",
    )

    @Serializable
    data class SetDto(
        val type: String = "NORMAL",
        @SerialName("weight_kg") val weightKg: Double = 0.0,
        val value: Int = 10,
        /** Optional top of the target rep range (REPS only); value is the bottom. */
        @SerialName("value_max") val valueMax: Int? = null,
        val unit: String = "REPS",
        @SerialName("rest_secs") val restSecs: Int = 90,
    )

    data class ImportReport(
        val planId: Long?,
        val workouts: Int,
        val exercises: Int,
        val createdCustom: List<String>,
        val skipped: List<String>,
        val error: String? = null,
    )

    private val dayMap = mapOf(
        "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7,
    )

    /** Muscle-enum slug (generator doc) -> wger muscle id, for custom_fallback. */
    private val muscleSlugToWgerId = mapOf(
        "biceps" to 1, "front_delts" to 2, "side_delts" to 2, "rear_delts" to 2,
        "chest" to 4, "triceps" to 5, "abs" to 6, "calves" to 7, "glutes" to 8,
        "traps" to 9, "quads" to 10, "hamstrings" to 11, "lats" to 12, "upper_back" to 12,
        "brachialis" to 13, "obliques" to 14, "soleus" to 15, "forearms" to 13,
        "lower_back" to 12, "adductors" to 10, "abductors" to 8, "neck" to 9,
    )

    /** What a parsed file contains, so the UI can route (and detect name collisions) first. */
    sealed class Parsed {
        data class PlanFile(val file: File, val plan: PlanDto) : Parsed()
        data class WorkoutFile(val file: File, val workout: WorkoutDto) : Parsed()
        data class Error(val message: String) : Parsed()
    }

    fun parse(text: String): Parsed {
        val file = try {
            json.decodeFromString<File>(text)
        } catch (e: Exception) {
            return Parsed.Error(e.message ?: "parse error")
        }
        if (file.schemaVersion != 1) return Parsed.Error("unsupported schema_version ${file.schemaVersion}")
        return when {
            file.plan != null -> {
                if (file.plan.name.isBlank()) Parsed.Error("plan.name missing")
                else Parsed.PlanFile(file, file.plan)
            }
            file.workout != null -> Parsed.WorkoutFile(file, file.workout)
            else -> Parsed.Error("file has neither plan nor workout")
        }
    }

    /** One-shot import kept for callers/tests that don't need collision handling. */
    suspend fun import(db: AppDatabase, text: String, lang: String): ImportReport =
        when (val parsed = parse(text)) {
            is Parsed.Error -> ImportReport(null, 0, 0, emptyList(), emptyList(), error = parsed.message)
            is Parsed.PlanFile -> importPlan(db, parsed.plan, lang)
            is Parsed.WorkoutFile ->
                ImportReport(null, 0, 0, emptyList(), emptyList(), error = "workout file: choose a target plan")
        }

    /**
     * Imports a plan file. [renameTo] imports under a different name (collision → rename);
     * [mergeIntoPlanId] skips plan creation and appends the workouts to an existing plan.
     */
    suspend fun importPlan(
        db: AppDatabase,
        plan: PlanDto,
        lang: String,
        renameTo: String? = null,
        mergeIntoPlanId: Long? = null,
    ): ImportReport {
        val createdCustom = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        val planId = mergeIntoPlanId ?: db.planDao().insertPlan(
            Plan(
                name = renameTo ?: plan.name,
                isActive = plan.active,
                cycleWeeks = plan.cycleWeeks?.coerceIn(1, 52),
                startedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
            )
        )
        val baseOrder = if (mergeIntoPlanId != null) db.planDao().workoutsList(planId).size else 0
        var exerciseCount = 0
        plan.workouts.forEachIndexed { wIndex, w ->
            exerciseCount += importWorkoutInto(
                db, planId, w, baseOrder + wIndex, lang, createdCustom, skipped,
                fallbackName = "Workout ${wIndex + 1}",
            )
        }
        return ImportReport(planId, plan.workouts.size, exerciseCount, createdCustom, skipped)
    }

    /** Imports a single-workout file into [targetPlanId]. */
    suspend fun importWorkout(
        db: AppDatabase,
        workout: WorkoutDto,
        targetPlanId: Long,
        lang: String,
    ): ImportReport {
        val createdCustom = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val order = db.planDao().workoutsList(targetPlanId).size
        val count = importWorkoutInto(
            db, targetPlanId, workout, order, lang, createdCustom, skipped, fallbackName = "Workout",
        )
        return ImportReport(targetPlanId, 1, count, createdCustom, skipped)
    }

    private suspend fun importWorkoutInto(
        db: AppDatabase,
        planId: Long,
        w: WorkoutDto,
        orderIndex: Int,
        lang: String,
        createdCustom: MutableList<String>,
        skipped: MutableList<String>,
        fallbackName: String,
    ): Int {
        var exerciseCount = 0
        val workoutId = db.planDao().insertWorkout(
            Workout(
                name = w.name.ifBlank { fallbackName },
                daysOfWeek = w.daysOfWeek.mapNotNull { dayMap[it.uppercase()] }.distinct().sorted(),
            )
        )
        db.planDao().insertPlanWorkout(
            dev.allan.workoutapp.data.db.PlanWorkout(
                planId = planId, workoutId = workoutId, orderIndex = orderIndex,
            )
        )
        w.exercises.forEachIndexed { eIndex, e ->
            val exerciseId = resolveExercise(db, e, lang, createdCustom)
            if (exerciseId == null) {
                skipped += e.match.names.firstOrNull() ?: "exercise ${eIndex + 1}"
                return@forEachIndexed
            }
            val weId = db.planDao().insertWorkoutExercise(
                WorkoutExercise(
                    workoutId = workoutId,
                    exerciseId = exerciseId,
                    orderIndex = eIndex,
                    note = e.note,
                    weightMode = runCatching { WeightMode.valueOf(e.weightMode) }.getOrDefault(WeightMode.TOTAL),
                    barWeightKg = e.barWeightKg,
                    supersetWithPrev = e.supersetWithPrevious && eIndex > 0,
                )
            )
            val sets = e.sets.ifEmpty { listOf(SetDto(), SetDto(), SetDto()) }
            sets.forEachIndexed { sIndex, s ->
                db.planDao().insertSetTemplate(
                    SetTemplate(
                        workoutExerciseId = weId,
                        setIndex = sIndex,
                        type = runCatching { SetType.valueOf(s.type) }.getOrDefault(SetType.NORMAL),
                        targetWeightKg = s.weightKg.coerceAtLeast(0.0),
                        targetValue = s.value.coerceAtLeast(0),
                        targetValueMax = s.valueMax?.takeIf { it > s.value },
                        valueUnit = runCatching { ValueUnit.valueOf(s.unit) }.getOrDefault(ValueUnit.REPS),
                        restSecs = s.restSecs.coerceIn(0, 3600),
                    )
                )
            }
            exerciseCount++
        }
        return exerciseCount
    }

    /** wger id -> exact name (any language) -> exact alias -> custom_fallback -> null. */
    private suspend fun resolveExercise(
        db: AppDatabase,
        e: ExerciseDto,
        lang: String,
        createdCustom: MutableList<String>,
    ): String? {
        e.match.wgerId?.let { id ->
            if (db.exerciseDao().exercise("wger:$id") != null) return "wger:$id"
        }
        e.match.names.forEach { name ->
            db.exerciseDao().exerciseIdByName(name.trim())?.let { return it }
        }
        e.match.names.forEach { name ->
            val target = name.trim().lowercase()
            db.exerciseDao().translationsWithAliasLike(name.trim()).forEach { tr ->
                if (tr.aliases.any { it.trim().lowercase() == target }) return tr.exerciseId
            }
        }
        val fallback = e.customFallback ?: return null
        val displayName = e.match.names.firstOrNull()?.trim() ?: return null
        val id = PlanRepo.createCustomExercise(
            db = db,
            name = displayName,
            description = fallback.description,
            primaryMuscleId = fallback.primaryMuscle?.let { muscleSlugToWgerId[it.lowercase()] },
            isCardio = fallback.isCardio,
            lang = lang,
        )
        createdCustom += displayName
        return id
    }

    /** Exports a plan back to schema-v1 JSON (round-trips with import). */
    suspend fun export(db: AppDatabase, planId: Long): String? {
        val plan = db.planDao().plan(planId) ?: return null
        val workouts = db.planDao().workoutsList(planId).map { workoutToDto(db, it) }
        return json.encodeToString(
            File(
                plan = PlanDto(
                    name = plan.name,
                    active = plan.isActive,
                    cycleWeeks = plan.cycleWeeks,
                    workouts = workouts,
                )
            )
        )
    }

    /** Exports a single workout as a shareable JSON file (auto-detected on import). */
    suspend fun exportWorkout(db: AppDatabase, workoutId: Long): String? {
        val workout = db.planDao().workout(workoutId) ?: return null
        return json.encodeToString(File(workout = workoutToDto(db, workout)))
    }

    private suspend fun workoutToDto(db: AppDatabase, w: Workout): WorkoutDto {
        val dayNames = mapOf(1 to "MON", 2 to "TUE", 3 to "WED", 4 to "THU", 5 to "FRI", 6 to "SAT", 7 to "SUN")
        val exercises = db.planDao().workoutExercisesList(w.id).map { we ->
            val translations = db.exerciseDao().translations(we.exerciseId)
            val exercise = db.exerciseDao().exercise(we.exerciseId)
            ExerciseDto(
                match = MatchDto(
                    wgerId = we.exerciseId.removePrefix("wger:").toLongOrNull()
                        .takeIf { we.exerciseId.startsWith("wger:") },
                    names = translations.map { it.name }.distinct(),
                ),
                customFallback = if (exercise?.isCustom == true) CustomFallbackDto(
                    isCardio = exercise.isCardio,
                    description = translations.firstOrNull()?.description ?: "",
                ) else null,
                weightMode = we.weightMode.name,
                barWeightKg = we.barWeightKg,
                supersetWithPrevious = we.supersetWithPrev,
                note = we.note,
                sets = db.planDao().setTemplatesList(we.id).map { s ->
                    SetDto(
                        type = s.type.name,
                        weightKg = s.targetWeightKg,
                        value = s.targetValue,
                        valueMax = s.targetValueMax,
                        unit = s.valueUnit.name,
                        restSecs = s.restSecs,
                    )
                },
            )
        }
        return WorkoutDto(
            name = w.name,
            daysOfWeek = w.daysOfWeek.mapNotNull { dayNames[it] },
            exercises = exercises,
        )
    }
}
