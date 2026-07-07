package dev.allan.workoutapp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.data.MuscleNames
import dev.allan.workoutapp.data.db.ExerciseHit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseLibraryScreen(
    appLang: String,
    onBack: () -> Unit,
    pickerWorkoutId: Long? = null,
    vm: ExerciseLibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val muscles by vm.muscles.collectAsState()
    val detail by vm.detail.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val addedMsg = stringResource(R.string.exercise_added)
    val onAdd: ((String) -> Unit)? = pickerWorkoutId?.let { wid ->
        { exerciseId ->
            vm.addToWorkout(wid, exerciseId) {
                android.widget.Toast.makeText(context, addedMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exercise_library)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.filters))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_exercises)) },
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showCustomDialog = true }) {
                    Text(stringResource(R.string.new_custom_exercise))
                }
                // Deferred search: explicit button, no per-keystroke queries.
                if (state.query.isNotBlank() || state.selectedMuscleId != null) {
                    TextButton(onClick = { vm.search(appLang) }) {
                        Text(stringResource(R.string.search_button))
                    }
                }
            }
            when {
                state.searching -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(24.dp)
                )
                state.searched && state.results.isEmpty() -> Text(
                    stringResource(R.string.no_results),
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.results, key = { it.id }) { hit ->
                        ExerciseRow(
                            hit = hit,
                            appLang = appLang,
                            altLine = state.altNames[hit.id],
                            muscleName = { id ->
                                muscles.firstOrNull { it.id == id }
                                    ?.let { MuscleNames.display(it.nameEn, appLang) } ?: ""
                            },
                            onClick = { vm.openDetail(hit) },
                            onAdd = onAdd,
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.muscle_group), fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.selectedMuscleId == null,
                        onClick = { vm.setMuscle(null) },
                        label = { Text(stringResource(R.string.all)) },
                    )
                    muscles.forEach { m ->
                        FilterChip(
                            selected = state.selectedMuscleId == m.id,
                            onClick = { vm.setMuscle(m.id) },
                            label = { Text(MuscleNames.display(m.nameEn, appLang)) },
                        )
                    }
                }
                Text(stringResource(R.string.search_language), fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchLang.entries.forEach { lang ->
                        FilterChip(
                            selected = state.searchLang == lang,
                            onClick = { vm.setSearchLang(lang) },
                            label = {
                                Text(
                                    when (lang) {
                                        SearchLang.APP -> stringResource(R.string.lang_app_default)
                                        SearchLang.EN -> "English"
                                        SearchLang.PT -> "Português (BR)"
                                        SearchLang.DE -> "Deutsch"
                                        SearchLang.ALL -> stringResource(R.string.all)
                                    }
                                )
                            },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.show_alt_names))
                    Switch(checked = state.showAltNames, onCheckedChange = vm::setShowAltNames)
                }
                val injured by vm.injuredMuscles.collectAsState()
                if (injured.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.hide_injured))
                        Switch(checked = state.excludeInjured, onCheckedChange = vm::setExcludeInjured)
                    }
                }
            }
        }
    }

    if (showCustomDialog) {
        CustomExerciseDialog(
            muscles = muscles,
            appLang = appLang,
            onDismiss = { showCustomDialog = false },
            onCreate = { name, desc, muscleId, cardio ->
                vm.createCustomExercise(name, desc, muscleId, cardio) { id ->
                    onAdd?.invoke(id)
                }
                showCustomDialog = false
            },
        )
    }

    detail?.let { d ->
        ModalBottomSheet(onDismissRequest = vm::closeDetail) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.hit.name, style = MaterialTheme.typography.headlineSmall)
                d.hit.category?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                val primary = d.hit.primaryMuscles.mapNotNull { id ->
                    muscles.firstOrNull { it.id == id }?.let { MuscleNames.display(it.nameEn, appLang) }
                }
                val secondary = d.hit.secondaryMuscles.mapNotNull { id ->
                    muscles.firstOrNull { it.id == id }?.let { MuscleNames.display(it.nameEn, appLang) }
                }
                if (primary.isNotEmpty()) {
                    Text("${stringResource(R.string.primary_muscles)}: ${primary.joinToString()}")
                }
                if (secondary.isNotEmpty()) {
                    Text("${stringResource(R.string.secondary_muscles)}: ${secondary.joinToString()}")
                }
                HorizontalDivider()
                val description = (d.translations.firstOrNull { it.lang == appLang }
                    ?: d.translations.firstOrNull { it.lang == "en" })
                    ?.description.orEmpty()
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
                val allNames = d.translations.flatMap { tr ->
                    (listOf(tr.name) + tr.aliases).map { "$it (${tr.lang})" }
                }.distinct()
                Text(
                    "${stringResource(R.string.also_known_as)}: ${allNames.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.wger_attribution),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomExerciseDialog(
    muscles: List<dev.allan.workoutapp.data.db.Muscle>,
    appLang: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, muscleId: Int?, isCardio: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var muscleId by remember { mutableStateOf<Int?>(null) }
    var cardio by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_custom_exercise)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.exercise_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    minLines = 2,
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cardio))
                    Switch(checked = cardio, onCheckedChange = { cardio = it })
                }
                Text(stringResource(R.string.muscle_group), fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    muscles.forEach { m ->
                        FilterChip(
                            selected = muscleId == m.id,
                            onClick = { muscleId = if (muscleId == m.id) null else m.id },
                            label = { Text(MuscleNames.display(m.nameEn, appLang)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), description.trim(), muscleId, cardio) },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ExerciseRow(
    hit: ExerciseHit,
    appLang: String,
    altLine: String?,
    muscleName: (Int) -> String,
    onClick: () -> Unit,
    onAdd: ((String) -> Unit)? = null,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hit.lang != appLang) {
                    Text("(${hit.lang})", style = MaterialTheme.typography.labelSmall)
                }
                if (onAdd != null) {
                    IconButton(onClick = { onAdd(hit.id) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.add_exercise),
                        )
                    }
                }
            }
            val tags = hit.primaryMuscles.map(muscleName).filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
            if (!altLine.isNullOrBlank()) {
                Text(altLine, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
