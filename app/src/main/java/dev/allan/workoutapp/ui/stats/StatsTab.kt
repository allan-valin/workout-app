package dev.allan.workoutapp.ui.stats

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.Settings
import dev.allan.workoutapp.data.StatsCalc
import dev.allan.workoutapp.data.db.BodyMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StatsAverages(
    val sessions: Int = 0,
    val avgDurationSecs: Int = 0,
    val avgActiveSecs: Int = 0,
    val avgRestSecs: Int = 0,
    val avgIdleSecs: Int = 0,
    val avgVolumeKg: Double = 0.0,
    /** startedAt millis -> session volume, chronological. */
    val volumeSeries: List<Pair<Long, Double>> = emptyList(),
)

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _averages = MutableStateFlow(StatsAverages())
    val averages: StateFlow<StatsAverages> = _averages

    val bodyMetrics: StateFlow<List<BodyMetric>> = db.sessionDao().bodyMetrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val heightCm: StateFlow<Double?> = Settings.heightCm(app)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            // Recompute whenever the finished-session set changes.
            db.sessionDao().finishedSessionsFlow().collect { sessions ->
                val logs = db.sessionDao().allFinishedLogs()
                val volumeBySession = logs.groupBy { it.sessionId }
                    .mapValues { (_, l) -> l.sumOf(StatsCalc::volumeKg) }
                val n = sessions.size
                if (n == 0) {
                    _averages.value = StatsAverages()
                    return@collect
                }
                val durations = sessions.map { (((it.endedAt ?: it.startedAt) - it.startedAt) / 1000L).toInt() }
                _averages.value = StatsAverages(
                    sessions = n,
                    avgDurationSecs = durations.sum() / n,
                    avgActiveSecs = sessions.sumOf { it.activeSecs } / n,
                    avgRestSecs = sessions.sumOf { it.restSecs } / n,
                    avgIdleSecs = (durations.sum() - sessions.sumOf { it.activeSecs } -
                        sessions.sumOf { it.restSecs }).coerceAtLeast(0) / n,
                    avgVolumeKg = sessions.sumOf { volumeBySession[it.id] ?: 0.0 } / n,
                    volumeSeries = sessions.map { it.startedAt to (volumeBySession[it.id] ?: 0.0) },
                )
            }
        }
    }

    fun addBodyWeight(kg: Double) {
        viewModelScope.launch {
            db.sessionDao().upsertBodyMetric(BodyMetric(epochDay = LocalDate.now().toEpochDay(), weightKg = kg))
        }
    }

    fun setHeight(cm: Double?) {
        viewModelScope.launch { Settings.setHeightCm(getApplication(), cm) }
    }
}

private fun fmtHm(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)

@Composable
fun StatsTab(vm: StatsViewModel = viewModel()) {
    val averages by vm.averages.collectAsState()
    val bodyMetrics by vm.bodyMetrics.collectAsState()
    val heightCm by vm.heightCm.collectAsState()
    var showAddWeight by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.averages), fontWeight = FontWeight.Bold)
                if (averages.sessions == 0) {
                    Text(stringResource(R.string.no_data_yet))
                } else {
                    StatLine(stringResource(R.string.sessions_count), averages.sessions.toString())
                    StatLine(stringResource(R.string.avg_session_duration), fmtHm(averages.avgDurationSecs))
                    StatLine(stringResource(R.string.avg_volume), "%.1f kg".format(averages.avgVolumeKg))
                    StatLine(stringResource(R.string.active_time), fmtHm(averages.avgActiveSecs))
                    StatLine(stringResource(R.string.rest_time), fmtHm(averages.avgRestSecs))
                    StatLine(stringResource(R.string.idle_time), fmtHm(averages.avgIdleSecs))
                }
            }
        }

        if (averages.volumeSeries.size >= 2) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.volume_over_time), fontWeight = FontWeight.Bold)
                    LineChart(
                        points = averages.volumeSeries.map { it.second },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.bodyweight), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddWeight = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(R.string.add_weight))
                    }
                }
                if (bodyMetrics.isEmpty()) {
                    Text(stringResource(R.string.no_data_yet))
                } else {
                    StatLine(
                        stringResource(R.string.current_weight),
                        "%.1f kg".format(bodyMetrics.last().weightKg),
                    )
                    if (bodyMetrics.size >= 2) {
                        LineChart(
                            points = bodyMetrics.map { it.weightKg },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                        )
                    }
                }
                HeightField(heightCm, onCommit = vm::setHeight)
            }
        }
    }

    if (showAddWeight) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddWeight = false },
            title = { Text(stringResource(R.string.add_weight)) },
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
                    text.replace(',', '.').toDoubleOrNull()?.let { vm.addBodyWeight(it) }
                    showAddWeight = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddWeight = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun HeightField(heightCm: Double?, onCommit: (Double?) -> Unit) {
    var text by remember(heightCm) {
        mutableStateOf(heightCm?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it.replace(',', '.').toDoubleOrNull())
        },
        label = { Text(stringResource(R.string.height_cm)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Minimal dependency-free polyline chart with min/max labels. */
@Composable
fun LineChart(points: List<Double>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (points.size < 2) return
    val min = points.min()
    val max = points.max()
    Column(modifier) {
        Text("%.1f".format(max), style = MaterialTheme.typography.labelSmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val stepX = size.width / (points.size - 1)
            val coords = points.mapIndexed { i, v ->
                Offset(i * stepX, size.height * (1f - ((v - min) / range).toFloat()))
            }
            for (i in 0 until coords.size - 1) {
                drawLine(
                    color = color,
                    start = coords[i],
                    end = coords[i + 1],
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
            }
            coords.forEach { drawCircle(color = color, radius = 7f, center = it) }
        }
        Text("%.1f".format(min), style = MaterialTheme.typography.labelSmall)
    }
}
