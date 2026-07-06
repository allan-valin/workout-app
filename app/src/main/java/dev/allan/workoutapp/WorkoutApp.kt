package dev.allan.workoutapp

import android.app.Application
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.snapshot.SnapshotImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WorkoutApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            SnapshotImporter.importIfNeeded(this@WorkoutApp)
        }
    }

    val db: AppDatabase by lazy { AppDatabase.get(this) }
}
