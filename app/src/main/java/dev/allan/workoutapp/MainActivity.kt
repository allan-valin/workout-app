package dev.allan.workoutapp

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.allan.workoutapp.data.db.Plan
import dev.allan.workoutapp.ui.library.ExerciseLibraryScreen
import dev.allan.workoutapp.ui.plans.PlanEditorScreen
import dev.allan.workoutapp.ui.plans.PlansViewModel
import dev.allan.workoutapp.ui.plans.SplitWizard
import dev.allan.workoutapp.ui.plans.WorkoutEditorScreen
import dev.allan.workoutapp.ui.session.SessionScreen
import dev.allan.workoutapp.ui.session.SummaryScreen
import dev.allan.workoutapp.ui.workout.WorkoutViewScreen
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun WorkoutTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Current app language, normalized to the three supported codes. */
fun currentAppLang(): String {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        .ifEmpty { Locale.getDefault().toLanguageTag() }
    return when {
        tag.startsWith("pt") -> "pt"
        tag.startsWith("de") -> "de"
        else -> "en"
    }
}

private fun cycleLanguage() {
    val next = when (currentAppLang()) {
        "en" -> "pt-BR"
        "pt" -> "de"
        else -> "en"
    }
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
}

private fun flagFor(lang: String): String = when (lang) {
    "pt" -> "🇧🇷"
    "de" -> "🇩🇪"
    else -> "🇬🇧"
}

private enum class Tab(val labelRes: Int) {
    Home(R.string.nav_home),
    Active(R.string.nav_active_plans),
    Inactive(R.string.nav_inactive_plans),
    Stats(R.string.nav_statistics),
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScaffold(
                onOpenLibrary = { navController.navigate("library") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenPlan = { navController.navigate("plan/$it") },
                onOpenWorkout = { navController.navigate("view/$it") },
                onResumeSession = { navController.navigate("session/$it") },
            )
        }
        composable(
            "view/{workoutId}",
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
        ) { entry ->
            val workoutId = entry.arguments!!.getLong("workoutId")
            WorkoutViewScreen(
                workoutId = workoutId,
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate("workout/$workoutId") },
                onStart = { navController.navigate("session/$workoutId") },
            )
        }
        composable(
            "session/{workoutId}",
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
        ) { entry ->
            val workoutId = entry.arguments!!.getLong("workoutId")
            SessionScreen(
                workoutId = workoutId,
                appLang = currentAppLang(),
                onExit = { navController.popBackStack() },
                onFinished = { sessionId ->
                    navController.navigate("summary/$sessionId") {
                        popUpTo("main")
                    }
                },
            )
        }
        composable(
            "summary/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            SummaryScreen(
                sessionId = entry.arguments!!.getLong("sessionId"),
                appLang = currentAppLang(),
                onClose = { navController.popBackStack("main", inclusive = false) },
                onBackToWorkout = { workoutId ->
                    navController.navigate("view/$workoutId") {
                        popUpTo("main")
                    }
                },
            )
        }
        composable("library") {
            ExerciseLibraryScreen(
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            dev.allan.workoutapp.ui.settings.SettingsScreen(
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            "plan/{planId}",
            arguments = listOf(navArgument("planId") { type = NavType.LongType }),
        ) { entry ->
            PlanEditorScreen(
                planId = entry.arguments!!.getLong("planId"),
                onBack = { navController.popBackStack() },
                onOpenWorkout = { navController.navigate("workout/$it") },
            )
        }
        composable(
            "workout/{workoutId}",
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
        ) { entry ->
            val workoutId = entry.arguments!!.getLong("workoutId")
            WorkoutEditorScreen(
                workoutId = workoutId,
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                onPickExercise = { navController.navigate("picker/$workoutId") },
            )
        }
        composable(
            "picker/{workoutId}",
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
        ) { entry ->
            ExerciseLibraryScreen(
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                pickerWorkoutId = entry.arguments!!.getLong("workoutId"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onResumeSession: (Long) -> Unit,
    vm: PlansViewModel = viewModel(),
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showNewPlan by remember { mutableStateOf(false) }

    val activePlans by vm.activePlans.collectAsState()
    val inactivePlans by vm.inactivePlans.collectAsState()
    val todayWorkouts by vm.todayWorkouts.collectAsState()
    val runningSession by vm.runningSession.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { cycleLanguage() }) {
                        Text(flagFor(currentAppLang()), style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (Tab.entries[selected] == Tab.Active) {
                FloatingActionButton(onClick = { showNewPlan = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_plan))
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            val image = when (tab) {
                                Tab.Home -> Icons.Default.Home
                                Tab.Active -> Icons.Default.FitnessCenter
                                Tab.Inactive -> Icons.Outlined.Inventory2
                                Tab.Stats -> Icons.Default.BarChart
                            }
                            Icon(image, contentDescription = null)
                        },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (Tab.entries[selected]) {
                Tab.Home -> {
                    runningSession?.let { session ->
                        item {
                            Card(
                                onClick = { onResumeSession(session.workoutId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.resume_workout),
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    item { Text(stringResource(R.string.today), style = MaterialTheme.typography.titleLarge) }
                    if (todayWorkouts.isEmpty()) {
                        item { Text(stringResource(R.string.no_workouts_today)) }
                    }
                    items(todayWorkouts, key = { it.id }) { w ->
                        Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(w.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    item {
                        Button(onClick = onOpenLibrary) {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                            Text(stringResource(R.string.exercise_library), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Tab.Active -> {
                    if (activePlans.isEmpty()) {
                        item { Text(stringResource(R.string.no_active_plans), Modifier.padding(top = 16.dp)) }
                    }
                    items(activePlans, key = { it.id }) { plan ->
                        PlanCard(plan, onOpen = { onOpenPlan(plan.id) }, onToggle = { vm.setPlanActive(plan, false) })
                    }
                }
                Tab.Inactive -> {
                    if (inactivePlans.isEmpty()) {
                        item { Text(stringResource(R.string.no_inactive_plans), Modifier.padding(top = 16.dp)) }
                    }
                    items(inactivePlans, key = { it.id }) { plan ->
                        PlanCard(plan, onOpen = { onOpenPlan(plan.id) }, onToggle = { vm.setPlanActive(plan, true) })
                    }
                }
                Tab.Stats -> item { dev.allan.workoutapp.ui.stats.StatsTab() }
            }
        }
    }

    if (showNewPlan) {
        NewPlanDialog(
            onDismiss = { showNewPlan = false },
            onCreateBlank = { name, weeks ->
                vm.createBlankPlan(name, weeks) { onOpenPlan(it) }
                showNewPlan = false
            },
            onCreateWizard = { name, weeks, days ->
                vm.createWizardPlan(name, weeks, days) { onOpenPlan(it) }
                showNewPlan = false
            },
        )
    }
}

@Composable
private fun PlanCard(plan: Plan, onOpen: () -> Unit, onToggle: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                plan.cycleWeeks?.let {
                    Text(stringResource(R.string.cycle_weeks) + ": $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = plan.isActive, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun NewPlanDialog(
    onDismiss: () -> Unit,
    onCreateBlank: (String, Int?) -> Unit,
    onCreateWizard: (String, Int?, List<Pair<String, Int>>) -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
