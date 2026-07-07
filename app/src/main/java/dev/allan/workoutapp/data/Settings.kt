package dev.allan.workoutapp.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

object Settings {
    private val HEIGHT_CM = doublePreferencesKey("height_cm")
    private val BATTERY_ONBOARDING_SHOWN =
        androidx.datastore.preferences.core.booleanPreferencesKey("battery_onboarding_shown")

    fun batteryOnboardingShown(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[BATTERY_ONBOARDING_SHOWN] ?: false }

    suspend fun setBatteryOnboardingShown(context: Context) {
        context.dataStore.edit { it[BATTERY_ONBOARDING_SHOWN] = true }
    }

    private val INJURED_MUSCLES =
        androidx.datastore.preferences.core.stringSetPreferencesKey("injured_muscles")

    fun injuredMuscles(context: Context): Flow<Set<Int>> =
        context.dataStore.data.map { prefs ->
            prefs[INJURED_MUSCLES]?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()
        }

    suspend fun setInjuredMuscles(context: Context, ids: Set<Int>) {
        context.dataStore.edit { it[INJURED_MUSCLES] = ids.map(Int::toString).toSet() }
    }

    private val PREV_NEXT_BUTTONS =
        androidx.datastore.preferences.core.booleanPreferencesKey("prev_next_buttons")

    fun prevNextButtons(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[PREV_NEXT_BUTTONS] ?: false }

    suspend fun setPrevNextButtons(context: Context, value: Boolean) {
        context.dataStore.edit { it[PREV_NEXT_BUTTONS] = value }
    }

    fun heightCm(context: Context): Flow<Double?> =
        context.dataStore.data.map { it[HEIGHT_CM] }

    suspend fun setHeightCm(context: Context, value: Double?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(HEIGHT_CM) else prefs[HEIGHT_CM] = value
        }
    }
}
