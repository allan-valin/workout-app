package dev.allan.workoutapp.data.transfer

import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.ExerciseTranslation
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
 *
 * OWNS: the schema-v1 DTOs, parsing, the exercise resolver, workout naming on import, the
 * app-language name rule, and the round-trip export.
 * MUST NEVER:
 *  - activate an imported plan (25/07: two active plans hid one from both views);
 *  - create a custom exercise before every other match has been tried — resolver order is
 *    wger id → wger name → fed (DB and asset index) → the user's customs → alias →
 *    custom_fallback (24/07);
 *  - overwrite a HUMAN translation of an exercise name (30/09 N1: only a missing or machine
 *    name for the app language is replaced, and the machine name stays as an alias);
 *  - import two workouts under one name (25/07: names are global in the Archive).
 * Shaped by: 24/07 (resolver), 25/07 (active flag, name collisions), 30/09 N1 (local names),
 * Phase 34 (sets[].tempo in the transfer JSON).
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
        /**
         * Reference photos carried inline (base64 JPEG/PNG). Stored as user images of the
         * matched exercise on import; the first one becomes the representative image.
         * Absent in files written before 0.8.2.
         */
        val images: List<ImageDto> = emptyList(),
    )

    /** One inline image: [name] is informational (the PDF crop's file name), [base64] the bytes. */
    @Serializable
    data class ImageDto(
        val name: String = "",
        val base64: String = "",
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
        /** Optional cadence, e.g. "3-1-1-0" (eccentric–pause–concentric–pause seconds). Blank = none. */
        val tempo: String = "",
    )

    data class ImportReport(
        val planId: Long?,
        val workouts: Int,
        val exercises: Int,
        val createdCustom: List<String>,
        val skipped: List<String>,
        val error: String? = null,
        /** Workouts auto-renamed on import because the name was already in use (old to new). */
        val renamed: List<Pair<String, String>> = emptyList(),
        /** Inline images stored as user images (duplicates of already-linked files not counted). */
        val images: Int = 0,
    )

    /**
     * Rename suffix seed: first letter of each word plus any standalone numbers
     * ("Seca com o Thales 4 - FORTALECIMENTO" -> "ScoT4F"). Keeps the suffix short
     * enough for workout-card titles (Allan, 25/07).
     */
    fun abbreviate(name: String): String =
        name.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { word -> if (word.all { it.isDigit() }) word else word.first().toString() }

    /**
     * Name for an imported workout. Workout names are global (the Archive lists every workout
     * by name), so a name already in use gets an "(<plan abbrev> dd/MM)" tag — and a counter
     * if even that is taken. [taken] is compared case-insensitively.
     */
    fun uniqueWorkoutName(base: String, taken: Set<String>, planAbbrev: String, date: String): String {
        val lower = taken.map { it.lowercase() }.toHashSet()
        if (base.lowercase() !in lower) return base
        val tag = if (planAbbrev.isBlank()) date else "$planAbbrev $date"
        var candidate = "$base ($tag)"
        var n = 2
        while (candidate.lowercase() in lower) {
            candidate = "$base ($tag $n)"
            n++
        }
        return candidate
    }

    /**
     * Row for a newly imported plan. isActive is ALWAYS false: honoring the file's `active`
     * flag used to leave two plans active at once, which hid the superseded plan from both
     * the Active view and the Archive (Allan, 25/07). Activation is a separate, explicit step.
     */
    fun newPlanRow(plan: PlanDto, renameTo: String?, now: Long): Plan = Plan(
        name = renameTo ?: plan.name,
        isActive = false,
        cycleWeeks = plan.cycleWeeks?.coerceIn(1, 52),
        startedAt = now,
        createdAt = now,
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

    /** Parse only — nothing is written. Routing (plan vs workout file) and collision checks
     *  happen in the UI before any of the import* calls below. */
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
        context: android.content.Context? = null,
    ): ImportReport {
        val createdCustom = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()

        val planId = mergeIntoPlanId
            ?: db.planDao().insertPlan(newPlanRow(plan, renameTo, System.currentTimeMillis()))
        val abbrev = abbreviate(
            mergeIntoPlanId?.let { db.planDao().plan(it)?.name } ?: renameTo ?: plan.name
        )
        val baseOrder = if (mergeIntoPlanId != null) db.planDao().workoutsList(planId).size else 0
        var exerciseCount = 0
        val images = IntArray(1)
        plan.workouts.forEachIndexed { wIndex, w ->
            exerciseCount += importWorkoutInto(
                db, planId, w, baseOrder + wIndex, lang, createdCustom, skipped,
                fallbackName = "Workout ${wIndex + 1}", context = context,
                planAbbrev = abbrev, renamed = renamed, images = images,
            )
        }
        return ImportReport(
            planId, plan.workouts.size, exerciseCount, createdCustom, skipped,
            renamed = renamed, images = images[0],
        )
    }

    /** Imports a single-workout file into [targetPlanId]. */
    suspend fun importWorkout(
        db: AppDatabase,
        workout: WorkoutDto,
        targetPlanId: Long,
        lang: String,
        context: android.content.Context? = null,
    ): ImportReport {
        val createdCustom = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val order = db.planDao().workoutsList(targetPlanId).size
        val images = IntArray(1)
        val count = importWorkoutInto(
            db, targetPlanId, workout, order, lang, createdCustom, skipped,
            fallbackName = "Workout", context = context,
            planAbbrev = abbreviate(db.planDao().plan(targetPlanId)?.name ?: ""), renamed = renamed,
            images = images,
        )
        return ImportReport(targetPlanId, 1, count, createdCustom, skipped, renamed = renamed, images = images[0])
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
        context: android.content.Context? = null,
        planAbbrev: String = "",
        renamed: MutableList<Pair<String, String>> = mutableListOf(),
        /** Out-parameter: number of inline images stored (slot 0). */
        images: IntArray = IntArray(1),
    ): Int {
        var exerciseCount = 0
        // Workout names are global (the Archive lists every workout by name), so an
        // import must not silently create same-name twins: rename the incoming one
        // with an abbreviated plan tag + date, and report it (Allan, 25/07).
        val base = w.name.ifBlank { fallbackName }
        val date = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
            .format(java.util.Date())
        val name = uniqueWorkoutName(base, db.planDao().workoutNames().toSet(), planAbbrev, date)
        if (name != base) renamed += base to name
        val workoutId = db.planDao().insertWorkout(
            Workout(
                name = name,
                daysOfWeek = w.daysOfWeek.mapNotNull { dayMap[it.uppercase()] }.distinct().sorted(),
            )
        )
        db.planDao().insertPlanWorkout(
            dev.allan.workoutapp.data.db.PlanWorkout(
                planId = planId, workoutId = workoutId, orderIndex = orderIndex,
            )
        )
        w.exercises.forEachIndexed { eIndex, e ->
            val exerciseId = resolveExercise(db, e, lang, createdCustom, context)
            if (exerciseId == null) {
                skipped += e.match.names.firstOrNull() ?: "exercise ${eIndex + 1}"
                return@forEachIndexed
            }
            installLocalName(db, exerciseId, e.match.names, lang)
            if (context != null) images[0] += installImages(context, db, exerciseId, e.images)
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
            // Every value from the file is clamped: a generator slip must not put a negative
            // weight or a 10-hour rest into the plan.
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
                        tempo = s.tempo.trim(),
                    )
                )
            }
            exerciseCount++
        }
        return exerciseCount
    }

    /** Base64 → bytes, or null when the text is not base64 or empty. */
    fun decodeImage(base64: String): ByteArray? =
        runCatching { java.util.Base64.getMimeDecoder().decode(base64.trim()) }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Content-addressed file name for an inline image, so importing the same plan twice
     * (or two plans sharing a photo) maps to one file instead of a growing pile.
     */
    fun imageFileName(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        return "import_" + digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /**
     * Stores the exercise's inline images under files/exercise_media (same place as the
     * gallery picker) and links them as user images. A file already linked to this exercise
     * is skipped, so a re-import is idempotent. The first image becomes the representative
     * one — the plan's photo is the reference Allan wants in-session (2026-10-06).
     * Returns how many images were newly linked.
     */
    private suspend fun installImages(
        context: android.content.Context,
        db: AppDatabase,
        exerciseId: String,
        images: List<ImageDto>,
    ): Int {
        if (images.isEmpty()) return 0
        val dir = java.io.File(context.filesDir, "exercise_media").apply { mkdirs() }
        val linked = db.exerciseDao().userImagePaths(exerciseId).toMutableSet()
        var added = 0
        var first: String? = null
        for (img in images) {
            val bytes = decodeImage(img.base64) ?: continue
            val file = java.io.File(dir, imageFileName(bytes))
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                runCatching { file.writeBytes(bytes) }.getOrElse { continue }
            }
            if (first == null) first = file.path
            if (file.path in linked) continue
            db.exerciseDao().insertUserImage(
                dev.allan.workoutapp.data.db.ExerciseUserImage(exerciseId = exerciseId, path = file.path)
            )
            linked += file.path
            added++
        }
        first?.let { db.exerciseDao().upsertImagePref(dev.allan.workoutapp.data.db.ExerciseImagePref(exerciseId, it)) }
        return added
    }

    /**
     * The name the plan file gives in the app's language, when the database has none or only
     * a machine translation for that language — or null when nothing should change.
     *
     * Allan, 30/09: the generator writes the Portuguese name into `match.names`, the import
     * matched the wger row by id / English name, and the app then showed the on-device
     * machine translation of the English name ("literally translating names instead of
     * correct names"). Rule: a human translation for [lang] is never touched; otherwise the
     * first name in [names] that is not already a name or alias of a human translation (and
     * not the machine name itself) is taken as the [lang] name. The generator doc asks for the
     * names in the app's language right after the English one, so "first unknown" is it.
     */
    fun localNameFor(names: List<String>, existing: List<ExerciseTranslation>, lang: String): String? {
        val own = existing.filter { it.lang == lang }
        if (own.any { !it.machine }) return null
        val known = existing.flatMap { tr -> listOf(tr.name) + tr.aliases }
            .map { it.trim().lowercase() }.toSet()
        return names.map { it.trim() }.firstOrNull { it.isNotEmpty() && it.lowercase() !in known }
    }

    /** Writes the [lang] translation chosen by [localNameFor]; keeps the description it had. */
    private suspend fun installLocalName(db: AppDatabase, exerciseId: String, names: List<String>, lang: String) {
        val existing = db.exerciseDao().translations(exerciseId)
        val name = localNameFor(names, existing, lang) ?: return
        val machineRow = existing.firstOrNull { it.lang == lang }
        db.exerciseDao().insertTranslations(
            listOf(
                // Same rowId → REPLACE overwrites in place; the machine name stays findable
                // as an alias for anyone searching the old wording.
                machineRow?.copy(
                    name = name,
                    aliases = (machineRow.aliases + machineRow.name).distinct(),
                    machine = false,
                ) ?: ExerciseTranslation(
                    exerciseId = exerciseId,
                    lang = lang,
                    name = name,
                    description = "",
                    aliases = emptyList(),
                    machine = false,
                )
            )
        )
    }

    /**
     * Match pipeline (Allan, 24/07): wger id -> exact name in wger -> free-exercise-db
     * (DB rows AND the not-yet-imported asset index) -> EXISTING custom exercises ->
     * exact alias -> custom_fallback -> null. Custom creation is the last resort so a
     * plan reimport reuses the user's own exercises instead of duplicating them.
     */
    private suspend fun resolveExercise(
        db: AppDatabase,
        e: ExerciseDto,
        lang: String,
        createdCustom: MutableList<String>,
        context: android.content.Context? = null,
    ): String? {
        e.match.wgerId?.let { id ->
            if (db.exerciseDao().exercise("wger:$id") != null) return "wger:$id"
        }
        val byName = e.match.names.flatMap { db.exerciseDao().exerciseIdsByName(it.trim()) }
        byName.firstOrNull { it.startsWith("wger:") }?.let { return it }
        byName.firstOrNull { it.startsWith("fed:") }?.let { return it }
        // Fed asset index: entries live outside the DB until first use, so a plain DB
        // lookup missed the whole second database during import.
        if (context != null) {
            e.match.names.forEach { name ->
                val target = name.trim().lowercase()
                dev.allan.workoutapp.data.FedIndex.search(context, name.trim(), null)
                    .firstOrNull { it.name.trim().lowercase() == target }
                    ?.let { hit ->
                        dev.allan.workoutapp.data.FedIndex.ensureImported(context, db, hit.id)
                        return hit.id
                    }
            }
        }
        byName.firstOrNull()?.let { return it } // remaining = the user's custom exercises
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

    /** Exports a plan back to schema-v1 JSON (round-trips with import). The exported
     *  `active` flag is informational: import ignores it (see newPlanRow). */
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
                        tempo = s.tempo,
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
