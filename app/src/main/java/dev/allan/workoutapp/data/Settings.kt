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

    fun heightCm(context: Context): Flow<Double?> =
        context.dataStore.data.map { it[HEIGHT_CM] }

    suspend fun setHeightCm(context: Context, value: Double?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(HEIGHT_CM) else prefs[HEIGHT_CM] = value
        }
    }
}
