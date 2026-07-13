package dev.allan.workoutapp.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.allan.workoutapp.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal const val DAY_MS = 86_400_000L

/** How the raw series is thinned to one point per bucket (Allan: pick your own smoothness). */
enum class Granularity(val labelRes: Int) {
    DAY(R.string.gran_day),
    WEEK(R.string.gran_week),
    MONTH(R.string.gran_month),
}

/** Bucket combiner: bodyweight averages, training volume adds up. */
enum class SeriesAggregate { MEAN, SUM }

/**
 * One point per granularity bucket; x = the bucket's latest sample time, so the newest
 * point stays at "now" instead of snapping to a bucket boundary.
 */
internal fun bucket(
    points: List<Pair<Long, Double>>,
    granularity: Granularity,
    aggregate: SeriesAggregate,
): List<Pair<Long, Double>> {
    fun key(t: Long): Long {
        val day = Math.floorDiv(t, DAY_MS)
        return when (granularity) {
            Granularity.DAY -> day
            // epochDay 0 = a Thursday; +3 makes weeks start on Monday.
            Granularity.WEEK -> Math.floorDiv(day + 3, 7)
            Granularity.MONTH -> LocalDate.ofEpochDay(day).let { it.year * 12L + it.monthValue }
        }
    }
    return points.groupBy { key(it.first) }
        .map { (_, pts) ->
            val sum = pts.sumOf { it.second }
            pts.maxOf { it.first } to if (aggregate == SeriesAggregate.SUM) sum else sum / pts.size
        }
        .sortedBy { it.first }
}

/**
 * Y gridline spacing: the smallest "round" step that needs at most ~5 lines for the
 * value spread — 0.5 kg for small bodyweight moves, up into the thousands for volume.
 */
internal fun gridStep(spread: Double): Double {
    val steps = doubleArrayOf(
        0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
        1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
    )
    return steps.firstOrNull { spread / it <= 5.0 } ?: steps.last()
}

/**
 * X-axis tick positions + labels per Allan's spec: 7d = every day (today rightmost),
 * 1m = every 7 days back from today, 3m = monthly, 6m = 2-month steps, 1y = 3-month
 * steps (today itself unlabeled beyond the month ranges), all = calendar years when the
 * data spans more than two, else 3-month steps. Ticks are generated over the whole
 * scrollable history and clipped to the visible window at draw time.
 */
internal fun xTicks(
    range: StatsRange,
    xStartMs: Long,
    today: LocalDate = LocalDate.now(),
): List<Pair<Long, String>> {
    fun ms(d: LocalDate) = d.toEpochDay() * DAY_MS
    val fmt = DateTimeFormatter.ofPattern("d/M")
    val out = mutableListOf<Pair<Long, String>>()
    fun monthsBack(stepMonths: Long) {
        var i = stepMonths
        while (true) {
            val d = today.minusMonths(i)
            if (ms(d) < xStartMs) break
            out += ms(d) to d.format(fmt)
            i += stepMonths
        }
    }
    when (range) {
        StatsRange.WEEK -> {
            var d = today
            while (ms(d) >= xStartMs) {
                out += ms(d) to d.dayOfMonth.toString()
                d = d.minusDays(1)
            }
        }
        StatsRange.MONTH -> {
            var d = today
            while (ms(d) >= xStartMs) {
                out += ms(d) to d.format(fmt)
                d = d.minusDays(7)
            }
        }
        StatsRange.THREE_MONTHS -> monthsBack(1)
        StatsRange.SIX_MONTHS -> monthsBack(2)
        StatsRange.YEAR -> monthsBack(3)
        StatsRange.ALL ->
            if (ms(today) - xStartMs > 2 * 365 * DAY_MS) {
                var year = today.year
                while (ms(LocalDate.of(year, 1, 1)) >= xStartMs) {
                    out += ms(LocalDate.of(year, 1, 1)) to year.toString()
                    year--
                }
            } else {
                monthsBack(3)
            }
    }
    return out
}

/**
 * Point graph over real time with the selected range as the visible window. Drag
 * horizontally to pan back through the whole history (the window clamps to the data).
 * Dotted y gridlines sit on round values; x ticks/labels follow the range. The canvas
 * stays screen-sized and pans by remapping time, so long histories cost nothing.
 */
@Composable
fun TimeSeriesChart(
    points: List<Pair<Long, Double>>,
    range: StatsRange,
    granularity: Granularity,
    aggregate: SeriesAggregate,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.isEmpty()) return
    val series = remember(points, granularity, aggregate) { bucket(points, granularity, aggregate) }

    val xEnd = maxOf((LocalDate.now().toEpochDay() + 1) * DAY_MS, series.last().first)
    val windowMs = range.days?.times(DAY_MS)
        ?: (xEnd - series.first().first).coerceAtLeast(DAY_MS)
    val xStart = minOf(series.first().first - DAY_MS / 2, xEnd - windowMs)
    // Right edge of the visible window; starts at "now", drag moves it back in time.
    var rightEdge by remember(series, range) { mutableLongStateOf(xEnd) }
    val minRightEdge = minOf(xEnd, xStart + windowMs)

    val ticks = remember(range, xStart) { xTicks(range, xStart) }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier.pointerInput(series, range, windowMs) {
            detectHorizontalDragGestures { _, dragAmount ->
                val msPerPx = windowMs.toDouble() / size.width
                rightEdge = (rightEdge - (dragAmount * msPerPx).toLong())
                    .coerceIn(minRightEdge, xEnd)
            }
        },
    ) {
        val xLabelBand = 16.dp.toPx()
        val plotH = size.height - xLabelBand
        val pad = 10f
        val winStart = rightEdge - windowMs
        fun x(t: Long) = pad + (size.width - 2 * pad) * (t - winStart).toFloat() / windowMs.toFloat()

        // Y scale follows only the VISIBLE points (Allan: a flat month inside a 20 kg
        // history still gets the 0.5 kg grid), padded by one grid step top and bottom so
        // the extremes sit one line in from the edges. Rescales live while panning.
        val visible = series.filter { it.first in winStart..rightEdge }
            .ifEmpty { series }
        val yMin = visible.minOf { it.second }
        val yMax = visible.maxOf { it.second }
        val step = gridStep(yMax - yMin)
        val yBottom = yMin - step
        val firstLine = (ceil(yBottom / step) * step)
            .let { if (it <= yBottom + step * 1e-6) it + step else it }
        // At least 3 gridlines (bottom + 2 above — Allan: a near-flat window otherwise
        // showed a single line with no scale to read against).
        val yTop = maxOf(yMax + step, firstLine + 2.5 * step)
        fun y(v: Double) = plotH * (1f - ((v - yBottom) / (yTop - yBottom)).toFloat())

        // Dotted gridlines on round multiples of the step, labeled at the left edge.
        // Lines landing exactly on the padded top/bottom edges are skipped — the pad is
        // there so the extremes sit one step inside, not to draw a border.
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        var grid = firstLine
        while (grid <= yTop - step * 0.5) {
            val gy = y(grid)
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), pathEffect = dash)
            val text = if (grid % 1.0 == 0.0) "%.0f".format(grid) else "%.1f".format(grid)
            val layout = measurer.measure(text, labelStyle)
            drawText(layout, topLeft = Offset(2f, gy - layout.size.height - 1f))
            grid += step
        }

        // Axis baseline + range-dependent date ticks (clipped to the visible window).
        drawLine(axisColor, Offset(0f, plotH), Offset(size.width, plotH))
        ticks.forEach { (t, label) ->
            if (t < winStart || t > rightEdge) return@forEach
            val tx = x(t)
            drawLine(axisColor, Offset(tx, plotH), Offset(tx, plotH + 4.dp.toPx() / 2))
            val layout = measurer.measure(label, labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (tx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                    plotH + 3f,
                ),
            )
        }

        // Series: only the window plus one neighbor each side, so lines enter/exit
        // cleanly. Clipped to the plot band — off-window neighbors can sit far outside
        // the visible-window y scale.
        val first = series.indexOfLast { it.first <= winStart }.coerceAtLeast(0)
        val last = series.indexOfFirst { it.first >= rightEdge }
            .let { if (it == -1) series.lastIndex else it }
        val coords = series.subList(first, last + 1).map { (t, v) -> Offset(x(t), y(v)) }
        clipRect(0f, 0f, size.width, plotH) {
            if (coords.size >= 2) {
                val area = Path().apply {
                    moveTo(coords.first().x, plotH)
                    coords.forEach { lineTo(it.x, it.y) }
                    lineTo(coords.last().x, plotH)
                    close()
                }
                drawPath(area, color = color.copy(alpha = 0.2f))
                for (i in 0 until coords.size - 1) {
                    drawLine(color, coords[i], coords[i + 1], strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
            coords.forEach {
                if (it.x in 0f..size.width) drawCircle(color = color, radius = 8f, center = it)
            }
        }
    }
}
