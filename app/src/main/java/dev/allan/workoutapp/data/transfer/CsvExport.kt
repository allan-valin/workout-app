package dev.allan.workoutapp.data.transfer

import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History CSV exports. Column contract documented in docs/WORKOUT_PLAN_GENERATOR.md
 * ("History CSV" section) so browser Claude can analyze the files — keep in sync.
 */
object CsvExport {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun ts(millis: Long): String =
        dateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun esc(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) '"' + value.replace("\"", "\"\"") + '"'
        else value

    suspend fun sets(db: AppDatabase, lang: String): String {
        val sessions = db.sessionDao().allSessions().associateBy { it.id }
        val workoutNames = mutableMapOf<Long, Pair<String, String>>() // workoutId -> (plan, workout)
        db.planDao().allPlans().forEach { plan ->
            db.planDao().workoutsList(plan.id).forEach { w -> workoutNames[w.id] = plan.name to w.name }
        }
        val sb = StringBuilder(
            "session_id,date,plan,workout,exercise_id,exercise_name,set_index,set_type,weight_kg,weight_mode,value,unit,active_secs,rest_secs\n"
        )
        db.sessionDao().allSetLogs().forEach { log ->
            val session = sessions[log.sessionId]
            val (planName, workoutName) = session?.let { workoutNames[it.workoutId] } ?: ("" to "")
            val exerciseName = PlanRepo.displayName(db, log.exerciseId, lang)
            sb.append(log.sessionId).append(',')
                .append(ts(log.completedAt)).append(',')
                .append(esc(planName)).append(',')
                .append(esc(workoutName)).append(',')
                .append(esc(log.exerciseId)).append(',')
                .append(esc(exerciseName)).append(',')
                .append(log.setIndex).append(',')
                .append(log.type.name).append(',')
                .append(log.weightKg).append(',')
                .append(log.weightMode.name).append(',')
                .append(log.value).append(',')
                .append(log.valueUnit.name).append(',')
                .append(log.activeSecs ?: "").append(',')
                .append(log.restSecs ?: "").append('\n')
        }
        return sb.toString()
    }

    /** One trained (exercise, day) with the heaviest working weight of that day. */
    data class WeightPoint(val exerciseName: String, val date: String, val weightKg: Double)

    /**
     * Weight evolution of the active cycle as a pivot table: one row per exercise, one column
     * per training day, cell = top working (non-warm-up) weight that day — readable in a
     * spreadsheet as-is (Allan, 29/08). Sessions count when they belong to one of the active
     * plan's workouts and started after the plan did.
     */
    suspend fun weightEvolution(db: AppDatabase, lang: String): String {
        val plan = db.planDao().activePlanNow() ?: return weightPivot(emptyList())
        val workoutIds = db.planDao().workoutsList(plan.id).map { it.id }.toSet()
        val since = plan.startedAt ?: 0L
        val sessions = db.sessionDao().finishedSessions()
            .filter { it.workoutId in workoutIds && it.startedAt >= since }
            .associateBy { it.id }
        val names = mutableMapOf<String, String>()
        val points = db.sessionDao().allSetLogs()
            .filter { it.sessionId in sessions && it.type != dev.allan.workoutapp.data.db.SetType.WARMUP && it.weightKg > 0.0 }
            .map { log ->
                WeightPoint(
                    exerciseName = names.getOrPut(log.exerciseId) { PlanRepo.displayName(db, log.exerciseId, lang) },
                    date = dayFmt.format(Instant.ofEpochMilli(log.completedAt).atZone(ZoneId.systemDefault())),
                    weightKg = log.weightKg,
                )
            }
        return weightPivot(points)
    }

    /** Pure pivot builder behind [weightEvolution]; exercises sorted by name, days ascending. */
    fun weightPivot(points: List<WeightPoint>): String {
        val days = points.map { it.date }.distinct().sorted()
        val byExercise = points.groupBy { it.exerciseName }.toSortedMap()
        val sb = StringBuilder("exercise")
        days.forEach { sb.append(',').append(it) }
        sb.append('\n')
        byExercise.forEach { (name, pts) ->
            val top = pts.groupBy { it.date }.mapValues { (_, p) -> p.maxOf { it.weightKg } }
            sb.append(esc(name))
            days.forEach { d -> sb.append(',').append(top[d]?.toString() ?: "") }
            sb.append('\n')
        }
        return sb.toString()
    }

    suspend fun sessions(db: AppDatabase): String {
        val workoutNames = mutableMapOf<Long, String>()
        db.planDao().allPlans().forEach { plan ->
            db.planDao().workoutsList(plan.id).forEach { w -> workoutNames[w.id] = w.name }
        }
        val logsBySession = db.sessionDao().allSetLogs().groupBy { it.sessionId }
        val sb = StringBuilder(
            "session_id,workout,started_at,ended_at,status,active_secs,rest_secs,idle_secs,total_volume_kg\n"
        )
        db.sessionDao().allSessions().forEach { s ->
            val total = (((s.endedAt ?: s.startedAt) - s.startedAt) / 1000L).toInt()
            val idle = (total - s.activeSecs - s.restSecs).coerceAtLeast(0)
            val volume = logsBySession[s.id]?.sumOf(dev.allan.workoutapp.data.StatsCalc::volumeKg) ?: 0.0
            sb.append(s.id).append(',')
                .append(esc(workoutNames[s.workoutId] ?: "")).append(',')
                .append(ts(s.startedAt)).append(',')
                .append(s.endedAt?.let(::ts) ?: "").append(',')
                .append(s.status.name).append(',')
                .append(s.activeSecs).append(',')
                .append(s.restSecs).append(',')
                .append(idle).append(',')
                .append("%.1f".format(volume)).append('\n')
        }
        return sb.toString()
    }

    suspend fun body(db: AppDatabase): String {
        val sb = StringBuilder("date,weight_kg\n")
        db.sessionDao().allBodyMetrics().forEach { m ->
            sb.append(java.time.LocalDate.ofEpochDay(m.epochDay)).append(',').append(m.weightKg).append('\n')
        }
        return sb.toString()
    }
}
