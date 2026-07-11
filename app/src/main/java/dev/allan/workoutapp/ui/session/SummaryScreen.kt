package dev.allan.workoutapp.ui.session

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.MuscleNames
import dev.allan.workoutapp.data.StatsCalc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SummaryState(
    val workoutId: Long = 0,
    val totalSecs: Int = 0,
    val activeSecs: Int = 0,
    val restSecs: Int = 0,
    val idleSecs: Int = 0,
    val totalVolumeKg: Double = 0.0,
    /** muscle display name -> volume kg, sorted desc. */
    val volumePerMuscle: List<Pair<String, Double>> = emptyList(),
    val setCount: Int = 0,
)

class SummaryViewModel(app: Application, private val sessionId: Long, private val lang: String) :
    AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state

    init {
        viewModelScope.launch {
            val session = db.sessionDao().session(sessionId) ?: return@launch
            val logs = db.sessionDao().setLogs(sessionId)
            val muscleNames = mutableMapOf<Int, String>()
            db.exerciseDao().muscles().collect { muscles ->
                muscles.forEach { muscleNames[it.id] = MuscleNames.display(it.nameEn, lang) }
                val exerciseMuscles = mutableMapOf<String, List<Int>>()
                logs.map { it.exerciseId }.distinct().forEach { id ->
                    exerciseMuscles[id] = db.exerciseDao().exercise(id)?.primaryMuscles ?: emptyList()
                }
                val perMuscle = StatsCalc.volumePerMuscle(logs) { exerciseMuscles[it] ?: emptyList() }
                val total = ((session.endedAt ?: System.currentTimeMillis()) - session.startedAt) / 1000L
                _state.value = SummaryState(
                    workoutId = session.workoutId,
                    totalSecs = total.toInt(),
                    activeSecs = session.activeSecs,
                    restSecs = session.restSecs,
                    idleSecs = (total.toInt() - session.activeSecs - session.restSecs).coerceAtLeast(0),
                    totalVolumeKg = logs.sumOf(StatsCalc::volumeKg),
                    volumePerMuscle = perMuscle.entries
                        .map { (muscleNames[it.key] ?: "?") to it.value }
                        .sortedByDescending { it.second },
                    setCount = logs.size,
                )
                return@collect
            }
        }
    }

    class Factory(private val app: Application, private val sessionId: Long, private val lang: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SummaryViewModel(app, sessionId, lang) as T
    }
}

private fun fmtHm(secs: Int): String =
    if (secs >= 3600) "%d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60)
    else "%d:%02d".format(secs / 60, secs % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    sessionId: Long,
    appLang: String,
    onClose: () -> Unit,
    onBackToWorkout: (Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SummaryViewModel = viewModel(
        key = "summary-$sessionId",
        factory = SummaryViewModel.Factory(app, sessionId, appLang),
    )
    val state by vm.state.collectAsState()

    // Spec: back returns to the finished workout in view mode; Close goes home.
    BackHandler { onBackToWorkout(state.workoutId) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.summary)) }) }
    ) { padding ->
        dev.allan.workoutapp.ui.common.ScrollbarColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            edgePadding = 16.dp,
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(stringResource(R.string.total_time), fmtHm(state.totalSecs))
                    StatRow(stringResource(R.string.active_time), fmtHm(state.activeSecs))
                    StatRow(stringResource(R.string.rest_time), fmtHm(state.restSecs))
                    StatRow(stringResource(R.string.idle_time), fmtHm(state.idleSecs))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(stringResource(R.string.sets_logged), state.setCount.toString())
                    StatRow(
                        stringResource(R.string.total_volume),
                        "%.1f kg".format(state.totalVolumeKg),
                    )
                    if (state.volumePerMuscle.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.volume_per_muscle), fontWeight = FontWeight.Bold)
                        state.volumePerMuscle.forEach { (muscle, volume) ->
                            StatRow(muscle, "%.1f kg".format(volume))
                        }
                    }
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
