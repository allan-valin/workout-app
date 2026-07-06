package dev.allan.workoutapp.ui.plans

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.allan.workoutapp.R
import dev.allan.workoutapp.WorkoutApp
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.data.db.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class PlanEditorViewModel(app: Application, private val planId: Long) : AndroidViewModel(app) {
    private val db = (app as WorkoutApp).db

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan

    val workouts: StateFlow<List<Workout>> = db.planDao().workouts(planId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshPlan()
    }

    private fun refreshPlan() {
        viewModelScope.launch { _plan.value = db.planDao().plan(planId) }
    }

    fun rename(name: String) = update { it.copy(name = name) }

    fun setCycleWeeks(weeks: Int?) = update { it.copy(cycleWeeks = weeks) }

    fun setActive(active: Boolean) = update { it.copy(isActive = active) }

    private fun update(transform: (Plan) -> Plan) {
        val current = _plan.value ?: return
        val next = transform(current)
        _plan.value = next
        viewModelScope.launch { db.planDao().updatePlan(next) }
    }

    fun addWorkout(name: String) {
        viewModelScope.launch {
            db.planDao().insertWorkout(
                Workout(planId = planId, name = name, orderIndex = workouts.value.size, daysOfWeek = emptyList())
            )
        }
    }

    fun deleteWorkout(w: Workout) {
        viewModelScope.launch { db.planDao().deleteWorkout(w.id) }
    }

    fun toggleDay(w: Workout, isoDay: Int) {
        val days = if (isoDay in w.daysOfWeek) w.daysOfWeek - isoDay else (w.daysOfWeek + isoDay).sorted()
        viewModelScope.launch { db.planDao().updateWorkout(w.copy(daysOfWeek = days)) }
    }

    /** 1-based current week within the cycle, or null when no cycle is set. */
    fun currentWeek(): Int? {
        val p = _plan.value ?: return null
        val start = p.startedAt ?: return null
        if (p.cycleWeeks == null) return null
        return ((System.currentTimeMillis() - start) / (7L * 24 * 3600 * 1000)).toInt() + 1
    }

    class Factory(private val app: Application, private val planId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlanEditorViewModel(app, planId) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(
    planId: Long,
    onBack: () -> Unit,
    onOpenWorkout: (Long) -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val vm: PlanEditorViewModel =
        viewModel(key = "plan-$planId", factory = PlanEditorViewModel.Factory(app, planId))
    val plan by vm.plan.collectAsState()
    val workouts by vm.workouts.collectAsState()
    var showAddWorkout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(plan?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        val p = plan ?: return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = p.name,
                    onValueChange = vm::rename,
                    label = { Text(stringResource(R.string.plan_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = p.cycleWeeks?.toString() ?: "",
                        onValueChange = { vm.setCycleWeeks(it.toIntOrNull()?.coerceIn(1, 52)) },
                        label = { Text(stringResource(R.string.cycle_weeks)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Text(stringResource(R.string.plan_active))
                    Switch(checked = p.isActive, onCheckedChange = vm::setActive)
                }
            }
            val week = vm.currentWeek()
            if (week != null && p.cycleWeeks != null) {
                item {
                    val deload = week >= p.cycleWeeks
                    Card {
                        Text(
                            text = if (deload)
                                stringResource(R.string.deload_banner, week, p.cycleWeeks)
                            else
                                stringResource(R.string.cycle_week_banner, week, p.cycleWeeks),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(workouts, key = { it.id }) { w ->
                Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteWorkout(w) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(
                                    selected = day.value in w.daysOfWeek,
                                    onClick = { vm.toggleDay(w, day.value) },
                                    label = { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = { showAddWorkout = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_workout), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (showAddWorkout) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddWorkout = false },
            title = { Text(stringResource(R.string.add_workout)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workout_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) vm.addWorkout(name.trim())
                        showAddWorkout = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddWorkout = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
