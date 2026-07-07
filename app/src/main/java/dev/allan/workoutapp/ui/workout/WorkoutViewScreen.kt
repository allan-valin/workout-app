package dev.allan.workoutapp.ui.workout

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.ExerciseTranslation
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ViewExercise(
    val workoutExerciseId: Long,
    val exerciseId: String,
    val name: String,
    /** Compact summary, e.g. "3× 10 Reps · 40 kg". */
    val summary: String,
)

class WorkoutViewViewModel(app: Application, private val workoutId: Long, private val lang: String) :
    AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _workout = MutableStateFlow<Workout?>(null)
    val workout: StateFlow<Workout?> = _workout

    private val _detail = MutableStateFlow<List<ExerciseTranslation>?>(null)
    val detail: StateFlow<List<ExerciseTranslation>?> = _detail

    /** True while a session for THIS workout is running — the start button becomes "resume". */
    val hasRunningSession: StateFlow<Boolean> =
        db.sessionDao().runningSessionFlow()
            .map { it?.workoutId == workoutId }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val exercises: StateFlow<List<ViewExercise>> =
        combine(
            db.planDao().workoutExercises(workoutId),
            db.planDao().setTemplatesForWorkout(workoutId),
        ) { wes, sets -> wes to sets }
            .map { (wes, sets) ->
                wes.map { we ->
                    val mySets = sets.filter { it.workoutExerciseId == we.id }
                    ViewExercise(
                        workoutExerciseId = we.id,
                        exerciseId = we.exerciseId,
                        name = PlanRepo.displayName(db, we.exerciseId, lang),
                        summary = summarize(mySets),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { _workout.value = db.planDao().workout(workoutId) }
    }

    private fun summarize(sets: List<SetTemplate>): String {
        if (sets.isEmpty()) return "—"
        val first = sets.first()
        val sameValue = sets.all { it.targetValue == first.targetValue && it.valueUnit == first.valueUnit }
        val unit = if (first.valueUnit == ValueUnit.REPS) "Reps" else "Secs"
        return if (sameValue) "${sets.size}× ${first.targetValue} $unit"
        else sets.joinToString("/") { it.targetValue.toString() } + " $unit"
    }

    fun openDetail(exerciseId: String) {
        viewModelScope.launch { _detail.value = db.exerciseDao().translations(exerciseId) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    class Factory(private val app: Application, private val workoutId: Long, private val lang: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkoutViewViewModel(app, workoutId, lang) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutViewScreen(
    workoutId: Long,
    appLang: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: WorkoutViewViewModel = viewModel(
        key = "view-$workoutId",
        factory = WorkoutViewViewModel.Factory(app, workoutId, appLang),
    )
    val workout by vm.workout.collectAsState()
    val exercises by vm.exercises.collectAsState()
    val detail by vm.detail.collectAsState()
    val hasRunningSession by vm.hasRunningSession.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_training))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text(
                    stringResource(
                        if (hasRunningSession) R.string.resume_workout_button else R.string.start_workout
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(exercises, key = { it.workoutExerciseId }) { ex ->
                    Card(onClick = { vm.openDetail(ex.exerciseId) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ex.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(ex.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    detail?.let { translations ->
        ModalBottomSheet(onDismissRequest = vm::closeDetail) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val best = translations.firstOrNull { it.lang == appLang }
                    ?: translations.firstOrNull { it.lang == "en" } ?: translations.firstOrNull()
                Text(best?.name ?: "", style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                Text(best?.description.orEmpty().ifBlank { stringResource(R.string.no_description) })
            }
        }
    }
}
