package dev.allan.workoutapp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.allan.workoutapp.data.db.AppDatabase
import dev.allan.workoutapp.data.db.SetLog
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.data.db.WeightMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W1 (Allan, 17/10): a number corrected on an already-logged row must change the LOG, not a
 * draft that dies with the session — otherwise the next session prefills the uncorrected value.
 */
@RunWith(AndroidJUnit4::class)
class SetLogEditTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun editingALoggedRowRewritesItsLog() = runBlocking {
        db.sessionDao().insertSetLog(
            SetLog(
                sessionId = 7, workoutExerciseId = 3, exerciseId = "wger:1", setIndex = 1,
                type = SetType.NORMAL, weightKg = 16.0, weightMode = WeightMode.TOTAL, barWeightKg = 0.0,
                value = 8, valueUnit = ValueUnit.REPS, activeSecs = 40, restSecs = 60, completedAt = 1000,
            )
        )
        db.sessionDao().updateSetLogNumbers(sessionId = 7, workoutExerciseId = 3, setIndex = 1, weightKg = 10.0, value = 9)
        val row = db.sessionDao().setLog(7, 3, 1)!!
        assertEquals(10.0, row.weightKg, 0.0)
        assertEquals(9, row.value)
        assertEquals(40, row.activeSecs) // only the numbers change; what the set cost stays
    }
}
