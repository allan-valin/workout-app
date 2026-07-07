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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
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
    val context = LocalContext.current
    val themeMode by dev.allan.workoutapp.data.Settings.themeMode(context)
        .collectAsState(initial = "system")
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Slightly larger type across the board — defaults were hard to read mid-workout.
    val base = androidx.compose.material3.Typography()
    val typography = base.copy(
        bodySmall = base.bodySmall.copy(fontSize = 13.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp),
    )
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
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
                onEditExercise = { weId -> navController.navigate("workout/$workoutId?focus=$weId") },
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
            "workout/{workoutId}?focus={focusId}",
            arguments = listOf(
                navArgument("workoutId") { type = NavType.LongType },
                navArgument("focusId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val workoutId = entry.arguments!!.getLong("workoutId")
            val focusId = entry.arguments!!.getLong("focusId").takeIf { it >= 0 }
            WorkoutEditorScreen(
                workoutId = workoutId,
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                onPickExercise = { navController.navigate("picker/$workoutId") },
                focusExerciseId = focusId,
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
    // Plan selection for deletion (checkboxes on Active/Inactive plan cards).
    var selectedPlans by remember { mutableStateOf(setOf<Long>()) }
    var confirmDeletePlans by remember { mutableStateOf(false) }

    val activePlans by vm.activePlans.collectAsState()
    val inactivePlans by vm.inactivePlans.collectAsState()
    val todayWorkouts by vm.todayWorkouts.collectAsState()
    val runningSession by vm.runningSession.collectAsState()
    val completedWeekDays by vm.completedWeekDays.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()
    val workoutCounts by vm.workoutCounts.collectAsState()

    val context = LocalContext.current
    val themeMode by dev.allan.workoutapp.data.Settings.themeMode(context)
        .collectAsState(initial = "system")
    val themeScope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (selectedPlans.isNotEmpty()) {
                        IconButton(onClick = { confirmDeletePlans = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                    val dark = when (themeMode) {
                        "dark" -> true
                        "light" -> false
                        else -> isSystemInDarkTheme()
                    }
                    IconButton(onClick = {
                        themeScope.launch {
                            dev.allan.workoutapp.data.Settings
                                .setThemeMode(context, if (dark) "light" else "dark")
                        }
                    }) {
                        Icon(
                            if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = stringResource(R.string.toggle_theme),
                        )
                    }
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
                    item { WeekRow(completedWeekDays) }
                    item { Text(stringResource(R.string.today), style = MaterialTheme.typography.titleLarge) }
                    if (todayWorkouts.isEmpty()) {
                        item { Text(stringResource(R.string.no_workouts_today)) }
                    }
                    if (activePlans.isEmpty()) {
                        // First-run state: nothing to train yet — offer plan creation right here.
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(stringResource(R.string.home_empty_hint))
                                    Button(onClick = { showNewPlan = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Text(
                                            stringResource(R.string.new_plan),
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(todayWorkouts, key = { it.id }) { w ->
                        Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                dev.allan.workoutapp.ui.plans.ExerciseCountBadge(exerciseCounts[w.id] ?: 0)
                                Text(w.name, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    item {
                        Button(onClick = onOpenLibrary) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            Text(stringResource(R.string.exercise_library), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Tab.Active -> {
                    if (activePlans.isEmpty()) {
                        item { Text(stringResource(R.string.no_active_plans), Modifier.padding(top = 16.dp)) }
                    }
                    items(activePlans, key = { it.id }) { plan ->
                        PlanCard(
                            plan,
                            workoutCount = workoutCounts[plan.id] ?: 0,
                            selected = plan.id in selectedPlans,
                            onToggleSelect = {
                                selectedPlans =
                                    if (plan.id in selectedPlans) selectedPlans - plan.id
                                    else selectedPlans + plan.id
                            },
                            onOpen = { onOpenPlan(plan.id) },
                            onToggle = { vm.setPlanActive(plan, false) },
                        )
                    }
                }
                Tab.Inactive -> {
                    if (inactivePlans.isEmpty()) {
                        item { Text(stringResource(R.string.no_inactive_plans), Modifier.padding(top = 16.dp)) }
                    }
                    items(inactivePlans, key = { it.id }) { plan ->
                        PlanCard(
                            plan,
                            workoutCount = workoutCounts[plan.id] ?: 0,
                            selected = plan.id in selectedPlans,
                            onToggleSelect = {
                                selectedPlans =
                                    if (plan.id in selectedPlans) selectedPlans - plan.id
                                    else selectedPlans + plan.id
                            },
                            onOpen = { onOpenPlan(plan.id) },
                            onToggle = { vm.setPlanActive(plan, true) },
                        )
                    }
                }
                Tab.Stats -> item { dev.allan.workoutapp.ui.stats.StatsTab() }
            }
        }
    }

    if (confirmDeletePlans) {
        AlertDialog(
            onDismissRequest = { confirmDeletePlans = false },
            title = { Text(stringResource(R.string.delete_plans_confirm, selectedPlans.size)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlans(selectedPlans)
                    selectedPlans = emptySet()
                    confirmDeletePlans = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePlans = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
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

/**
 * Mon–Sun circles above Today; a filled circle with a checkmark marks days of the
 * current week where a session was finished. Today's circle is outlined.
 */
@Composable
private fun WeekRow(completedDays: Set<Int>) {
    val today = java.time.LocalDate.now().dayOfWeek.value
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        java.time.DayOfWeek.entries.forEach { day ->
            val done = day.value in completedDays
            val isToday = day.value == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        )
                        .then(
                            if (isToday) Modifier.border(
                                2.dp, MaterialTheme.colorScheme.primary, CircleShape
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: Plan,
    workoutCount: Int,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                // Plans hold workouts — the count makes the plan/workout hierarchy visible.
                Text(
                    stringResource(R.string.plan_workout_count, workoutCount) +
                        (plan.cycleWeeks?.let { " · ${stringResource(R.string.cycle_weeks)}: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
