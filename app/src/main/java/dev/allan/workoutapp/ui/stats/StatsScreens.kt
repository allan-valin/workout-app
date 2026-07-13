package dev.allan.workoutapp.ui.stats

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

@Composable
fun GranularityChips(selected: Granularity, onSelect: (Granularity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Granularity.entries.forEach { g ->
            FilterChip(
                selected = selected == g,
                onClick = { onSelect(g) },
                label = { Text(stringResource(g.labelRes)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyweightScreen(onBack: () -> Unit, vm: StatsViewModel = viewModel()) {
    val metrics by vm.bodyMetrics.collectAsState()
    var range by remember { mutableStateOf(StatsRange.MONTH) }
    var granularity by remember { mutableStateOf(Granularity.DAY) }
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
        dev.allan.workoutapp.ui.common.ScrollbarColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            edgePadding = 16.dp,
        ) {
            RangeChips(range) { range = it }
            GranularityChips(granularity) { granularity = it }
            if (series.isEmpty()) {
                Text(stringResource(R.string.no_data_yet))
            } else {
                TimeSeriesChart(
                    points = series,
                    range = range,
                    granularity = granularity,
                    aggregate = SeriesAggregate.MEAN,
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
    // Volume series are per-session — weekly buckets read better by default (Allan:
    // the same workout runs at most ~3x a week).
    var granularity by remember { mutableStateOf(Granularity.WEEK) }

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
        dev.allan.workoutapp.ui.common.ScrollbarColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            edgePadding = 16.dp,
        ) {
            // Averages block moved here from the stats card (the card stays light).
            if (averages.sessions > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatLine(stringResource(R.string.sessions_count), averages.sessions.toString())
                        StatLine(stringResource(R.string.avg_session_duration), fmtHm(averages.avgDurationSecs))
                        StatLine(stringResource(R.string.avg_volume), "%.1f kg".format(averages.avgVolumeKg))
                        StatLine(stringResource(R.string.active_time), fmtHm(averages.avgActiveSecs))
                        StatLine(stringResource(R.string.rest_time), fmtHm(averages.avgRestSecs))
                        StatLine(stringResource(R.string.idle_time), fmtHm(averages.avgIdleSecs))
                    }
                }
            }
            RangeChips(range) { range = it }
            GranularityChips(granularity) { granularity = it }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.volume_over_time), fontWeight = FontWeight.Bold)
                    if (averages.volumeSeries.isEmpty()) Text(stringResource(R.string.no_data_yet))
                    else TimeSeriesChart(
                        points = averages.volumeSeries,
                        range = range,
                        granularity = granularity,
                        aggregate = SeriesAggregate.SUM,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
            }
            muscleSeries.forEach { (muscleName, series) ->
                if (series.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(muscleName, fontWeight = FontWeight.Bold)
                            TimeSeriesChart(
                                points = series,
                                range = range,
                                granularity = granularity,
                                aggregate = SeriesAggregate.SUM,
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
