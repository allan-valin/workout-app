package dev.allan.workoutapp

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedButton
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
import androidx.navigation.compose.currentBackStackEntryAsState
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
import dev.allan.workoutapp.ui.settings.PlanImportDialogs
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

// Material shared-axis X (motion spec): 300ms total, ~30dp slide (≈ width/13), outgoing
// fades out in 90ms, incoming fades in over 210ms after that. Used by the NavHost and the
// bottom-nav tab switch so every screen change feels the same.
private const val NAV_MS = 300
private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210

internal fun sharedAxisEnter(forward: Boolean): androidx.compose.animation.EnterTransition =
    androidx.compose.animation.fadeIn(
        androidx.compose.animation.core.tween(FADE_IN_MS, delayMillis = FADE_OUT_MS)
    ) + androidx.compose.animation.slideInHorizontally(
        androidx.compose.animation.core.tween(NAV_MS)
    ) { full -> (if (forward) 1 else -1) * (full / 13) }

internal fun sharedAxisExit(forward: Boolean): androidx.compose.animation.ExitTransition =
    androidx.compose.animation.fadeOut(
        androidx.compose.animation.core.tween(FADE_OUT_MS)
    ) + androidx.compose.animation.slideOutHorizontally(
        androidx.compose.animation.core.tween(NAV_MS)
    ) { full -> (if (forward) -1 else 1) * (full / 13) }

/** Localized short date (dd.MM.yy / dd/MM/yyyy / …) per the device language pattern. */
internal fun formatDateShort(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT))

private enum class Tab(val labelRes: Int) {
    Home(R.string.nav_home),
    Active(R.string.nav_active_plans),
    Inactive(R.string.nav_inactive_plans),
    Stats(R.string.nav_statistics),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    // Selected bottom-nav tab is hoisted so the global bar (below) and MainScaffold share it.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Global bottom nav on every screen EXCEPT an in-progress workout (session).
    val showBottomBar = currentRoute?.startsWith("session/") != true

    Scaffold(
        // Only the bottom bar contributes padding; each screen owns its own top insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(selected = selectedTab) { idx ->
                    val proceed = {
                        selectedTab = idx
                        // From a drill-in screen, a tab tap returns to the tabbed surface.
                        if (currentRoute != "main") {
                            navController.navigate("main") {
                                popUpTo("main") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    // A screen with unsaved edits (workout editor) intercepts the tap the
                    // same way it intercepts system back — keep/discard first, then leave.
                    dev.allan.workoutapp.ui.common.NavExitGuard.handler?.invoke(proceed) ?: proceed()
                }
            }
        },
    ) { barPadding ->
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.padding(barPadding),
            // Material "shared axis X": both screens slide only ~30dp while the outgoing one
            // fades out fast (90ms) and the incoming one fades in after it (210ms, delayed).
            // Two opaque screens are never on screen at once — kills the overlap/clunky feel
            // the full-width slide had (Allan 2026-07-11, matches other apps' seamless feel).
            enterTransition = { sharedAxisEnter(forward = true) },
            exitTransition = { sharedAxisExit(forward = true) },
            popEnterTransition = { sharedAxisEnter(forward = false) },
            popExitTransition = { sharedAxisExit(forward = false) },
        ) {
        composable("main") {
            MainScaffold(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onOpenLibrary = { navController.navigate("library") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenPlan = { navController.navigate("plan/$it") },
                onOpenWorkout = { navController.navigate("view/$it") },
                onResumeSession = { navController.navigate("session/$it") },
                onOpenBodyweight = { navController.navigate("bodyweight") },
                onOpenProgression = { navController.navigate("progression") },
                onOpenArchivePlans = { navController.navigate("archivePlans") },
                onOpenArchiveWorkouts = { navController.navigate("archiveWorkouts") },
            )
        }
        composable(
            "addWorkout/{planId}/{mode}",
            arguments = listOf(
                navArgument("planId") { type = NavType.LongType },
                navArgument("mode") { type = NavType.StringType },
            ),
        ) { entry ->
            val mode = if (entry.arguments!!.getString("mode") == "import")
                dev.allan.workoutapp.ui.plans.AddWorkoutMode.IMPORT
            else dev.allan.workoutapp.ui.plans.AddWorkoutMode.BASE
            dev.allan.workoutapp.ui.plans.AddWorkoutScreen(
                planId = entry.arguments!!.getLong("planId"),
                mode = mode,
                onBack = { navController.popBackStack() },
            )
        }
        // Archive add-target variant of the add-workout screen (no plan involved).
        composable(
            "addWorkoutArchive/{mode}",
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = if (entry.arguments!!.getString("mode") == "import")
                dev.allan.workoutapp.ui.plans.AddWorkoutMode.IMPORT
            else dev.allan.workoutapp.ui.plans.AddWorkoutMode.BASE
            dev.allan.workoutapp.ui.plans.AddWorkoutScreen(
                planId = null,
                mode = mode,
                onBack = { navController.popBackStack() },
            )
        }
        composable("archivePlans") {
            dev.allan.workoutapp.ui.plans.ArchivePlansScreen(
                onBack = { navController.popBackStack() },
                onOpenPlan = { navController.navigate("plan/$it") },
                onOpenWorkout = { navController.navigate("view/$it") },
            )
        }
        composable("archiveWorkouts") {
            dev.allan.workoutapp.ui.plans.ArchiveWorkoutsScreen(
                onBack = { navController.popBackStack() },
                onOpenWorkout = { navController.navigate("view/$it") },
                onEditWorkout = { navController.navigate("workout/$it") },
                onAddToArchive = { mode -> navController.navigate("addWorkoutArchive/$mode") },
                onOpenPlan = { navController.navigate("plan/$it") },
            )
        }
        composable("bodyweight") {
            dev.allan.workoutapp.ui.stats.BodyweightScreen(onBack = { navController.popBackStack() })
        }
        composable("progression") {
            dev.allan.workoutapp.ui.stats.ProgressionScreen(onBack = { navController.popBackStack() })
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
                onSummary = { sessionId ->
                    navController.navigate("summary/$sessionId") { popUpTo("main") }
                },
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
            val planId = entry.arguments!!.getLong("planId")
            PlanEditorScreen(
                planId = planId,
                onBack = { navController.popBackStack() },
                onOpenWorkout = { navController.navigate("workout/$it") },
                onAddWorkout = { mode -> navController.navigate("addWorkout/$planId/$mode") },
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
            val swapResult by entry.savedStateHandle
                .getStateFlow<String?>("swap_result", null)
                .collectAsState()
            WorkoutEditorScreen(
                workoutId = workoutId,
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                onPickExercise = { navController.navigate("picker/$workoutId") },
                onSwapExercise = { weId -> navController.navigate("swap/$workoutId/$weId") },
                swapResult = swapResult,
                onSwapConsumed = { entry.savedStateHandle["swap_result"] = null },
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
        composable(
            "swap/{workoutId}/{weId}",
            arguments = listOf(
                navArgument("workoutId") { type = NavType.LongType },
                navArgument("weId") { type = NavType.LongType },
            ),
        ) { entry ->
            val weId = entry.arguments!!.getLong("weId")
            ExerciseLibraryScreen(
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
                onSwapPick = { exerciseId ->
                    // Hand the choice back to the editor, then close the library.
                    navController.previousBackStackEntry?.savedStateHandle?.set("swap_result", "$weId:$exerciseId")
                    navController.popBackStack()
                },
            )
        }
        }
    }
}

/** The 4-tab bottom navigation, shared by the tabbed surface and every drill-in screen. */
@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        Tab.entries.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = {
                    val image = when (tab) {
                        Tab.Home -> Icons.Default.Home
                        Tab.Active -> Icons.Default.FitnessCenter
                        Tab.Inactive -> Icons.Outlined.Inventory2
                        Tab.Stats -> Icons.Default.BarChart
                    }
                    Icon(image, contentDescription = null)
                },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onOpenWorkout: (Long) -> Unit,
    onResumeSession: (Long) -> Unit,
    onOpenBodyweight: () -> Unit,
    onOpenProgression: () -> Unit,
    onOpenArchivePlans: () -> Unit,
    onOpenArchiveWorkouts: () -> Unit,
    vm: PlansViewModel = viewModel(),
) {
    val selected = selectedTab
    var showNewPlan by remember { mutableStateOf(false) }
    var showReactivate by remember { mutableStateOf(false) }

    val activePlan by vm.activePlan.collectAsState()
    val activePlanWorkouts by vm.activePlanWorkouts.collectAsState()
    val activePlanMuscleLoad by vm.activePlanMuscleLoad.collectAsState()
    val lastTrained by vm.lastTrained.collectAsState()
    val todayWorkouts by vm.todayWorkouts.collectAsState()
    val runningSession by vm.runningSession.collectAsState()
    val completedWeekDays by vm.completedWeekDays.collectAsState()
    val exerciseCounts by vm.exerciseCounts.collectAsState()

    val context = LocalContext.current
    val themeMode by dev.allan.workoutapp.data.Settings.themeMode(context)
        .collectAsState(initial = "system")
    val themeScope = androidx.compose.runtime.rememberCoroutineScope()

    // Import from the new-cycle dialog reuses the whole Settings import pipeline
    // (parse, plan/workout detection, collision dialogs).
    val settingsVm: dev.allan.workoutapp.ui.settings.SettingsViewModel = viewModel()
    val importPlanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { settingsVm.importPlan(it, currentAppLang()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Tab.entries[selected].labelRes)) },
                actions = {
                    // Theme/language/settings live on the Start tab only.
                    if (Tab.entries[selected] != Tab.Home) return@TopAppBar
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
            // Add/import a cycle only when there's no active cycle (Active tab shows the active
            // cycle's workouts otherwise — a second cycle would just deactivate this one).
            if (Tab.entries[selected] == Tab.Active && activePlan == null) {
                FloatingActionButton(onClick = { showNewPlan = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_plan))
                }
            }
        },
    ) { padding ->
        // Tab switches are state changes (not NavHost destinations), so they need their own
        // transition — the same shared-axis X as the NavHost; direction follows tab order.
        androidx.compose.animation.AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val forward = targetState > initialState
                sharedAxisEnter(forward) togetherWith sharedAxisExit(forward)
            },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "tabContent",
        ) { tabIndex ->
        val tabListState = androidx.compose.foundation.lazy.rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = tabListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (Tab.entries[tabIndex]) {
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
                    if (activePlan == null) {
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
                }
                Tab.Active -> {
                    val plan = activePlan
                    if (plan == null) {
                        item {
                            Text(
                                stringResource(R.string.no_active_plan_hint),
                                Modifier.padding(top = 16.dp),
                            )
                        }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    plan.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onOpenPlan(plan.id) }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_training))
                                }
                            }
                        }
                        if (activePlanWorkouts.isEmpty()) {
                            item { Text(stringResource(R.string.no_workouts_yet)) }
                        }
                        items(activePlanWorkouts, key = { it.id }) { w ->
                            Card(onClick = { onOpenWorkout(w.id) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        dev.allan.workoutapp.ui.plans.ExerciseCountBadge(exerciseCounts[w.id] ?: 0)
                                        Text(w.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { onOpenWorkout(w.id) }) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = stringResource(R.string.start_workout),
                                            )
                                        }
                                    }
                                    lastTrained[w.id]?.let { millis ->
                                        Text(
                                            stringResource(R.string.last_trained, formatDateShort(millis)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                }
                            }
                        }
                        // Add-workout lives in the plan editor + Archive now (not the plain
                        // Active view) — tap the pencil to edit the cycle and add there.
                        // EXPERIMENTAL muscle map for the whole cycle (all workouts unioned).
                        if (activePlanMuscleLoad.isNotEmpty()) {
                            item {
                                dev.allan.workoutapp.ui.common.BodyMap(
                                    load = activePlanMuscleLoad,
                                    title = stringResource(R.string.muscles_worked_cycle),
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    item { LibraryButton(onOpenLibrary) }
                }
                Tab.Inactive -> {
                    item {
                        dev.allan.workoutapp.ui.plans.ArchiveHubContent(
                            onOpenPlans = onOpenArchivePlans,
                            onOpenWorkouts = onOpenArchiveWorkouts,
                        )
                    }
                    item { LibraryButton(onOpenLibrary) }
                }
                Tab.Stats -> item {
                    dev.allan.workoutapp.ui.stats.StatsTab(
                        onOpenBodyweight = onOpenBodyweight,
                        onOpenProgression = onOpenProgression,
                    )
                }
            }
        }
        dev.allan.workoutapp.ui.common.LazyScrollbar(
            tabListState,
            Modifier.align(Alignment.TopEnd),
        )
        }
        }
    }

    if (showNewPlan) {
        val inactivePlans by vm.inactivePlans.collectAsState()
        dev.allan.workoutapp.ui.plans.NewPlanDialog(
            onDismiss = { showNewPlan = false },
            onCreateBlank = { name, weeks ->
                vm.createBlankPlan(name, weeks) { onOpenPlan(it) }
                showNewPlan = false
            },
            onCreateWizard = { name, weeks, days ->
                vm.createWizardPlan(name, weeks, days) { onOpenPlan(it) }
                showNewPlan = false
            },
            onImport = {
                showNewPlan = false
                importPlanLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            onReactivate = if (inactivePlans.isEmpty()) null else {
                { showNewPlan = false; showReactivate = true }
            },
        )
    }
    if (showReactivate) {
        val inactivePlans by vm.inactivePlans.collectAsState()
        dev.allan.workoutapp.ui.plans.ReactivateCycleDialog(
            plans = inactivePlans,
            onPick = { vm.setPlanActive(it, true) },
            onDismiss = { showReactivate = false },
        )
    }
    PlanImportDialogs(settingsVm)
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

/** Entry to the exercise library, shown at the bottom of the Active/Archive tabs. */
@Composable
private fun LibraryButton(onOpenLibrary: () -> Unit) {
    OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
        Text(stringResource(R.string.exercise_library), modifier = Modifier.padding(start = 8.dp))
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

