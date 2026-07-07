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
        Plan::class, Workout::class, WorkoutExercise::class, SetTemplate::class,
        Session::class, SetLog::class, ExerciseNote::class, BodyMetric::class,
    ],
    version = 2,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "workout.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
