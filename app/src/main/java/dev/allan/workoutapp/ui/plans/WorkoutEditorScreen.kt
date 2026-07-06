package dev.allan.workoutapp.ui.plans

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.db.SetTemplate
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import dev.allan.workoutapp.data.db.Workout
import dev.allan.workoutapp.data.db.WorkoutExercise
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditorExercise(
    val we: WorkoutExercise,
    val name: String,
    val sets: List<SetTemplate>,
)

class WorkoutEditorViewModel(app: Application, private val workoutId: Long, private val lang: String) :
    AndroidViewModel(app) {

    private val db = (app as WorkoutApp).db

    private val _workout = MutableStateFlow<Workout?>(null)
    val workout: StateFlow<Workout?> = _workout

    val exercises: StateFlow<List<EditorExercise>> =
        combine(
            db.planDao().workoutExercises(workoutId),
            db.planDao().setTemplatesForWorkout(workoutId),
        ) { wes, sets -> wes to sets }
            .map { (wes, sets) ->
                wes.map { we ->
                    EditorExercise(
                        we = we,
                        name = PlanRepo.displayName(db, we.exerciseId, lang),
                        sets = sets.filter { it.workoutExerciseId == we.id }.sortedBy { it.setIndex },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { _workout.value = db.planDao().workout(workoutId) }
    }

    fun renameWorkout(name: String) {
        val w = _workout.value ?: return
        _workout.value = w.copy(name = name)
        viewModelScope.launch { db.planDao().updateWorkout(w.copy(name = name)) }
    }

    fun removeExercise(item: EditorExercise) {
        viewModelScope.launch { db.planDao().deleteWorkoutExercise(item.we.id) }
    }

    fun move(item: EditorExercise, up: Boolean) {
        val list = exercises.value
        val index = list.indexOfFirst { it.we.id == item.we.id }
        val other = if (up) index - 1 else index + 1
        if (index < 0 || other < 0 || other >= list.size) return
        viewModelScope.launch {
            db.planDao().updateWorkoutExercise(item.we.copy(orderIndex = list[other].we.orderIndex))
            db.planDao().updateWorkoutExercise(list[other].we.copy(orderIndex = item.we.orderIndex))
        }
    }

    fun setWeightMode(item: EditorExercise, mode: WeightMode) {
        viewModelScope.launch { db.planDao().updateWorkoutExercise(item.we.copy(weightMode = mode)) }
    }

    fun setBarWeight(item: EditorExercise, kg: Double) {
        viewModelScope.launch { db.planDao().updateWorkoutExercise(item.we.copy(barWeightKg = kg)) }
    }

    fun updateSet(set: SetTemplate) {
        viewModelScope.launch { db.planDao().updateSetTemplate(set) }
    }

    fun addSet(item: EditorExercise) {
        val last = item.sets.lastOrNull()
        viewModelScope.launch {
            db.planDao().insertSetTemplate(
                (last ?: SetTemplate(workoutExerciseId = item.we.id, setIndex = -1))
                    .copy(id = 0, setIndex = (last?.setIndex ?: -1) + 1)
            )
        }
    }

    fun removeSet(set: SetTemplate) {
        viewModelScope.launch { db.planDao().deleteSetTemplate(set.id) }
    }

    fun applyRestToAll(item: EditorExercise, restSecs: Int) {
        viewModelScope.launch {
            item.sets.forEach { db.planDao().updateSetTemplate(it.copy(restSecs = restSecs)) }
        }
    }

    class Factory(private val app: Application, private val workoutId: Long, private val lang: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkoutEditorViewModel(app, workoutId, lang) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorScreen(
    workoutId: Long,
    appLang: String,
    onBack: () -> Unit,
    onPickExercise: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: WorkoutEditorViewModel = viewModel(
        key = "workout-$workoutId",
        factory = WorkoutEditorViewModel.Factory(app, workoutId, appLang),
    )
    val workout by vm.workout.collectAsState()
    val exercises by vm.exercises.collectAsState()

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
                    IconButton(onClick = onPickExercise) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_exercise))
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (exercises.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.empty_workout_hint))
                        Button(onClick = onPickExercise) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.add_exercise), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            items(exercises, key = { it.we.id }) { item ->
                ExerciseEditorCard(item, vm)
            }
        }
    }
}

@Composable
private fun ExerciseEditorCard(item: EditorExercise, vm: WorkoutEditorViewModel) {
    var showBulkRest by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.move(item, up = true) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(onClick = { vm.move(item, up = false) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
                IconButton(onClick = { vm.removeExercise(item) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                }
            }

            // Weight interpretation: total / per dumbbell / per side of the bar.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WeightMode.entries.forEach { mode ->
                    FilterChip(
                        selected = item.we.weightMode == mode,
                        onClick = { vm.setWeightMode(item, mode) },
                        label = {
                            Text(
                                when (mode) {
                                    WeightMode.TOTAL -> stringResource(R.string.weight_total)
                                    WeightMode.PER_DUMBBELL -> stringResource(R.string.weight_per_dumbbell)
                                    WeightMode.PER_SIDE -> stringResource(R.string.weight_per_side)
                                }
                            )
                        },
                    )
                }
            }
            if (item.we.weightMode == WeightMode.PER_SIDE) {
                NumberField(
                    value = item.we.barWeightKg,
                    label = stringResource(R.string.bar_weight),
                    onCommit = { vm.setBarWeight(item, it) },
                    modifier = Modifier.width(140.dp),
                )
            }

            item.sets.forEach { set ->
                SetRow(set, onUpdate = vm::updateSet, onDelete = { vm.removeSet(set) })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.addSet(item) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_set))
                }
                TextButton(onClick = { showBulkRest = true }) {
                    Icon(Icons.Default.Timer, contentDescription = null)
                    Text(stringResource(R.string.rest_apply_all))
                }
            }
        }
    }

    if (showBulkRest) {
        var text by remember { mutableStateOf(item.sets.firstOrNull()?.restSecs?.toString() ?: "90") }
        AlertDialog(
            onDismissRequest = { showBulkRest = false },
            title = { Text(stringResource(R.string.rest_apply_all)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rest_secs)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        text.toIntOrNull()?.let { vm.applyRestToAll(item, it.coerceIn(0, 3600)) }
                        showBulkRest = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkRest = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SetRow(set: SetTemplate, onUpdate: (SetTemplate) -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        EnumDropdown(
            current = set.type.label(),
            options = SetType.entries.map { it to it.label() },
            onSelect = { onUpdate(set.copy(type = it)) },
        )
        NumberField(
            value = set.targetWeightKg,
            label = stringResource(R.string.kg),
            onCommit = { onUpdate(set.copy(targetWeightKg = it)) },
            modifier = Modifier.weight(1f),
        )
        IntField(
            value = set.targetValue,
            label = if (set.valueUnit == ValueUnit.REPS) stringResource(R.string.reps) else stringResource(R.string.secs),
            onCommit = { onUpdate(set.copy(targetValue = it)) },
            modifier = Modifier.weight(1f),
        )
        EnumDropdown(
            current = if (set.valueUnit == ValueUnit.REPS) stringResource(R.string.reps) else stringResource(R.string.secs),
            options = listOf(
                ValueUnit.REPS to stringResource(R.string.reps),
                ValueUnit.SECS to stringResource(R.string.secs),
            ),
            onSelect = { onUpdate(set.copy(valueUnit = it)) },
        )
        IntField(
            value = set.restSecs,
            label = stringResource(R.string.rest_short),
            onCommit = { onUpdate(set.copy(restSecs = it)) },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
        }
    }
}

@Composable
private fun SetType.label(): String = when (this) {
    SetType.WARMUP -> stringResource(R.string.set_warmup)
    SetType.NORMAL -> stringResource(R.string.set_normal)
    SetType.FAILURE -> stringResource(R.string.set_failure)
    SetType.DROP -> stringResource(R.string.set_drop)
    SetType.SUPERSET -> stringResource(R.string.set_superset)
}

@Composable
private fun <T> EnumDropdown(current: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text(current) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun NumberField(value: Double, label: String, onCommit: (Double) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(if (value == 0.0) "0" else value.toString().removeSuffix(".0")) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.replace(',', '.').toDoubleOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun IntField(value: Int, label: String, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
