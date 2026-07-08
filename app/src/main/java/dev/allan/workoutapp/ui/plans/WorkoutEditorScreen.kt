package dev.allan.workoutapp.ui.plans

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.PlanRepo
import dev.allan.workoutapp.data.SuggestionFocus
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
import kotlinx.coroutines.flow.first
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

    // One name lookup per exercise, not one per Flow emission (every set edit re-emits).
    private val nameCache = mutableMapOf<String, String>()

    private val _suggesting = MutableStateFlow(false)
    val suggesting: StateFlow<Boolean> = _suggesting

    val exercises: StateFlow<List<EditorExercise>> =
        combine(
            db.planDao().workoutExercises(workoutId),
            db.planDao().setTemplatesForWorkout(workoutId),
        ) { wes, sets -> wes to sets }
            .map { (wes, sets) ->
                wes.map { we ->
                    EditorExercise(
                        we = we,
                        name = nameCache.getOrPut(we.exerciseId) {
                            PlanRepo.displayName(db, we.exerciseId, lang)
                        },
                        sets = sets.filter { it.workoutExerciseId == we.id }.sortedBy { it.setIndex },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { _workout.value = db.planDao().workout(workoutId) }
    }

    /** (name, description) for the ℹ dialog — localized with en fallback. */
    private val _description = MutableStateFlow<Pair<String, String>?>(null)
    val description: StateFlow<Pair<String, String>?> = _description

    fun openDescription(item: EditorExercise) {
        viewModelScope.launch {
            val translations = db.exerciseDao().translations(item.we.exerciseId)
            val best = translations.firstOrNull { it.lang == lang }
                ?: translations.firstOrNull { it.lang == "en" } ?: translations.firstOrNull()
            _description.value = item.name to best?.description.orEmpty()
        }
    }

    fun closeDescription() {
        _description.value = null
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

    fun toggleSuperset(item: EditorExercise) {
        viewModelScope.launch {
            db.planDao().updateWorkoutExercise(item.we.copy(supersetWithPrev = !item.we.supersetWithPrev))
        }
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

    fun suggestExercises(focus: SuggestionFocus, total: Int?) {
        if (_suggesting.value) return
        viewModelScope.launch {
            _suggesting.value = true
            try {
                val injured = dev.allan.workoutapp.data.Settings
                    .injuredMuscles(getApplication()).first()
                dev.allan.workoutapp.data.SuggestionEngine
                    .fillWorkout(db, workoutId, focus, injured, total)
            } finally {
                _suggesting.value = false
            }
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
    /** Non-null: edit only this workout-exercise (quick edit from a running session). */
    focusExerciseId: Long? = null,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: WorkoutEditorViewModel = viewModel(
        key = "workout-$workoutId",
        factory = WorkoutEditorViewModel.Factory(app, workoutId, appLang),
    )
    val workout by vm.workout.collectAsState()
    val exercises by vm.exercises.collectAsState()
    val suggesting by vm.suggesting.collectAsState()
    var showSuggestDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Multi-select for bulk exercise deletion: one confirmation instead of one per trash tap.
    var selectedExercises by remember { mutableStateOf(setOf<Long>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }

    if (showSuggestDialog) {
        SuggestFocusDialog(
            onDismiss = { showSuggestDialog = false },
            onPick = { focus, total ->
                showSuggestDialog = false
                vm.suggestExercises(focus, total)
            },
        )
    }

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
                    if (selectedExercises.isNotEmpty()) {
                        IconButton(onClick = { selectedExercises = exercises.map { it.we.id }.toSet() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { selectedExercises = emptySet() }) {
                            Icon(Icons.Default.Deselect, contentDescription = stringResource(R.string.unselect_all))
                        }
                        IconButton(onClick = { confirmBulkDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                        return@TopAppBar
                    }
                    if (suggesting) {
                        CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { showSuggestDialog = true }) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.suggest_exercises),
                            )
                        }
                    }
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
            val shownExercises = focusExerciseId
                ?.let { f -> exercises.filter { it.we.id == f } } ?: exercises
            items(shownExercises, key = { it.we.id }) { item ->
                // animateItem keeps the viewport still and slides the card to its new
                // slot, so reordering reads as movement instead of a scroll jump.
                Box(Modifier.animateItem()) {
                    ExerciseEditorCard(
                        item = item,
                        index = exercises.indexOfFirst { it.we.id == item.we.id },
                        vm = vm,
                        onMove = { up ->
                            focusManager.clearFocus() // a focused field would drag the scroll along
                            vm.move(item, up)
                        },
                        selected = item.we.id in selectedExercises,
                        onToggleSelect = {
                            selectedExercises =
                                if (item.we.id in selectedExercises) selectedExercises - item.we.id
                                else selectedExercises + item.we.id
                        },
                    )
                }
            }
        }
    }

    val description by vm.description.collectAsState()
    description?.let { (name, desc) ->
        AlertDialog(
            onDismissRequest = vm::closeDescription,
            title = { Text(name) },
            text = {
                Text(
                    desc.ifBlank { stringResource(R.string.no_description) },
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::closeDescription) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    if (confirmBulkDelete) {
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text(stringResource(R.string.confirm_delete_selected, selectedExercises.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulkDelete = false
                    exercises.filter { it.we.id in selectedExercises }.forEach(vm::removeExercise)
                    selectedExercises = emptySet()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SuggestFocusDialog(
    onDismiss: () -> Unit,
    onPick: (SuggestionFocus, Int?) -> Unit,
) {
    var countText by remember { mutableStateOf("4") }
    val count = countText.toIntOrNull()?.coerceAtLeast(1) ?: 4
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggest_exercises)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(stringResource(R.string.suggest_count), style = MaterialTheme.typography.labelLarge)
                // Explicit editable count (min 1) — the old "default" chip was opaque and
                // the fixed chip row clipped in pt-BR.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { countText = (count - 1).coerceAtLeast(1).toString() }) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { new -> countText = new.filter { it.isDigit() }.take(2) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.width(72.dp),
                    )
                    IconButton(onClick = { countText = (count + 1).toString() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
                Text(
                    stringResource(R.string.suggest_focus),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                SuggestionFocus.entries.forEach { focus ->
                    TextButton(
                        onClick = { onPick(focus, count.takeIf { it > 0 }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                when (focus) {
                                    SuggestionFocus.FULL_BODY -> R.string.split_full_body
                                    SuggestionFocus.PUSH -> R.string.split_push
                                    SuggestionFocus.PULL -> R.string.split_pull
                                    SuggestionFocus.LEGS -> R.string.split_legs
                                    SuggestionFocus.UPPER -> R.string.split_upper
                                    SuggestionFocus.LOWER -> R.string.split_lower
                                    SuggestionFocus.CHEST -> R.string.split_chest
                                    SuggestionFocus.BACK -> R.string.split_back
                                    SuggestionFocus.SHOULDERS -> R.string.split_shoulders
                                    SuggestionFocus.ARMS -> R.string.split_arms
                                    SuggestionFocus.CARDIO_CORE -> R.string.split_cardio_core
                                }
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ExerciseEditorCard(
    item: EditorExercise,
    index: Int,
    vm: WorkoutEditorViewModel,
    onMove: (Boolean) -> Unit,
    selected: Boolean,
    onToggleSelect: () -> Unit,
) {
    var showBulkRest by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox feeds the top-bar multi-select delete (replaces per-card trash).
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.openDescription(item) }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.description))
                }
                IconButton(onClick = { onMove(true) }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(onClick = { onMove(false) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
            }

            // Superset pairing: alternate with the previous exercise, rest after the pair.
            if (index > 0 || item.we.supersetWithPrev) {
                FilterChip(
                    selected = item.we.supersetWithPrev,
                    onClick = { vm.toggleSuperset(item) },
                    label = { Text(stringResource(R.string.superset_with_prev)) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                )
            }

            // Weight interpretation: total / per dumbbell / per side of the bar.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                WeightMode.entries.forEach { mode ->
                    FilterChip(
                        selected = item.we.weightMode == mode,
                        onClick = { vm.setWeightMode(item, mode) },
                        label = { Text(weightModeLabel(mode)) },
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

            SetHeaderRow(item.we.weightMode)
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
private fun weightModeLabel(mode: WeightMode): String = when (mode) {
    WeightMode.TOTAL -> stringResource(R.string.weight_total)
    WeightMode.PER_DUMBBELL -> stringResource(R.string.weight_per_dumbbell)
    WeightMode.PER_SIDE -> stringResource(R.string.weight_per_side)
}

/** Column captions so it's obvious what each field means; weight carries the mode tag. */
@Composable
private fun SetHeaderRow(weightMode: WeightMode) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HeaderText(stringResource(R.string.header_type), Modifier.width(36.dp))
        HeaderText(
            stringResource(R.string.kg) + " · " + weightModeLabel(weightMode).lowercase(),
            Modifier.weight(1f),
        )
        HeaderText(stringResource(R.string.header_target), Modifier.weight(1.6f))
        Box(Modifier.width(44.dp))
        HeaderText(stringResource(R.string.rest_short), Modifier.weight(0.8f))
        Box(Modifier.width(28.dp))
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun SetRow(set: SetTemplate, onUpdate: (SetTemplate) -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Set type: initial letter only; tinted = tappable/changeable.
        TintedDropdown(
            current = set.type.label().take(1),
            options = SetType.entries
                .filter { it != SetType.SUPERSET } // superset is an exercise-level link now
                .map { it to it.label() },
            onSelect = { onUpdate(set.copy(type = it)) },
            modifier = Modifier.width(36.dp),
        )
        NumberField(
            value = set.targetWeightKg,
            label = stringResource(R.string.kg),
            onCommit = { onUpdate(set.copy(targetWeightKg = it)) },
            modifier = Modifier.weight(1f),
        )
        // Target: rep range in one field ("10" or "10-12"); single duration for timed sets.
        if (set.valueUnit == ValueUnit.REPS) {
            RangeField(
                min = set.targetValue,
                max = set.targetValueMax,
                onCommit = { min, max ->
                    onUpdate(set.copy(targetValue = min, targetValueMax = max?.takeIf { it > min }))
                },
                modifier = Modifier.weight(1.6f),
            )
        } else {
            IntField(
                value = set.targetValue,
                label = "s",
                onCommit = { onUpdate(set.copy(targetValue = it)) },
                modifier = Modifier.weight(1.6f),
            )
        }
        // Unit toggle: "Rep"/"Sec" spelled out enough to read, tinted+bordered = changeable.
        TintedDropdown(
            current = (if (set.valueUnit == ValueUnit.REPS) stringResource(R.string.reps)
            else stringResource(R.string.secs)).take(3),
            options = listOf(
                ValueUnit.REPS to stringResource(R.string.reps),
                ValueUnit.SECS to stringResource(R.string.secs),
            ),
            onSelect = { onUpdate(set.copy(valueUnit = it)) },
            modifier = Modifier.width(52.dp),
        )
        IntField(
            value = set.restSecs,
            label = "s",
            onCommit = { onUpdate(set.copy(restSecs = it)) },
            modifier = Modifier.weight(0.8f),
        )
        // Single-set delete is cheap to redo — no confirmation dialog.
        IconButton(onClick = onDelete, modifier = Modifier.width(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Dropdown with a tinted pill background — the tint marks "this is changeable". */
@Composable
private fun <T> TintedDropdown(
    current: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                // Border marks "this is a control", not a static label.
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                current,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
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

/** Rep-range field: accepts "10" (fixed target) or "10-12" (range). */
@Composable
private fun RangeField(
    min: Int,
    max: Int?,
    onCommit: (Int, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(min, max) {
        mutableStateOf(if (max != null) "$min-$max" else "$min")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            Regex("""^(\d+)(?:\s*[-–]\s*(\d+))?$""").find(input.trim())?.let { m ->
                onCommit(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull())
            }
        },
        label = { Text("×") },
        singleLine = true,
        modifier = modifier,
    )
}
