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
            autoEndStaleSession()
            dev.allan.workoutapp.data.MediaStore.sweep(this@WorkoutApp, db)
        }
    }

    /** Crash recovery: a RUNNING session older than 5 h is flagged ended, stats kept. */
    private suspend fun autoEndStaleSession() {
        val session = db.sessionDao().runningSession() ?: return
        val age = System.currentTimeMillis() - session.startedAt
        if (age > dev.allan.workoutapp.ui.session.SESSION_AUTO_END_MS) {
            db.sessionDao().updateSession(
                session.copy(
                    endedAt = session.startedAt + dev.allan.workoutapp.ui.session.SESSION_AUTO_END_MS,
                    status = dev.allan.workoutapp.data.db.SessionStatus.AUTO_ENDED,
                )
            )
        }
    }

    val db: AppDatabase by lazy { AppDatabase.get(this) }
}
