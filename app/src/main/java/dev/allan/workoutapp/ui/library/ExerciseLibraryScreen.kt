package dev.allan.workoutapp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
    /** Swap mode: each result substitutes the selected exercise instead of adding. */
    onSwapPick: ((String) -> Unit)? = null,
    vm: ExerciseLibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val muscles by vm.muscles.collectAsState()
    val detail by vm.detail.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showCustomsSheet by remember { mutableStateOf(false) }
    var selectedCustoms by remember { mutableStateOf(setOf<String>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val addedMsg = stringResource(R.string.exercise_added)
    // Swap mode wins: tapping a result substitutes; otherwise picker mode adds to a workout.
    val onAdd: ((String) -> Unit)? = onSwapPick ?: pickerWorkoutId?.let { wid ->
        { exerciseId ->
            vm.addToWorkout(wid, exerciseId) {
                android.widget.Toast.makeText(context, addedMsg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val swapMode = onSwapPick != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (swapMode) R.string.swap_exercise else R.string.exercise_library)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Search action (the filter panel already opens itself on focus, so the
                    // old filter icon was dead weight — Allan).
                    IconButton(onClick = { showFilters = false; vm.search(appLang) }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_button))
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
                modifier = Modifier
                    .fillMaxWidth()
                    // Filters drop down from the search bar the moment it's focused, and
                    // never block typing or the Search button (Allan: the old bottom sheet
                    // made search and filters fight each other).
                    .onFocusChanged { if (it.isFocused) showFilters = true },
                label = { Text(stringResource(R.string.search_exercises)) },
                singleLine = true,
            )
            androidx.compose.animation.AnimatedVisibility(visible = showFilters) {
                FilterPanel(vm = vm, state = state, muscles = muscles, appLang = appLang)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Real buttons, not clickable text (Allan).
                androidx.compose.material3.OutlinedButton(onClick = { showCustomsSheet = true }) {
                    Text(stringResource(R.string.custom_exercises))
                }
                // Deferred search: explicit button, no per-keystroke queries.
                if (state.query.isNotBlank() || state.selectedMuscleId != null) {
                    Button(onClick = { showFilters = false; vm.search(appLang) }) {
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
                else -> {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    // Images collapsed by default (no pop-in while results load); the
                    // chevron reveals them per row.
                    var expandedImages by remember { mutableStateOf(setOf<String>()) }
                    androidx.compose.foundation.layout.Box {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    swapMode = swapMode,
                                    isFavorite = hit.id in favoriteIds,
                                    onToggleFavorite = { vm.toggleFavorite(hit.id) },
                                    showImage = hit.id in expandedImages,
                                    onToggleImage = {
                                        expandedImages =
                                            if (hit.id in expandedImages) expandedImages - hit.id
                                            else expandedImages + hit.id
                                    },
                                )
                            }
                        }
                        dev.allan.workoutapp.ui.common.LazyScrollbar(
                            listState,
                            Modifier.align(Alignment.TopEnd),
                            edgePadding = 12.dp,
                        )
                    }
                }
            }
        }
    }


    if (showCustomsSheet) {
        val customs by vm.customs.collectAsState()
        val customsMessage by vm.customsMessage.collectAsState()
        var confirmDeleteCustom by remember { mutableStateOf<ExerciseHit?>(null) }
        val inUseTemplate = stringResource(R.string.custom_in_use)
        ModalBottomSheet(onDismissRequest = { showCustomsSheet = false; selectedCustoms = emptySet() }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.custom_exercises), style = MaterialTheme.typography.headlineSmall)
                if (customs.isEmpty()) Text(stringResource(R.string.no_custom_exercises))
                customs.forEach { hit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onAdd != null) {
                            androidx.compose.material3.Checkbox(
                                checked = hit.id in selectedCustoms,
                                onCheckedChange = {
                                    selectedCustoms =
                                        if (hit.id in selectedCustoms) selectedCustoms - hit.id
                                        else selectedCustoms + hit.id
                                },
                            )
                        }
                        Text(hit.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.openDetail(hit) }) {
                            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.description))
                        }
                        IconButton(onClick = { confirmDeleteCustom = hit }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { showCustomDialog = true }) {
                        Text(stringResource(R.string.new_custom_exercise))
                    }
                    if (onAdd != null && selectedCustoms.isNotEmpty()) {
                        Button(onClick = {
                            selectedCustoms.forEach { onAdd(it) }
                            selectedCustoms = emptySet()
                            showCustomsSheet = false
                        }) { Text(stringResource(R.string.add_selected, selectedCustoms.size)) }
                    }
                }
            }
        }
        confirmDeleteCustom?.let { hit ->
            AlertDialog(
                onDismissRequest = { confirmDeleteCustom = null },
                title = { Text(stringResource(R.string.confirm_delete_custom, hit.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteCustom(hit) { uses -> inUseTemplate.format(uses) }
                        confirmDeleteCustom = null
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteCustom = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        customsMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = vm::clearCustomsMessage,
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = vm::clearCustomsMessage) { Text(stringResource(R.string.ok)) }
                },
            )
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
        val description = (d.translations.firstOrNull { it.lang == appLang }
            ?: d.translations.firstOrNull { it.lang == "en" })
            ?.description.orEmpty()
        // Same shared detail sheet as the editor/session — editable link + watch/open.
        dev.allan.workoutapp.ui.common.ExerciseInfoSheet(
            name = d.hit.name,
            description = description,
            videoUrl = d.videoUrl,
            onSaveLink = { url -> vm.saveVideoLink(d.hit.id, url) },
            onDismiss = vm::closeDetail,
            note = d.note,
            onSaveNote = { txt -> vm.saveNote(d.hit.id, txt) },
        ) {
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
            HorizontalDivider()
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

/**
 * Inline filter panel dropping down from the search bar (replaces the old bottom sheet so
 * typing, filtering and the Search button all work together).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    vm: ExerciseLibraryViewModel,
    state: LibraryUiState,
    muscles: List<dev.allan.workoutapp.data.db.Muscle>,
    appLang: String,
) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun ExerciseRow(
    hit: ExerciseHit,
    appLang: String,
    altLine: String?,
    muscleName: (Int) -> String,
    onClick: () -> Unit,
    onAdd: ((String) -> Unit)? = null,
    swapMode: Boolean = false,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    showImage: Boolean = false,
    onToggleImage: (() -> Unit)? = null,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Illustration hidden until the chevron opens it (downloaded file first,
            // wger URL fallback when the media pipeline hasn't run yet).
            val model = hit.imagePath ?: hit.imageUrl
            if (showImage && model != null) {
                coil.compose.AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(bottom = 6.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onToggleImage != null && (hit.imagePath != null || hit.imageUrl != null)) {
                    IconButton(onClick = onToggleImage) {
                        Icon(
                            if (showImage) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hit.lang != appLang) {
                    Text("(${hit.lang})", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.favorite),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onAdd != null) {
                    IconButton(onClick = { onAdd(hit.id) }) {
                        Icon(
                            if (swapMode) Icons.Default.SwapVert else Icons.Default.Add,
                            contentDescription = stringResource(
                                if (swapMode) R.string.swap_exercise else R.string.add_exercise
                            ),
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
