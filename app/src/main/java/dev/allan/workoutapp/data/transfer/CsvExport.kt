package dev.allan.workoutapp.data.transfer

import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.ValueUnit
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

    /** One logged set: which exercise, which day, which slot, and what was done. */
    data class SetPoint(
        val exerciseName: String,
        val date: String,
        val setIndex: Int,
        val type: dev.allan.workoutapp.data.db.SetType,
        val weightKg: Double,
        val value: Int,
        val unit: ValueUnit,
        val completedAt: Long,
    )

    /**
     * Weight evolution of the active cycle: one row per SET of each exercise (name repeated,
     * set number, W for warm-ups), one column per training day, cell = "weight x reps"
     * ("BW x 46" for bodyweight work, "75s" / "24 x 40s" for timed sets). One value per
     * exercise said nothing when every set has its own reps and weight, and bodyweight
     * exercises progress in reps only (Allan, 30/09). Sessions count when they belong to one
     * of the active plan's workouts and started after the plan did.
     */
    suspend fun weightEvolution(db: AppDatabase, lang: String): String {
        val plan = db.planDao().activePlanNow() ?: return setPivot(emptyList())
        val workoutIds = db.planDao().workoutsList(plan.id).map { it.id }.toSet()
        val since = plan.startedAt ?: 0L
        val sessions = db.sessionDao().finishedSessions()
            .filter { it.workoutId in workoutIds && it.startedAt >= since }
            .associateBy { it.id }
        val names = mutableMapOf<String, String>()
        val points = db.sessionDao().allSetLogs()
            .filter { it.sessionId in sessions }
            .map { log ->
                SetPoint(
                    exerciseName = names.getOrPut(log.exerciseId) { PlanRepo.displayName(db, log.exerciseId, lang) },
                    date = dayFmt.format(Instant.ofEpochMilli(log.completedAt).atZone(ZoneId.systemDefault())),
                    setIndex = log.setIndex,
                    type = log.type,
                    weightKg = log.weightKg,
                    value = log.value,
                    unit = log.valueUnit,
                    completedAt = log.completedAt,
                )
            }
        return setPivot(points)
    }

    /** Pure pivot builder behind [weightEvolution]; exercises sorted by name, sets and days ascending. */
    fun setPivot(points: List<SetPoint>): String {
        val days = points.map { it.date }.distinct().sorted()
        val sb = StringBuilder("exercise,set")
        days.forEach { sb.append(',').append(it) }
        sb.append('\n')
        points.groupBy { it.exerciseName }.toSortedMap().forEach { (name, pts) ->
            pts.groupBy { it.setIndex }.toSortedMap().forEach { (setIndex, slot) ->
                // The newest log of a day wins the cell (two sessions on one day).
                val byDay = slot.groupBy { it.date }.mapValues { (_, l) -> l.maxBy { it.completedAt } }
                val warm = slot.any { it.type == dev.allan.workoutapp.data.db.SetType.WARMUP }
                sb.append(esc(name)).append(',').append(setIndex + 1).append(if (warm) " W" else "")
                days.forEach { d -> sb.append(',').append(byDay[d]?.let(::cell) ?: "") }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun num(v: Double): String =
        if (v == Math.rint(v)) v.toLong().toString() else v.toString()

    private fun cell(p: SetPoint): String {
        val work = if (p.unit == ValueUnit.SECS) p.value.toString() + "s" else p.value.toString()
        return when {
            p.unit == ValueUnit.SECS && p.weightKg <= 0.0 -> work
            p.weightKg <= 0.0 -> "BW x " + work
            else -> num(p.weightKg) + " x " + work
        }
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
