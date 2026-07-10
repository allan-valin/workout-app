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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.sync.withLock

data class EditorExercise(
    val we: WorkoutExercise,
    val name: String,
    val sets: List<SetTemplate>,
)

/** Data for the ℹ detail sheet in the editor. */
data class ExerciseDescription(
    val exerciseId: String,
    val name: String,
    val description: String,
    val videoUrl: String?,
    val note: String,
)

/** Full editable content of a workout, for undo/redo/discard. */
data class EditorSnapshot(
    val name: String,
    val wes: List<WorkoutExercise>,
    val templates: List<SetTemplate>,
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
        viewModelScope.launch {
            _workout.value = db.planDao().workout(workoutId)
            initialSnapshot = readSnapshot()
            lastSnapshot = initialSnapshot
        }
    }

    // ---- Undo / redo / discard --------------------------------------------------------
    // The editor writes changes to the DB live. To support undo/redo and "discard on exit"
    // we snapshot the workout's full content (name + exercises + set templates) before every
    // change; restore rewrites those rows (ids preserved via REPLACE inserts).
    private val editMutex = kotlinx.coroutines.sync.Mutex()
    private var initialSnapshot: EditorSnapshot? = null
    // Last content this VM knows about; lets us detect edits made OUTSIDE the editor
    // (adding an exercise from the picker) and fold them into undo/dirty on resume.
    private var lastSnapshot: EditorSnapshot? = null
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty

    private suspend fun readSnapshot(): EditorSnapshot {
        val name = db.planDao().workout(workoutId)?.name ?: ""
        val wes = db.planDao().workoutExercisesList(workoutId)
        val templates = wes.flatMap { db.planDao().setTemplatesList(it.id) }
        return EditorSnapshot(name, wes, templates)
    }

    private suspend fun restore(s: EditorSnapshot) {
        db.planDao().deleteSetTemplatesForWorkout(workoutId)
        db.planDao().deleteWorkoutExercisesForWorkout(workoutId)
        db.planDao().restoreWorkoutExercises(s.wes)
        db.planDao().restoreSetTemplates(s.templates)
        db.planDao().workout(workoutId)?.let { db.planDao().updateWorkout(it.copy(name = s.name)) }
        _workout.value = db.planDao().workout(workoutId)
    }

    private fun refreshFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun recordChange(before: EditorSnapshot) {
        undoStack.addLast(before)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        _dirty.value = true
        refreshFlags()
    }

    /** Run a mutation, capturing the pre-change snapshot onto the undo stack first. */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            editMutex.withLock {
                val before = readSnapshot()
                block()
                recordChange(before)
                lastSnapshot = readSnapshot()
            }
        }
    }

    fun undo() {
        viewModelScope.launch {
            editMutex.withLock {
                val prev = undoStack.removeLastOrNull() ?: return@withLock
                redoStack.addLast(readSnapshot())
                restore(prev)
                lastSnapshot = prev
                _dirty.value = true
                refreshFlags()
            }
        }
    }

    fun redo() {
        viewModelScope.launch {
            editMutex.withLock {
                val next = redoStack.removeLastOrNull() ?: return@withLock
                undoStack.addLast(readSnapshot())
                restore(next)
                lastSnapshot = next
                _dirty.value = true
                refreshFlags()
            }
        }
    }

    /**
     * Called when the editor regains focus. If the workout changed while we were away
     * (an exercise added from the picker), fold that into the undo history and mark dirty
     * so leaving prompts to keep/discard even for an add-then-leave on an empty workout.
     */
    fun checkExternalChange() {
        viewModelScope.launch {
            editMutex.withLock {
                val ref = lastSnapshot ?: return@withLock
                val current = readSnapshot()
                if (current != ref) {
                    recordChange(ref)
                    lastSnapshot = current
                }
            }
        }
    }

    /** Exit path: revert everything to the state the editor opened with. */
    fun discardChanges(onDone: () -> Unit) {
        viewModelScope.launch {
            editMutex.withLock { initialSnapshot?.let { restore(it) } }
            onDone()
        }
    }

    /** Backing state for the ℹ detail sheet — localized description + saved video link. */
    private val _description = MutableStateFlow<ExerciseDescription?>(null)
    val description: StateFlow<ExerciseDescription?> = _description

    fun openDescription(item: EditorExercise) {
        viewModelScope.launch {
            val translations = db.exerciseDao().translations(item.we.exerciseId)
            val best = translations.firstOrNull { it.lang == lang }
                ?: translations.firstOrNull { it.lang == "en" } ?: translations.firstOrNull()
            _description.value = ExerciseDescription(
                exerciseId = item.we.exerciseId,
                name = item.name,
                description = best?.description.orEmpty(),
                videoUrl = db.exerciseDao().videoLink(item.we.exerciseId),
                note = db.sessionDao().noteText(item.we.exerciseId) ?: "",
            )
        }
    }

    fun saveNote(exerciseId: String, text: String) {
        _description.value = _description.value?.takeIf { it.exerciseId == exerciseId }?.copy(note = text)
            ?: _description.value
        viewModelScope.launch { PlanRepo.saveExerciseNote(db, exerciseId, text) }
    }

    /** Save (or clear when blank) the video link for the exercise the sheet is showing. */
    fun saveVideoLink(exerciseId: String, url: String) {
        val trimmed = url.trim()
        _description.value = _description.value?.takeIf { it.exerciseId == exerciseId }
            ?.copy(videoUrl = trimmed.ifBlank { null }) ?: _description.value
        viewModelScope.launch {
            if (trimmed.isBlank()) db.exerciseDao().deleteVideoLink(exerciseId)
            else db.exerciseDao().upsertVideoLink(
                dev.allan.workoutapp.data.db.ExerciseLink(exerciseId = exerciseId, url = trimmed)
            )
        }
    }

    fun closeDescription() {
        _description.value = null
    }

    fun renameWorkout(name: String) {
        val w = _workout.value ?: return
        _workout.value = w.copy(name = name)
        edit { db.planDao().updateWorkout(w.copy(name = name)) }
    }

    fun removeExercise(item: EditorExercise) {
        edit { db.planDao().deleteWorkoutExercise(item.we.id) }
    }

    fun move(item: EditorExercise, up: Boolean) {
        val list = exercises.value
        val index = list.indexOfFirst { it.we.id == item.we.id }
        val other = if (up) index - 1 else index + 1
        if (index < 0 || other < 0 || other >= list.size) return
        edit {
            db.planDao().updateWorkoutExercise(item.we.copy(orderIndex = list[other].we.orderIndex))
            db.planDao().updateWorkoutExercise(list[other].we.copy(orderIndex = item.we.orderIndex))
        }
    }

    fun setWeightMode(item: EditorExercise, mode: WeightMode) {
        edit { db.planDao().updateWorkoutExercise(item.we.copy(weightMode = mode)) }
    }

    fun setBarWeight(item: EditorExercise, kg: Double) {
        edit { db.planDao().updateWorkoutExercise(item.we.copy(barWeightKg = kg)) }
    }

    fun toggleSuperset(item: EditorExercise) {
        edit {
            db.planDao().updateWorkoutExercise(item.we.copy(supersetWithPrev = !item.we.supersetWithPrev))
        }
    }

    fun updateSet(set: SetTemplate) {
        edit { db.planDao().updateSetTemplate(set) }
    }

    fun addSet(item: EditorExercise) {
        edit {
            // Read the latest sets from the DB (not the possibly-stale composable snapshot) so
            // a weight/reps edit typed just before tapping "add set" is carried into the clone.
            val last = db.planDao().setTemplatesList(item.we.id).maxByOrNull { it.setIndex }
            db.planDao().insertSetTemplate(
                (last ?: SetTemplate(workoutExerciseId = item.we.id, setIndex = -1))
                    .copy(id = 0, setIndex = (last?.setIndex ?: -1) + 1)
            )
        }
    }

    fun removeSet(set: SetTemplate) {
        edit { db.planDao().deleteSetTemplate(set.id) }
    }

    /** Long-press drag-reorder of an exercise's sets: renumber setIndex to the new order. */
    fun moveSet(item: EditorExercise, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        edit {
            val sets = db.planDao().setTemplatesList(item.we.id).sortedBy { it.setIndex }.toMutableList()
            if (fromIndex !in sets.indices || toIndex !in sets.indices) return@edit
            sets.add(toIndex, sets.removeAt(fromIndex))
            sets.forEachIndexed { i, s -> if (s.setIndex != i) db.planDao().updateSetTemplate(s.copy(setIndex = i)) }
        }
    }

    fun applyRestToAll(item: EditorExercise, restSecs: Int) {
        edit {
            item.sets.forEach { db.planDao().updateSetTemplate(it.copy(restSecs = restSecs)) }
        }
    }

    fun suggestExercises(focus: SuggestionFocus, total: Int?) {
        if (_suggesting.value) return
        viewModelScope.launch {
            _suggesting.value = true
            try {
                editMutex.withLock {
                    val before = readSnapshot()
                    val injured = dev.allan.workoutapp.data.Settings
                        .injuredMuscles(getApplication()).first()
                    dev.allan.workoutapp.data.SuggestionEngine
                        .fillWorkout(db, workoutId, focus, injured, total)
                    recordChange(before)
                }
            } finally {
                _suggesting.value = false
            }
        }
    }

    /** Wizard confirm: per-muscle counts, extra session-only injuries, full-body mode. */
    fun suggestByMuscles(counts: List<Pair<Int, Int>>, extraInjured: Set<Int>, compound: Boolean?) {
        if (_suggesting.value) return
        viewModelScope.launch {
            _suggesting.value = true
            try {
                editMutex.withLock {
                    val before = readSnapshot()
                    val injured = dev.allan.workoutapp.data.Settings
                        .injuredMuscles(getApplication()).first() + extraInjured
                    dev.allan.workoutapp.data.SuggestionEngine
                        .fillWorkoutByMuscles(db, workoutId, counts, injured, compound)
                    recordChange(before)
                }
            } finally {
                _suggesting.value = false
            }
        }
    }

    val muscles: StateFlow<List<dev.allan.workoutapp.data.db.Muscle>> = db.exerciseDao().muscles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val dirty by vm.dirty.collectAsState()
    var showSuggestDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Multi-select for bulk exercise deletion: one confirmation instead of one per trash tap.
    var selectedExercises by remember { mutableStateOf(setOf<Long>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // Exit guard: unsaved edits → keep/discard prompt (back arrow or system back).
    var confirmExit by remember { mutableStateOf(false) }
    val attemptBack = { if (dirty) confirmExit = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = true) { attemptBack() }

    // Detect exercises added from the picker (an out-of-editor mutation) on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.checkExternalChange()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSuggestDialog) {
        val muscles by vm.muscles.collectAsState()
        SuggestWizard(
            muscles = muscles,
            appLang = appLang,
            onDismiss = { showSuggestDialog = false },
            onConfirm = { counts, extraInjured, compound ->
                showSuggestDialog = false
                vm.suggestByMuscles(counts, extraInjured, compound)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
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
                    IconButton(onClick = { vm.undo() }, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                    }
                    IconButton(onClick = { vm.redo() }, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo))
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
                    // Save = persist-and-exit; the DB already holds the live edits, so this
                    // just leaves the screen (and drops the undo history with it).
                    TextButton(onClick = onBack) { Text(stringResource(R.string.save)) }
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
    description?.let { info ->
        // Same slide-up detail sheet as the library/session (was a popup before).
        dev.allan.workoutapp.ui.common.ExerciseInfoSheet(
            name = info.name,
            description = info.description,
            videoUrl = info.videoUrl,
            onSaveLink = { url -> vm.saveVideoLink(info.exerciseId, url) },
            onDismiss = vm::closeDescription,
            note = info.note,
            onSaveNote = { txt -> vm.saveNote(info.exerciseId, txt) },
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

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) {
                    Text(stringResource(R.string.keep_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; vm.discardChanges(onBack) }) {
                    Text(stringResource(R.string.discard_changes))
                }
            },
        )
    }
}

@Composable
private fun focusLabel(focus: SuggestionFocus): String = stringResource(
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

/** ~minutes per suggested exercise: 1 min setup + 3 sets × (40 s work + 60 s rest). */
private const val MINUTES_PER_EXERCISE = 6

/**
 * Three-step suggestion wizard: (1) multi-select foci + exercise count or desired
 * duration + optional injuries checkbox + full-body mode, (2) session-only injured
 * muscles, (3) per-muscle exercise counts derived from the merged recipes.
 * Going back keeps every selection.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SuggestWizard(
    muscles: List<dev.allan.workoutapp.data.db.Muscle>,
    appLang: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Pair<Int, Int>>, Set<Int>, Boolean?) -> Unit,
) {
    var step by remember { mutableIntStateOf(1) }
    var foci by remember { mutableStateOf(setOf<SuggestionFocus>()) }
    var countText by remember { mutableStateOf("4") }
    var injuriesChecked by remember { mutableStateOf(false) }
    var extraInjured by remember { mutableStateOf(setOf<Int>()) }
    var fullBodyCompound by remember { mutableStateOf(true) }
    // Step-3 state survives go-back; recomputed only when foci/count change.
    var muscleCounts by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    val count = countText.toIntOrNull()?.coerceAtLeast(1) ?: 4

    // Per-muscle count step only makes sense when the split spans several muscles:
    // 2+ foci, or full-body in isolation mode. A single focus or full-body-compound
    // needs no distribution — skip step 3 and let the button read "Confirm".
    val needsMuscleStep = foci.size >= 2 ||
        (SuggestionFocus.FULL_BODY in foci && !fullBodyCompound)
    // Build the auto-distribution once foci/count are known; reused whether or not
    // the user gets to tweak it in step 3.
    fun recomputeCounts() {
        // List EVERY muscle of the selected foci, even when the total is too small to give
        // each one a pick (scaledCounts drops 0-count muscles). 0-filled rows let the user
        // bump any muscle in step 3. (Allan: "legs+shoulders, 4 total showed only legs".)
        val recipe = dev.allan.workoutapp.data.SuggestionEngine.mergedRecipe(foci)
        val scaled = dev.allan.workoutapp.data.SuggestionEngine.scaledCounts(recipe, count).toMap()
        muscleCounts = recipe.map { (id, _) -> id to (scaled[id] ?: 0) }
    }
    fun finish() {
        if (muscleCounts.isEmpty()) recomputeCounts()
        onConfirm(
            muscleCounts.filter { it.second > 0 },
            if (injuriesChecked) extraInjured else emptySet(),
            if (SuggestionFocus.FULL_BODY in foci) fullBodyCompound else null,
        )
    }

    fun muscleLabel(id: Int): String =
        if (id == dev.allan.workoutapp.data.SuggestionEngine.CARDIO) "Cardio"
        else muscles.firstOrNull { it.id == id }
            ?.let { dev.allan.workoutapp.data.MuscleNames.display(it.nameEn, appLang) } ?: "#$id"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggest_exercises)) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                when (step) {
                    1 -> {
                        Text(stringResource(R.string.suggest_focus), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SuggestionFocus.entries.forEach { focus ->
                                FilterChip(
                                    selected = focus in foci,
                                    onClick = {
                                        foci = if (focus in foci) foci - focus else foci + focus
                                    },
                                    label = { Text(focusLabel(focus)) },
                                )
                            }
                        }
                        if (SuggestionFocus.FULL_BODY in foci) {
                            Text(
                                stringResource(R.string.full_body_mode),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = fullBodyCompound,
                                    onClick = { fullBodyCompound = true },
                                    label = { Text(stringResource(R.string.mode_compound)) },
                                )
                                FilterChip(
                                    selected = !fullBodyCompound,
                                    onClick = { fullBodyCompound = false },
                                    label = { Text(stringResource(R.string.mode_isolation)) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.suggest_count),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 10.dp),
                        )
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
                        // Editable duration that back-computes the count (6 min/exercise).
                        // Kept in sync with the count field above (either drives the other).
                        Text(
                            stringResource(R.string.suggest_duration),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedTextField(
                                value = (count * MINUTES_PER_EXERCISE).toString(),
                                onValueChange = { new ->
                                    val mins = new.filter { it.isDigit() }.take(3).toIntOrNull()
                                    if (mins != null) {
                                        countText = (Math.round(mins / MINUTES_PER_EXERCISE.toDouble())
                                            .toInt().coerceAtLeast(1)).toString()
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.width(88.dp),
                            )
                            Text(
                                stringResource(R.string.minutes_suffix),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Checkbox(
                                checked = injuriesChecked,
                                onCheckedChange = { injuriesChecked = it },
                            )
                            Text(stringResource(R.string.consider_injuries))
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.injured_muscles), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            muscles.forEach { m ->
                                FilterChip(
                                    selected = m.id in extraInjured,
                                    onClick = {
                                        extraInjured =
                                            if (m.id in extraInjured) extraInjured - m.id
                                            else extraInjured + m.id
                                    },
                                    label = { Text(muscleLabel(m.id)) },
                                )
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.per_muscle_counts), style = MaterialTheme.typography.labelLarge)
                        muscleCounts.forEachIndexed { i, (muscleId, c) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(muscleLabel(muscleId), modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    muscleCounts = muscleCounts.toMutableList()
                                        .also { it[i] = muscleId to (c - 1).coerceAtLeast(0) }
                                }) { Icon(Icons.Default.Remove, contentDescription = null) }
                                Text("$c")
                                IconButton(onClick = {
                                    muscleCounts = muscleCounts.toMutableList().also { it[i] = muscleId to c + 1 }
                                }) { Icon(Icons.Default.Add, contentDescription = null) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                1 -> {
                    // "Next" only if a further step (injuries or per-muscle) will show;
                    // otherwise the single-focus / full-body-compound case confirms here.
                    val hasNext = injuriesChecked || needsMuscleStep
                    TextButton(
                        enabled = foci.isNotEmpty(),
                        onClick = {
                            recomputeCounts()
                            when {
                                injuriesChecked -> step = 2
                                needsMuscleStep -> step = 3
                                else -> finish()
                            }
                        },
                    ) { Text(stringResource(if (hasNext) R.string.next else R.string.confirm)) }
                }
                2 -> TextButton(
                    onClick = { if (needsMuscleStep) step = 3 else finish() },
                ) { Text(stringResource(if (needsMuscleStep) R.string.next else R.string.confirm)) }
                else -> TextButton(
                    enabled = muscleCounts.any { it.second > 0 },
                    onClick = { finish() },
                ) { Text(stringResource(R.string.confirm)) }
            }
        },
        dismissButton = {
            when (step) {
                1 -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                2 -> TextButton(onClick = { step = 1 }) { Text(stringResource(R.string.go_back)) }
                else -> TextButton(onClick = { step = if (injuriesChecked) 2 else 1 }) {
                    Text(stringResource(R.string.go_back))
                }
            }
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
            // Long-press any set row to drag-reorder; neighbours slide out of the way.
            sh.calvin.reorderable.ReorderableColumn(
                list = item.sets,
                onSettle = { from, to -> vm.moveSet(item, from, to) },
            ) { _, set, isDragging ->
                key(set.id) {
                    val elevation by androidx.compose.animation.core.animateDpAsState(
                        if (isDragging) 6.dp else 0.dp, label = "setDrag",
                    )
                    androidx.compose.material3.Surface(
                        tonalElevation = elevation,
                        shadowElevation = elevation,
                        modifier = Modifier.longPressDraggableHandle(),
                    ) {
                        SetRow(set, onUpdate = vm::updateSet, onDelete = { vm.removeSet(set) })
                    }
                }
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
  Column(Modifier.fillMaxWidth()) {
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
    // Per-set cadence/tempo reminder (e.g. 4-0-2-0), below the set details.
    // Commit on every change (not just blur): tapping Save/back could dispose the field
    // before onFocusChanged fired, silently dropping the cadence.
    var tempo by remember(set.id, set.tempo) { mutableStateOf(set.tempo) }
    var showTempoInfo by remember { mutableStateOf(false) }
    // Justified cadence row: label left, centered value, info button right.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.tempo_label) + ":",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = tempo,
            onValueChange = {
                tempo = it.filter { c -> c.isDigit() || c == '-' }.take(11)
                onUpdate(set.copy(tempo = tempo.trim()))
            },
            placeholder = {
                Text("4-0-2-0", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        // Dedicated cadence legend — separate from the exercise info button.
        IconButton(onClick = { showTempoInfo = true }) {
            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.tempo_info_title))
        }
    }
    if (showTempoInfo) {
        AlertDialog(
            onDismissRequest = { showTempoInfo = false },
            title = { Text(stringResource(R.string.tempo_info_title)) },
            text = { Text(stringResource(R.string.tempo_info_body)) },
            confirmButton = {
                TextButton(onClick = { showTempoInfo = false }) { Text(stringResource(R.string.ok)) }
            },
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
