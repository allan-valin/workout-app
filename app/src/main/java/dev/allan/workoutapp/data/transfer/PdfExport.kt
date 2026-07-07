package dev.allan.workoutapp.data.transfer

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Printable plan sheet: one section per workout, exercises with their set tables.
 * A4 portrait (595×842 pt @72dpi), plain Paint text — no extra dependencies.
 */
object PdfExport {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val LINE = 16f

    suspend fun plan(db: AppDatabase, planId: Long, lang: String): ByteArray? {
        val plan = db.planDao().plan(planId) ?: return null

        val title = Paint().apply {
            textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val h2 = Paint().apply {
            textSize = 15f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint().apply { textSize = 11f }
        val small = Paint().apply { textSize = 10f; color = Color.DKGRAY }

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var y = MARGIN + 10f

        fun newPageIfNeeded(needed: Float) {
            if (y + needed <= PAGE_H - MARGIN) return
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN + 10f
        }

        fun draw(text: String, paint: Paint, indent: Float = 0f, advance: Float = LINE) {
            newPageIfNeeded(advance)
            page.canvas.drawText(text, MARGIN + indent, y, paint)
            y += advance
        }

        draw(plan.name, title, advance = 30f)
        plan.cycleWeeks?.let { draw("Cycle: $it weeks", small, advance = 20f) }

        for (w in db.planDao().workoutsList(planId).filter { !it.archived }) {
            val days = w.daysOfWeek.joinToString(" · ") {
                DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            y += 6f
            draw(w.name + if (days.isNotEmpty()) "   —   $days" else "", h2, advance = 22f)

            val wes = db.planDao().workoutExercisesList(w.id)
            wes.forEachIndexed { i, we ->
                val name = PlanRepo.displayName(db, we.exerciseId, lang)
                val mode = when (we.weightMode) {
                    WeightMode.TOTAL -> ""
                    WeightMode.PER_DUMBBELL -> "  [kg per dumbbell]"
                    WeightMode.PER_SIDE -> "  [kg per side, bar ${we.barWeightKg} kg]"
                }
                val link = if (we.supersetWithPrev) "  ⇄ superset with previous" else ""
                newPageIfNeeded(LINE * 2)
                draw("${i + 1}. $name$mode$link", body, advance = LINE)
                if (we.note.isNotBlank()) draw(we.note, small, indent = 14f)
                db.planDao().setTemplatesList(we.id).forEach { s ->
                    val type = when (s.type) {
                        SetType.WARMUP -> "W"
                        SetType.NORMAL -> "N"
                        SetType.FAILURE -> "F"
                        SetType.DROP -> "D"
                        SetType.SUPERSET -> "S"
                    }
                    val target = if (s.valueUnit == ValueUnit.REPS) {
                        (s.targetValueMax?.let { "${s.targetValue}–$it" } ?: "${s.targetValue}") + " reps"
                    } else "${s.targetValue} s"
                    val weight = if (s.targetWeightKg > 0.0) "${s.targetWeightKg} kg × " else ""
                    draw("[$type]  $weight$target   rest ${s.restSecs} s", small, indent = 14f)
                }
            }
        }

        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
