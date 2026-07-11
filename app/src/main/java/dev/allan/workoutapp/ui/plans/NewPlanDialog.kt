package dev.allan.workoutapp.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.allan.workoutapp.R
import dev.allan.workoutapp.data.db.Plan

/**
 * New-cycle overlay — shared by the Active tab FAB and Archive → Cycles FAB. Creates a blank
 * or wizard-split cycle, imports a JSON file, or reactivates an archived cycle ([onReactivate],
 * hidden when the caller has none to offer).
 */
@Composable
fun NewPlanDialog(
    onDismiss: () -> Unit,
    onCreateBlank: (String, Int?) -> Unit,
    onCreateWizard: (String, Int?, List<Pair<String, Int>>) -> Unit,
    onImport: () -> Unit,
    onReactivate: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("") }
    var cycleWeeksText by remember { mutableStateOf("") }
    var useWizard by remember { mutableStateOf(true) }
    var daysPerWeek by remember { mutableFloatStateOf(3f) }

    val suggestion = SplitWizard.generate(daysPerWeek.toInt())
    val resolvedDays = suggestion.map { stringResource(it.labelRes) + it.suffix to it.isoDay }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_plan)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plan_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cycleWeeksText,
                    onValueChange = { cycleWeeksText = it },
                    label = { Text(stringResource(R.string.cycle_weeks_optional)) },
                    singleLine = true,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.suggest_split))
                    Switch(checked = useWizard, onCheckedChange = { useWizard = it })
                }
                if (useWizard) {
                    Text(stringResource(R.string.days_per_week, daysPerWeek.toInt()))
                    Slider(
                        value = daysPerWeek,
                        onValueChange = { daysPerWeek = it },
                        valueRange = 1f..7f,
                        steps = 5,
                    )
                    Text(
                        resolvedDays.joinToString { it.first },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Same import as Settings — second entry point so a file can seed the cycle.
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_plan))
                }
                // Repeat an old cycle instead of building a new one.
                if (onReactivate != null) {
                    OutlinedButton(onClick = onReactivate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.reactivate_cycle))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val weeks = cycleWeeksText.toIntOrNull()?.coerceIn(1, 52)
                    if (useWizard) onCreateWizard(name.trim(), weeks, resolvedDays)
                    else onCreateBlank(name.trim(), weeks)
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Picker behind "reactivate an archived cycle": tap one to make it THE active cycle. */
@Composable
fun ReactivateCycleDialog(
    plans: List<Plan>,
    onPick: (Plan) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reactivate_cycle)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                plans.forEach { plan ->
                    Card(
                        onClick = { onPick(plan); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            plan.name,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.titleSmall,
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
