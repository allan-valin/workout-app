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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.allan.workoutapp.ui.library.ExerciseLibraryScreen
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
    "pt" -> "🇧🇷" // BR
    "de" -> "🇩🇪" // DE
    else -> "🇬🇧" // GB
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
            MainScaffold(onOpenLibrary = { navController.navigate("library") })
        }
        composable("library") {
            ExerciseLibraryScreen(
                appLang = currentAppLang(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(onOpenLibrary: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { cycleLanguage() }) {
                        Text(flagFor(currentAppLang()), style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (Tab.entries[selected]) {
                Tab.Home -> {
                    Text(stringResource(R.string.no_workouts_today), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenLibrary) {
                        Icon(Icons.Default.MenuBook, contentDescription = null)
                        Text(stringResource(R.string.exercise_library), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                else -> Text(stringResource(R.string.placeholder_phase0))
            }
        }
    }
}
