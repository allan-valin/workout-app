package dev.allan.workoutapp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    vm: ExerciseLibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val muscles by vm.muscles.collectAsState()
    val detail by vm.detail.collectAsState()
    var showFilters by remember { mutableStateOf(false) }

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
            // Deferred search: explicit button, no per-keystroke queries.
            if (state.query.isNotBlank() || state.selectedMuscleId != null) {
                TextButton(onClick = { vm.search(appLang) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.search_button))
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
            }
        }
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

@Composable
private fun ExerciseRow(
    hit: ExerciseHit,
    appLang: String,
    altLine: String?,
    muscleName: (Int) -> String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hit.lang != appLang) {
                    Text("(${hit.lang})", style = MaterialTheme.typography.labelSmall)
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
