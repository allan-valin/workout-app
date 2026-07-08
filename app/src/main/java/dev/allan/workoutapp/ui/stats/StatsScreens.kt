package dev.allan.workoutapp.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import java.time.LocalDate

/** Selectable time windows for the stats graphs; null days = since first entry. */
enum class StatsRange(val days: Long?, val labelRes: Int) {
    WEEK(7, R.string.range_1w),
    MONTH(30, R.string.range_1m),
    THREE_MONTHS(91, R.string.range_3m),
    SIX_MONTHS(182, R.string.range_6m),
    YEAR(365, R.string.range_1y),
    ALL(null, R.string.range_all),
}

/**
 * Point graph over real time: x = instant, y = value; dots connected by a line
 * with the area under it filled. Dependency-free Canvas.
 */
@Composable
fun PointAreaChart(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.isEmpty()) return
    val sorted = remember(points) { points.sortedBy { it.first } }
    val min = sorted.minOf { it.second }
    val max = sorted.maxOf { it.second }
    Column(modifier) {
        Text("%.1f".format(max), style = MaterialTheme.typography.labelSmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            val xMin = sorted.first().first
            val xMax = sorted.last().first
            val xRange = (xMax - xMin).takeIf { it > 0 } ?: 1L
            val yRange = (max - min).takeIf { it > 0 } ?: 1.0
            val coords = sorted.map { (t, v) ->
                Offset(
                    size.width * ((t - xMin).toFloat() / xRange.toFloat()),
                    // 10% headroom top and bottom so extremes don't sit on the edge.
                    size.height * (0.9f - 0.8f * ((v - min) / yRange).toFloat()),
                )
            }
            if (coords.size >= 2) {
                val area = Path().apply {
                    moveTo(coords.first().x, size.height)
                    coords.forEach { lineTo(it.x, it.y) }
                    lineTo(coords.last().x, size.height)
                    close()
                }
                drawPath(area, color = color.copy(alpha = 0.2f))
                for (i in 0 until coords.size - 1) {
                    drawLine(color, coords[i], coords[i + 1], strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
            coords.forEach { drawCircle(color = color, radius = 8f, center = it) }
        }
        Text("%.1f".format(min), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun RangeChips(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        StatsRange.entries.forEach { r ->
            FilterChip(
                selected = selected == r,
                onClick = { onSelect(r) },
                label = { Text(stringResource(r.labelRes)) },
            )
        }
    }
}

/** Cut a (epochMillis, value) series down to the selected window. */
fun <T> windowed(series: List<Pair<Long, T>>, range: StatsRange): List<Pair<Long, T>> {
    val days = range.days ?: return series
    val cutoff = System.currentTimeMillis() - days * 86_400_000L
    return series.filter { it.first >= cutoff }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyweightScreen(onBack: () -> Unit, vm: StatsViewModel = viewModel()) {
    val metrics by vm.bodyMetrics.collectAsState()
    var range by remember { mutableStateOf(StatsRange.MONTH) }
    var editDay by remember { mutableStateOf<Long?>(null) }

    val series = metrics.map { it.epochDay * 86_400_000L to it.weightKg }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bodyweight)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeChips(range) { range = it }
            val shown = windowed(series, range)
            if (shown.isEmpty()) {
                Text(stringResource(R.string.no_data_yet))
            } else {
                PointAreaChart(
                    points = shown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
            Text(stringResource(R.string.last_entries), fontWeight = FontWeight.Bold)
            metrics.takeLast(7).reversed().forEach { m ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(LocalDate.ofEpochDay(m.epochDay).toString())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%.1f kg".format(m.weightKg), fontWeight = FontWeight.Medium)
                        IconButton(onClick = { editDay = m.epochDay }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_weight))
                        }
                    }
                }
            }
        }
    }

    editDay?.let { day ->
        val current = metrics.firstOrNull { it.epochDay == day }?.weightKg
        var text by remember(day) { mutableStateOf(current?.let { "%.1f".format(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editDay = null },
            title = { Text(LocalDate.ofEpochDay(day).toString()) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.bodyweight) + " (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.replace(',', '.').toDoubleOrNull()?.let { vm.addBodyWeight(it, day) }
                    editDay = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editDay = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionScreen(onBack: () -> Unit, vm: StatsViewModel = viewModel()) {
    val averages by vm.averages.collectAsState()
    val muscleSeries by vm.muscleSeries.collectAsState()
    var range by remember { mutableStateOf(StatsRange.MONTH) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.progression)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RangeChips(range) { range = it }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.volume_over_time), fontWeight = FontWeight.Bold)
                    val shown = windowed(averages.volumeSeries, range)
                    if (shown.isEmpty()) Text(stringResource(R.string.no_data_yet))
                    else PointAreaChart(
                        points = shown,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
            }
            muscleSeries.forEach { (muscleName, series) ->
                val shown = windowed(series, range)
                if (shown.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(muscleName, fontWeight = FontWeight.Bold)
                            PointAreaChart(
                                points = shown,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
