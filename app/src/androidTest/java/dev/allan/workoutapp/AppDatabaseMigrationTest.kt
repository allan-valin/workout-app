package dev.allan.workoutapp

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.allan.workoutapp.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the 9 -> 10 -> 11 upgrade keeps a real user's rows, instead of installing the old
 * APK and walking the UI by hand (which is how the 02/08 batch was checked, and which cost a
 * whole session). Seeds a v9 database with the rows the migrations touch, runs the shipped
 * migrations, and asserts the data survived and the new schema is there.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates9To11KeepingData() {
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL(
                "INSERT INTO exercise_note (id, exerciseId, sessionId, text, updatedAt) " +
                    "VALUES (1, 'wger:73', NULL, 'PreMigrationNote', 1000)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 11, true, *AppDatabase.ALL_MIGRATIONS)

        db.query("SELECT text, pinned FROM exercise_note WHERE id = 1").use { c ->
            assertTrue("the note written on v9 must survive the upgrade", c.moveToFirst())
            assertEquals("PreMigrationNote", c.getString(0))
            assertEquals("an existing note defaults to unpinned", 0, c.getInt(1))
        }

        // v10's table must exist and be usable, not merely declared.
        db.execSQL(
            "INSERT INTO session_suggestion_state (sessionId, workoutExerciseId, handled) VALUES (1, 1, 1)"
        )
        db.query("SELECT handled FROM session_suggestion_state WHERE sessionId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    /** Every intermediate step must also validate — a broken v1 install is still an install. */
    @Test
    fun migratesFromTheOldestSchema() {
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 11, true, *AppDatabase.ALL_MIGRATIONS)
    }
}
