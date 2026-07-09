package dev.allan.workoutapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Exercise::class, ExerciseTranslation::class, Muscle::class, Equipment::class,
        Plan::class, Workout::class, PlanWorkout::class, WorkoutExercise::class, SetTemplate::class,
        Session::class, SetLog::class, ExerciseNote::class, BodyMetric::class,
        SessionSetDraft::class, ExerciseLink::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun planDao(): PlanDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2: workout archiving, superset exercise links, target rep ranges. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workout_exercise ADD COLUMN supersetWithPrev INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE set_template ADD COLUMN targetValueMax INTEGER")
            }
        }

        /** v3: session weight drafts survive leaving the screen; user video links per exercise. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_set_draft (
                        sessionId INTEGER NOT NULL,
                        templateId INTEGER NOT NULL,
                        weightKg REAL NOT NULL,
                        value INTEGER NOT NULL,
                        PRIMARY KEY(sessionId, templateId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS exercise_link (
                        exerciseId TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v4: workout↔plan is now many-to-many (plan_workout join). The workout table
         * loses its owning planId/orderIndex; existing rows are backfilled into the join.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plan_workout (
                        planId INTEGER NOT NULL,
                        workoutId INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        PRIMARY KEY(planId, workoutId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plan_workout_planId ON plan_workout(planId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plan_workout_workoutId ON plan_workout(workoutId)")
                db.execSQL(
                    "INSERT INTO plan_workout (planId, workoutId, orderIndex) " +
                        "SELECT planId, id, orderIndex FROM workout"
                )
                // Rebuild workout without planId/orderIndex (SQLite can't drop columns pre-3.35).
                db.execSQL(
                    """
                    CREATE TABLE workout_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        daysOfWeek TEXT NOT NULL,
                        archived INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO workout_new (id, name, daysOfWeek, archived) " +
                        "SELECT id, name, daysOfWeek, archived FROM workout"
                )
                db.execSQL("DROP TABLE workout")
                db.execSQL("ALTER TABLE workout_new RENAME TO workout")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "workout.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
