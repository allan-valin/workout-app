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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.StatsCalc
import dev.allan.workoutapp.data.db.BodyMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

    /** Muscle display name -> (session start, volume) series for the progression page. */
    private val _muscleSeries = MutableStateFlow<Map<String, List<Pair<Long, Double>>>>(emptyMap())
    val muscleSeries: StateFlow<Map<String, List<Pair<Long, Double>>>> = _muscleSeries

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
                    _muscleSeries.value = emptyMap()
                    return@collect
                }
                // Per-muscle volume per session for the progression graphs.
                val lang = dev.allan.workoutapp.currentAppLang()
                val muscleName = db.exerciseDao().muscles().first()
                    .associate { it.id to dev.allan.workoutapp.data.MuscleNames.display(it.nameEn, lang) }
                val primaryCache = mutableMapOf<String, List<Int>>()
                suspend fun primaries(id: String) = primaryCache.getOrPut(id) {
                    db.exerciseDao().exercise(id)?.primaryMuscles ?: emptyList()
                }
                val logsBySession = logs.groupBy { it.sessionId }
                val startBySession = sessions.associate { it.id to it.startedAt }
                val perMuscle = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
                for ((sessionId, sessionLogs) in logsBySession) {
                    val start = startBySession[sessionId] ?: continue
                    val perId = mutableMapOf<Int, Double>()
                    for (log in sessionLogs) {
                        val muscle = primaries(log.exerciseId).firstOrNull() ?: continue
                        perId[muscle] = (perId[muscle] ?: 0.0) + StatsCalc.volumeKg(log)
                    }
                    perId.forEach { (id, vol) ->
                        val name = muscleName[id] ?: return@forEach
                        perMuscle.getOrPut(name) { mutableListOf() }.add(start to vol)
                    }
                }
                _muscleSeries.value = perMuscle.mapValues { (_, v) -> v.sortedBy { it.first } }
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

    /** One entry per day; [epochDay] lets forgotten days be backfilled or edited. */
    fun addBodyWeight(kg: Double, epochDay: Long = LocalDate.now().toEpochDay()) {
        viewModelScope.launch {
            db.sessionDao().upsertBodyMetric(BodyMetric(epochDay = epochDay, weightKg = kg))
        }
    }
}

private fun fmtHm(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StatsTab(
    onOpenBodyweight: () -> Unit,
    onOpenProgression: () -> Unit,
    vm: StatsViewModel = viewModel(),
) {
    val averages by vm.averages.collectAsState()
    val bodyMetrics by vm.bodyMetrics.collectAsState()
    var showAddWeight by remember { mutableStateOf(false) }

    // Bodyweight first, progression second (Allan's requested order). Height/BMI
    // removed — irrelevant for lifting. Cards open their full-screen graph pages.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(onClick = onOpenBodyweight, modifier = Modifier.fillMaxWidth()) {
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
                    val monthCutoff = LocalDate.now().minusDays(30).toEpochDay()
                    PointAreaChart(
                        points = bodyMetrics
                            .filter { it.epochDay >= monthCutoff }
                            .map { it.epochDay * 86_400_000L to it.weightKg }
                            .ifEmpty { listOf(bodyMetrics.last().let { it.epochDay * 86_400_000L to it.weightKg }) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                    )
                    Text(
                        stringResource(R.string.tap_for_details),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Card(onClick = onOpenProgression, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.progression), fontWeight = FontWeight.Bold)
                if (averages.sessions == 0) {
                    Text(stringResource(R.string.no_data_yet))
                } else {
                    StatLine(stringResource(R.string.sessions_count), averages.sessions.toString())
                    StatLine(stringResource(R.string.avg_session_duration), fmtHm(averages.avgDurationSecs))
                    StatLine(stringResource(R.string.avg_volume), "%.1f kg".format(averages.avgVolumeKg))
                    StatLine(stringResource(R.string.active_time), fmtHm(averages.avgActiveSecs))
                    StatLine(stringResource(R.string.rest_time), fmtHm(averages.avgRestSecs))
                    StatLine(stringResource(R.string.idle_time), fmtHm(averages.avgIdleSecs))
                    Text(
                        stringResource(R.string.tap_for_details),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    if (showAddWeight) {
        var text by remember { mutableStateOf("") }
        // Date defaults to today but is pickable — forgotten days can be backfilled.
        val dateState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis(),
        )
        var showDatePicker by remember { mutableStateOf(false) }
        val pickedDay = (dateState.selectedDateMillis ?: System.currentTimeMillis()) / 86_400_000L
        AlertDialog(
            onDismissRequest = { showAddWeight = false },
            title = { Text(stringResource(R.string.add_weight)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.bodyweight) + " (kg)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    // Bordered cell + calendar icon so the date reads as tappable.
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            LocalDate.ofEpochDay(pickedDay).toString(),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = stringResource(R.string.add_weight),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.replace(',', '.').toDoubleOrNull()?.let { vm.addBodyWeight(it, pickedDay) }
                    showAddWeight = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddWeight = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
        if (showDatePicker) {
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.ok)) }
                },
            ) {
                androidx.compose.material3.DatePicker(state = dateState)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
