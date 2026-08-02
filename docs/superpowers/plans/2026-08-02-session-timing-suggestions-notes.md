# Session timing, suggestions, summary & notes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the in-session state amnesia and the over-eager progression chip, make active-time accounting reflect what was actually timed, and add the pace note, the last-summary card, the readable rest notification, reps steppers and pinned notes.

**Architecture:** Suggestion state and applied values become persisted (new `session_suggestion` table + existing `session_set_draft`) so `startOrResume()` — which re-runs on every `ON_RESUME` and every in-session template edit — stops wiping them. Active-time rules move out of `SessionViewModel.logSet` into a pure `SetTiming` object plus explicit booking calls on `SessionManager`, so every rule is unit-testable. UI work stays inside the existing `SessionScreen` composables.

**Tech Stack:** Kotlin, Jetpack Compose, Room (DB v9 → v11), kotlinx-coroutines, JUnit4.

## Global Constraints

- Source spec: `docs/FEEDBACK_BATCH_2026-08-02.md`. Item ids (A1, B2, …) below refer to it.
- Build/verify: `./gradlew test assembleRelease` from the repo root. Both must be green before any commit.
- Emulator pass required at the end of every batch (AVD `testphone`, `hw.gpu.enabled=yes`; headless: `adb start-server` **before** launching, `-gpu swiftshader_indirect`). Install the **release** apk — the debug key mismatches the installed app.
- Real-device (Redmi) verification stays deferred until 1.0. Do not claim device verification.
- Commit **and push to `main`** at every task's final step. One commit per task, not one commit for the batch.
- Never regenerate or commit the keystore at `~/keys/workout-app.jks`.
- Every new user-visible string goes in all three locales: `app/src/main/res/values/strings.xml` (en), `values-pt-rBR/strings.xml`, `values-de/strings.xml`.
- Tappable things need a visible container/icon (outlined pill = changeable). Keep the existing pill + `(i)` pattern when mirroring a control.
- Room: one migration per schema change, appended to `AppDatabase.addMigrations(...)`. Never `fallbackToDestructiveMigration`.
- Update `docs/PROGRESS.md` and the "Still to verify" list in `docs/AUDIT_2026-07-25.md` as tasks land.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/allan/workoutapp/data/SetTiming.kt` | Pure timing math: tempo parsing, default/measured active seconds, pace verdict, superset share window. No Android deps. |
| `app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt` | Unit tests for the above. |
| `app/src/test/java/dev/allan/workoutapp/SuggestionPersistenceTest.kt` | Pure-state tests for apply/dismiss/manual-edit marking. |

**Modified**

| File | Change |
|---|---|
| `data/ProgressionEngine.kt` | New rule set (uniform-last-session, surplus reps, DROP_WEIGHT). |
| `data/db/Entities.kt` | `SessionSuggestionState` entity; `ExerciseNote.pinned`. |
| `data/db/Daos.kt` | Suggestion-state upsert/read; `lastFinishedSession()`; note pin write. |
| `data/db/AppDatabase.kt` | `MIGRATION_9_10` (suggestion state), `MIGRATION_10_11` (note pin), version 11. |
| `session/SessionManager.kt` | Countdown-run booking, per-template measured seconds, superset share window. |
| `session/TimerService.kt` | Per-second remaining text in the countdown notification. |
| `ui/session/SessionViewModel.kt` | Persisted apply/dismiss, reps reset on weight change, new active-time wiring, pace state, pinned note. |
| `ui/session/SessionScreen.kt` | Countdown-default panel, pace note, reps steppers + narrower weight button, pinned note line. |
| `ui/plans/PlansViewModel.kt` | `lastFinishedSession` flow for the Home card. |
| `MainActivity.kt` | Home card linking to the last summary. |
| `res/values*/strings.xml` | New strings. |

---

### Task 1: Progression rules (B2)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/data/ProgressionEngine.kt`
- Test: `app/src/test/java/dev/allan/workoutapp/ProgressionEngineTest.kt`

**Interfaces:**
- Consumes: `SetTemplate`, `SetLog` (unchanged entities).
- Produces: `ProgressionEngine.Kind { ADD_WEIGHT, ADD_REP, DROP_WEIGHT }`;
  `ProgressionEngine.Suggestion(kind: Kind, weightIncrementKg: Double = 0.0, repIncrement: Int = 0)`;
  `ProgressionEngine.suggest(templates: List<SetTemplate>, history: List<SetLog>, primaryMuscles: List<Int>, weightMode: WeightMode): Suggestion?` (signature unchanged).

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/dev/allan/workoutapp/ProgressionEngineTest.kt` (the existing
`template(index, min, max)` / `log(session, index, reps, weight, at)` helpers stay as they are;
older tests asserting a suggestion from a *mixed* session must be updated to the new rules —
delete assertions that contradict the table below rather than keeping both):

```kotlin
    // 02/08 rules: one uniform session is enough, and the rep suggestion carries the
    // real surplus. A session with a spread of rep counts says nothing yet.
    private fun uniform(session: Long, reps: Int, weight: Double = 40.0) =
        listOf(log(session, 0, reps, weight), log(session, 1, reps, weight), log(session, 2, reps, weight))

    @Test
    fun `range max reached suggests weight`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            uniform(1, 16), chest, WeightMode.TOTAL,
        )
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s!!.kind)
    }

    @Test
    fun `inside range suggests one rep`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            uniform(1, 15), chest, WeightMode.TOTAL,
        )
        assertEquals(ProgressionEngine.Kind.ADD_REP, s!!.kind)
        assertEquals(1, s.repIncrement)
    }

    @Test
    fun `below floor suggests dropping weight`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            uniform(1, 12), chest, WeightMode.TOTAL,
        )
        assertEquals(ProgressionEngine.Kind.DROP_WEIGHT, s!!.kind)
        assertEquals(ProgressionEngine.incrementFor(40.0, chest, WeightMode.TOTAL), s.weightIncrementKg, 0.001)
    }

    @Test
    fun `fixed target overshoot suggests the real surplus`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 12, null), template(1, 12, null), template(2, 12, null)),
            uniform(1, 16), chest, WeightMode.TOTAL,
        )
        assertEquals(ProgressionEngine.Kind.ADD_REP, s!!.kind)
        assertEquals(4, s.repIncrement)
    }

    @Test
    fun `fixed target beyond plus four suggests weight`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 12, null), template(1, 12, null), template(2, 12, null)),
            uniform(1, 17), chest, WeightMode.TOTAL,
        )
        assertEquals(ProgressionEngine.Kind.ADD_WEIGHT, s!!.kind)
    }

    @Test
    fun `mixed rep counts suggest nothing`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            listOf(log(1, 0, 12), log(1, 1, 14), log(1, 2, 14)), chest, WeightMode.TOTAL,
        )
        assertNull(s)
    }

    @Test
    fun `mixed weights suggest nothing`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            listOf(log(1, 0, 16, 40.0), log(1, 1, 16, 42.5), log(1, 2, 16, 40.0)),
            chest, WeightMode.TOTAL,
        )
        assertNull(s)
    }

    @Test
    fun `incomplete session suggests nothing`() {
        val s = ProgressionEngine.suggest(
            listOf(template(0, 14, 16), template(1, 14, 16), template(2, 14, 16)),
            listOf(log(1, 0, 16), log(1, 1, 16)), chest, WeightMode.TOTAL,
        )
        assertNull(s)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*ProgressionEngineTest*'`
Expected: FAIL — `Unresolved reference: DROP_WEIGHT` and `repIncrement`.

- [ ] **Step 3: Rewrite the engine**

Replace the KDoc block and the `Kind` / `Suggestion` / `suggest` members of
`app/src/main/java/dev/allan/workoutapp/data/ProgressionEngine.kt` with:

```kotlin
/**
 * Double-progression suggestions, never auto-applied (docs/PROGRESSION.md for sources).
 *
 * Rules as of 02/08 (docs/FEEDBACK_BATCH_2026-08-02.md, B2). One finished session is
 * enough — the suggestion shows at the next workout — but only when that session was
 * UNIFORM: every working set logged, all at the same rep count, all at the same weight.
 * A spread (12/14/14) means the exercise hasn't settled, so nothing is suggested.
 *
 * With a uniform session at reps R, weight W and floor F = targetValue:
 *  - explicit range max M: R >= M          -> ADD_WEIGHT
 *  - no range max:         R >  F + 4      -> ADD_WEIGHT
 *  - R < F                                 -> DROP_WEIGHT (one increment step down)
 *  - otherwise                             -> ADD_REP by max(1, R - F)
 *
 * Only REPS sets of type NORMAL / FAILURE count ("working sets"). Timed sets, warmups
 * and drops never trigger suggestions.
 */
object ProgressionEngine {

    enum class Kind { ADD_WEIGHT, ADD_REP, DROP_WEIGHT }

    data class Suggestion(
        val kind: Kind,
        val weightIncrementKg: Double = 0.0,
        /** Reps to add for ADD_REP — the real surplus over the target, at least 1. */
        val repIncrement: Int = 0,
    )
```

Keep `lowerBodyMuscles`, `plateRound` and `incrementFor` exactly as they are, then replace
`suggest` with:

```kotlin
    /**
     * @param templates current set templates of the exercise (define range + working sets)
     * @param history finished-session logs for this workoutExercise, newest first
     *        (SessionDao.previousLogs order), any number of sessions mixed together
     */
    fun suggest(
        templates: List<SetTemplate>,
        history: List<SetLog>,
        primaryMuscles: List<Int>,
        weightMode: WeightMode,
    ): Suggestion? {
        val working = templates.filter {
            it.valueUnit == ValueUnit.REPS && (it.type == SetType.NORMAL || it.type == SetType.FAILURE)
        }
        if (working.isEmpty()) return null
        val workingIndexes = working.map { it.setIndex }.toSet()

        val last = history
            .filter { it.valueUnit == ValueUnit.REPS && it.setIndex in workingIndexes }
            .groupBy { it.sessionId }
            .values
            .maxByOrNull { logs -> logs.maxOf { it.completedAt } }
            ?: return null

        if (last.size < working.size) return null            // exercise not completed
        val reps = last.first().value
        if (last.any { it.value != reps }) return null        // not uniform
        val weight = last.first().weightKg
        if (last.any { it.weightKg != weight }) return null

        val floor = working.minOf { it.targetValue }
        val rangeMax = working.mapNotNull { it.targetValueMax }.maxOrNull()
        val step = incrementFor(weight, primaryMuscles, weightMode)

        return when {
            rangeMax != null && reps >= rangeMax -> Suggestion(Kind.ADD_WEIGHT, step)
            rangeMax == null && reps > floor + 4 -> Suggestion(Kind.ADD_WEIGHT, step)
            reps < floor -> Suggestion(Kind.DROP_WEIGHT, step)
            else -> Suggestion(Kind.ADD_REP, repIncrement = maxOf(1, reps - floor))
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ProgressionEngineTest*'`
Expected: PASS.

- [ ] **Step 5: Handle the new kind at the call sites so the app still builds**

`app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt`, the chip label at
line ~723 — replace the `if (s.kind == …ADD_WEIGHT) … else …` expression with:

```kotlin
                    label = {
                        val kg = if (s.weightIncrementKg % 1.0 == 0.0) "${s.weightIncrementKg.toInt()}"
                        else "${s.weightIncrementKg}"
                        Text(
                            when (s.kind) {
                                dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_WEIGHT ->
                                    stringResource(R.string.suggestion_add_weight, kg)
                                dev.allan.workoutapp.data.ProgressionEngine.Kind.DROP_WEIGHT ->
                                    stringResource(R.string.suggestion_drop_weight, kg)
                                dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_REP ->
                                    stringResource(R.string.suggestion_add_rep, s.repIncrement)
                            }
                        )
                    },
```

`app/src/main/res/values/strings.xml` — replace the existing `suggestion_add_rep` string
and add the drop one:

```xml
    <string name="suggestion_add_rep">+%1$d rep</string>
    <string name="suggestion_drop_weight">−%1$s kg</string>
```

`app/src/main/res/values-pt-rBR/strings.xml`:

```xml
    <string name="suggestion_add_rep">+%1$d rep</string>
    <string name="suggestion_drop_weight">−%1$s kg</string>
```

`app/src/main/res/values-de/strings.xml`:

```xml
    <string name="suggestion_add_rep">+%1$d Wdh</string>
    <string name="suggestion_drop_weight">−%1$s kg</string>
```

Then in `SessionViewModel.applySuggestion` (line ~425) change the `else` branch so it uses
the increment instead of a hard-coded 1 (the full rewrite of this function happens in Task 3;
this keeps the build green now):

```kotlin
                s.kind == dev.allan.workoutapp.data.ProgressionEngine.Kind.ADD_WEIGHT ->
                    set.copy(weightKg = set.weightKg + s.weightIncrementKg)
                s.kind == dev.allan.workoutapp.data.ProgressionEngine.Kind.DROP_WEIGHT ->
                    set.copy(weightKg = (set.weightKg - s.weightIncrementKg).coerceAtLeast(0.0))
                else -> set.copy(value = set.value + s.repIncrement)
```

- [ ] **Step 6: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/ProgressionEngine.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-pt-rBR/strings.xml \
        app/src/main/res/values-de/strings.xml \
        app/src/test/java/dev/allan/workoutapp/ProgressionEngineTest.kt
git commit -m "Suggest from one uniform session, with the real rep surplus"
git push origin main
```

---

### Task 2: Persist the suggestion verdict (B1 schema)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/Entities.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/AppDatabase.kt`

**Interfaces:**
- Produces: entity `SessionSuggestionState(sessionId: Long, workoutExerciseId: Long, handled: Boolean)` (table `session_suggestion_state`, composite PK);
  `SessionDao.upsertSuggestionState(state: SessionSuggestionState)`,
  `SessionDao.suggestionStates(sessionId: Long): List<SessionSuggestionState>`,
  `SessionDao.deleteSuggestionStates(sessionId: Long)`.

- [ ] **Step 1: Add the entity**

Append to `app/src/main/java/dev/allan/workoutapp/data/db/Entities.kt`:

```kotlin
/**
 * "This exercise's progression chip has been answered in this session" — set when the
 * user applies it, dismisses it, or edits the reps by hand. Persisted because
 * SessionViewModel.startOrResume() re-runs on every ON_RESUME and every in-session
 * template edit, and would otherwise recompute the chip and show it again (Allan, 02/08).
 */
@Serializable
@Entity(tableName = "session_suggestion_state", primaryKeys = ["sessionId", "workoutExerciseId"])
data class SessionSuggestionState(
    val sessionId: Long,
    val workoutExerciseId: Long,
    val handled: Boolean = true,
)
```

- [ ] **Step 2: Add the DAO queries**

In `app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt`, inside `SessionDao` (next to
the `upsertDraft` / `drafts` / `deleteDrafts` block):

```kotlin
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestionState(state: SessionSuggestionState)

    @Query("SELECT * FROM session_suggestion_state WHERE sessionId = :sessionId")
    suspend fun suggestionStates(sessionId: Long): List<SessionSuggestionState>

    @Query("DELETE FROM session_suggestion_state WHERE sessionId = :sessionId")
    suspend fun deleteSuggestionStates(sessionId: Long)
```

- [ ] **Step 3: Register the entity and the migration**

In `app/src/main/java/dev/allan/workoutapp/data/db/AppDatabase.kt`: bump `version = 9` to
`version = 10`, add `SessionSuggestionState::class` to the `entities = [...]` list, add the
migration next to `MIGRATION_8_9`:

```kotlin
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_suggestion_state (
                        sessionId INTEGER NOT NULL,
                        workoutExerciseId INTEGER NOT NULL,
                        handled INTEGER NOT NULL,
                        PRIMARY KEY(sessionId, workoutExerciseId)
                    )
                    """
                )
            }
        }
```

and add `MIGRATION_9_10` to the `addMigrations(...)` call.

- [ ] **Step 4: Build to verify Room accepts the schema**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL (Room's annotation processor validates the schema at compile time;
a mismatch between entity and migration SQL fails here).

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/db/Entities.kt \
        app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt \
        app/src/main/java/dev/allan/workoutapp/data/db/AppDatabase.kt
git commit -m "Store whether a session's progression chip was already answered"
git push origin main
```

---

### Task 3: Suggestions stop forgetting and stop returning (B1, B3, B4)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`
- Test: `app/src/test/java/dev/allan/workoutapp/SuggestionPersistenceTest.kt` (create)

**Interfaces:**
- Consumes: `SessionDao.upsertSuggestionState`, `suggestionStates`, `deleteSuggestionStates` (Task 2); `ProgressionEngine.Suggestion.repIncrement`, `Kind.DROP_WEIGHT` (Task 1).
- Produces: `applySuggestedSets(sets: List<SessionSet>, suggestion: ProgressionEngine.Suggestion): List<SessionSet>` (top-level in `SessionViewModel.kt`, pure, testable);
  `SessionViewModel.applySuggestion(exerciseIndex: Int)`, `dismissSuggestion(exerciseIndex: Int)` (same signatures, now persisting).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/allan/workoutapp/SuggestionPersistenceTest.kt`:

```kotlin
package dev.allan.workoutapp

import dev.allan.workoutapp.data.ProgressionEngine
import dev.allan.workoutapp.data.db.SetType
import dev.allan.workoutapp.data.db.ValueUnit
import dev.allan.workoutapp.ui.session.SessionSet
import dev.allan.workoutapp.ui.session.applySuggestedSets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Allan, 02/08: applying "+1" then swapping exercise (or backgrounding the app) lost the
 * change, because only the in-memory state was touched. The applied values now come from a
 * pure function so the numbers themselves are pinned down by a test; persistence is the
 * caller's job (saveDraft).
 */
class SuggestionPersistenceTest {

    private fun set(id: Long, index: Int, reps: Int = 12, weight: Double = 40.0, done: Boolean = false) =
        SessionSet(
            templateId = id,
            setIndex = index,
            type = SetType.NORMAL,
            weightKg = weight,
            value = reps,
            valueUnit = ValueUnit.REPS,
            restSecs = 60,
            targetMin = 12,
            targetMax = 14,
            done = done,
        )

    private val sets = listOf(set(1, 0), set(2, 1), set(3, 2))

    @Test
    fun `add rep applies the full surplus to every undone working set`() {
        val out = applySuggestedSets(
            sets,
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_REP, repIncrement = 4),
        )
        assertEquals(listOf(16, 16, 16), out.map { it.value })
    }

    @Test
    fun `add weight raises the weight and resets reps to the target minimum`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 14), set(2, 1, reps = 14)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_WEIGHT, weightIncrementKg = 2.5),
        )
        assertEquals(listOf(42.5, 42.5), out.map { it.weightKg })
        assertEquals(listOf(12, 12), out.map { it.value })
    }

    @Test
    fun `drop weight lowers the weight, floors at zero and resets reps`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 10, weight = 2.0)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.DROP_WEIGHT, weightIncrementKg = 2.5),
        )
        assertEquals(0.0, out.single().weightKg, 0.001)
        assertEquals(12, out.single().value)
    }

    @Test
    fun `done sets are never touched`() {
        val out = applySuggestedSets(
            listOf(set(1, 0, reps = 12, done = true), set(2, 1, reps = 12)),
            ProgressionEngine.Suggestion(ProgressionEngine.Kind.ADD_REP, repIncrement = 2),
        )
        assertEquals(listOf(12, 14), out.map { it.value })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SuggestionPersistenceTest*'`
Expected: FAIL — `Unresolved reference: applySuggestedSets`.

- [ ] **Step 3: Extract the pure function and persist everything**

In `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`, add this
top-level function next to `openingExercise` (around line 115):

```kotlin
/**
 * The sets after taking a progression suggestion. Only undone REPS working sets change.
 * Weight changes reset the reps to the plan's floor: the point of adding load is to work
 * back up through the range again (Allan, 02/08).
 */
fun applySuggestedSets(
    sets: List<SessionSet>,
    suggestion: dev.allan.workoutapp.data.ProgressionEngine.Suggestion,
): List<SessionSet> {
    val kinds = dev.allan.workoutapp.data.ProgressionEngine.Kind
    return sets.map { set ->
        val working = !set.done && set.valueUnit == ValueUnit.REPS &&
            (set.type == SetType.NORMAL || set.type == SetType.FAILURE)
        if (!working) return@map set
        when (suggestion.kind) {
            kinds.ADD_WEIGHT -> set.copy(
                weightKg = set.weightKg + suggestion.weightIncrementKg,
                value = set.targetMin,
            )
            kinds.DROP_WEIGHT -> set.copy(
                weightKg = (set.weightKg - suggestion.weightIncrementKg).coerceAtLeast(0.0),
                value = set.targetMin,
            )
            kinds.ADD_REP -> set.copy(value = set.value + suggestion.repIncrement)
        }
    }
}
```

Replace `applySuggestion` and `dismissSuggestion` (lines ~424-448) with:

```kotlin
    /** Apply the progression hint to all undone working sets, persist them, then clear it. */
    fun applySuggestion(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val s = ex.suggestion ?: return
        val newSets = applySuggestedSets(ex.sets, s)
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(sets = newSets, suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
        // Drafts are what startOrResume() reads back — without this write the applied
        // numbers died on the next ON_RESUME or template edit (Allan, 02/08).
        newSets.forEach(::saveDraft)
        markSuggestionHandled(ex.workoutExerciseId)
    }

    fun dismissSuggestion(exerciseIndex: Int) {
        val ex = _state.value.exercises.getOrNull(exerciseIndex) ?: return
        val exercises = _state.value.exercises.toMutableList()
        exercises[exerciseIndex] = ex.copy(suggestion = null)
        _state.value = _state.value.copy(exercises = exercises)
        markSuggestionHandled(ex.workoutExerciseId)
    }

    /** Remember, for this session, that the chip was answered — apply, ✕, or a manual edit. */
    private fun markSuggestionHandled(workoutExerciseId: Long) {
        val sessionId = _state.value.sessionId ?: return
        viewModelScope.launch {
            db.sessionDao().upsertSuggestionState(
                dev.allan.workoutapp.data.db.SessionSuggestionState(
                    sessionId = sessionId,
                    workoutExerciseId = workoutExerciseId,
                )
            )
        }
    }
```

In `startOrResume`, read the marks before building the exercise list — after the
`val drafts = …` line (~265):

```kotlin
        val handledSuggestions = db.sessionDao().suggestionStates(session.id)
            .filter { it.handled }.map { it.workoutExerciseId }.toSet()
```

and make the `suggestion =` argument (~300) respect it:

```kotlin
                suggestion = if (we.id in handledSuggestions) null
                else dev.allan.workoutapp.data.ProgressionEngine.suggest(
                    templates = weTemplates,
                    history = previous,
                    primaryMuscles = exercise?.primaryMuscles ?: emptyList(),
                    weightMode = we.weightMode,
                ),
```

Manual edits count as answering (B4) — in `updateSet` (~373), after the existing
`saveDraft(set)` call:

```kotlin
        // Typing the number yourself answers the chip too; it used to keep nagging.
        if (_state.value.exercises.getOrNull(exerciseIndex)?.suggestion != null) {
            dismissSuggestion(exerciseIndex)
        }
```

Finally, clear the marks with the drafts at session end — in `endSession`, next to
`db.sessionDao().deleteDrafts(sessionId)`:

```kotlin
            db.sessionDao().deleteSuggestionStates(sessionId)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SuggestionPersistenceTest*'`
Expected: PASS.

- [ ] **Step 5: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Emulator pass — the amnesia bug**

Install the release apk on `testphone`, start a workout with a suggestion chip showing, then:
1. Tap the chip → the numbers change.
2. Swipe to the next exercise and back → numbers still changed, chip gone.
3. Press Home, wait 5 s, return to the app → numbers still changed, chip gone.
4. Dismiss a chip on another exercise with ✕, background/foreground → chip stays gone.
5. Edit a rep number by hand on a third exercise → its chip disappears.

Screenshot steps 2 and 3 into `screenshots/` for the audit ledger.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/test/java/dev/allan/workoutapp/SuggestionPersistenceTest.kt \
        screenshots
git commit -m "Keep applied and dismissed suggestions across resumes"
git push origin main
```

---

### Task 4: Timing math (A4, A5, A6)

**Files:**
- Create: `app/src/main/java/dev/allan/workoutapp/data/SetTiming.kt`
- Test: `app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt` (create)

**Interfaces:**
- Produces: `SetTiming.DEFAULT_ACTIVE_SECS = 40`, `SetTiming.POSITION_SECS = 5`, `SetTiming.SHARE_WINDOW_MS = 20_000L`, `SetTiming.FAST_TOLERANCE = 0.15`;
  `SetTiming.tempoSecs(tempo: String): Int?`;
  `SetTiming.expectedSecs(reps: Int, tempo: String): Int?`;
  `SetTiming.defaultActiveSecs(reps: Int, tempo: String): Int`;
  `SetTiming.measuredActiveSecs(rawSecs: Int): Int`;
  `SetTiming.Pace { FAST, ON_TEMPO }`; `SetTiming.pace(actualSecs: Int, expectedSecs: Int): Pace`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt`:

```kotlin
package dev.allan.workoutapp

import dev.allan.workoutapp.data.SetTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Active-time rules from Allan's 02/08 batch (docs/FEEDBACK_BATCH_2026-08-02.md, A4–A6). */
class SetTimingTest {

    @Test
    fun `tempo phases add up`() {
        assertEquals(4, SetTiming.tempoSecs("1-1-1-1"))
        assertEquals(2, SetTiming.tempoSecs("1-0-1-0"))
        assertEquals(7, SetTiming.tempoSecs("3-1-2-1"))
    }

    @Test
    fun `an X hold counts as zero`() {
        assertEquals(4, SetTiming.tempoSecs("2-X-2-0"))
    }

    @Test
    fun `blank or unparseable tempo has no seconds`() {
        assertNull(SetTiming.tempoSecs(""))
        assertNull(SetTiming.tempoSecs("slow"))
    }

    @Test
    fun `no tempo falls back to forty seconds`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = ""))
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 3, tempo = ""))
    }

    @Test
    fun `tempo drives the default when defined`() {
        assertEquals(40, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-1-1-1"))
        assertEquals(20, SetTiming.defaultActiveSecs(reps = 10, tempo = "1-0-1-0"))
        assertEquals(70, SetTiming.defaultActiveSecs(reps = 10, tempo = "3-1-2-1"))
    }

    @Test
    fun `measured time loses five seconds for getting into position`() {
        assertEquals(35, SetTiming.measuredActiveSecs(40))
        assertEquals(5, SetTiming.measuredActiveSecs(7))
        assertEquals(5, SetTiming.measuredActiveSecs(2))
    }

    @Test
    fun `more than fifteen percent under the tempo estimate is too fast`() {
        assertEquals(SetTiming.Pace.FAST, SetTiming.pace(actualSecs = 30, expectedSecs = 40))
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 35, expectedSecs = 40))
    }

    @Test
    fun `slower than the estimate is fine`() {
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 90, expectedSecs = 40))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SetTimingTest*'`
Expected: FAIL — `Unresolved reference: SetTiming`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/dev/allan/workoutapp/data/SetTiming.kt`:

```kotlin
package dev.allan.workoutapp.data

/**
 * How long a set counts as "active", and whether it was rushed.
 *
 * Allan, 02/08: a rep set with no timer used to book reps × 3 s. It now books the tempo
 * estimate when a cadence is defined (1-1-1-1 × 10 reps = 40 s, 1-0-1-0 × 10 = 20 s) and
 * a flat 40 s otherwise. Measured durations lose 5 s for getting into position. Moving
 * faster than the cadence is worth a warning; slower is not a problem.
 */
object SetTiming {

    const val DEFAULT_ACTIVE_SECS = 40
    /** Getting under the bar / into the machine is not work time. */
    const val POSITION_SECS = 5
    /** Two registers this close together share one measured duration (superset). */
    const val SHARE_WINDOW_MS = 20_000L
    /** Under the estimate by more than this fraction = rushed. */
    const val FAST_TOLERANCE = 0.15

    enum class Pace { FAST, ON_TEMPO }

    /** Seconds one rep takes at this cadence, e.g. "3-1-2-1" → 7. Null when unusable. */
    fun tempoSecs(tempo: String): Int? {
        val parts = tempo.trim().split('-', ' ').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        // "X" means explosive/no hold — counted as zero, not as a parse failure.
        val secs = parts.map { part ->
            if (part.equals("X", ignoreCase = true)) 0 else part.toIntOrNull() ?: return null
        }
        return secs.sum()
    }

    /** Expected duration of the whole set from its cadence, null when there is none. */
    fun expectedSecs(reps: Int, tempo: String): Int? =
        tempoSecs(tempo)?.let { it * reps }?.takeIf { it > 0 }

    /** Active seconds to book when nothing was timed. */
    fun defaultActiveSecs(reps: Int, tempo: String): Int =
        expectedSecs(reps, tempo) ?: DEFAULT_ACTIVE_SECS

    /** Active seconds to book from a measured duration. */
    fun measuredActiveSecs(rawSecs: Int): Int =
        (rawSecs - POSITION_SECS).coerceAtLeast(POSITION_SECS)

    fun pace(actualSecs: Int, expectedSecs: Int): Pace =
        if (actualSecs < expectedSecs * (1 - FAST_TOLERANCE)) Pace.FAST else Pace.ON_TEMPO
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SetTimingTest*'`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/SetTiming.kt \
        app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt
git commit -m "Derive set duration from the cadence instead of three seconds a rep"
git push origin main
```

---

### Task 5: Book every countdown run, share it across a superset (A2, A3)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/session/SessionManager.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`
- Test: `app/src/test/java/dev/allan/workoutapp/SessionManagerTimerTest.kt`

**Interfaces:**
- Consumes: `SetTiming.SHARE_WINDOW_MS`, `SetTiming.measuredActiveSecs`, `SetTiming.defaultActiveSecs` (Task 4).
- Produces on `SessionManager`: `completeSetCountdown(now: Long = System.currentTimeMillis())` (books the run and clears the countdown), `bookedRunSecs(templateId: Long): Int?`, `clearBookedRuns(templateId: Long)`, `coveredByPreviousMeasure(now: Long = System.currentTimeMillis()): Boolean`, `recordMeasured(secs: Int, now: Long = System.currentTimeMillis())`.
- New `TimerState` fields: `runSecsByTemplate: Map<Long, Int>`, `lastMeasuredSecs: Int?`, `lastMeasuredAt: Long?`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/dev/allan/workoutapp/SessionManagerTimerTest.kt`:

```kotlin
    /**
     * Allan, 02/08: the same 45 s timer run twice (one leg each) booked 45 s once. Each
     * completed run books, and the set itself then books nothing more.
     */
    @Test
    fun `each completed countdown run books its own seconds`() {
        SessionManager.clear()
        SessionManager.startSession(1, System.currentTimeMillis())
        SessionManager.startSetCountdown(45, templateId = 7)
        SessionManager.completeSetCountdown()
        SessionManager.startSetCountdown(45, templateId = 7)
        SessionManager.completeSetCountdown()
        assertEquals(90, SessionManager.state.value.activeSecs)
        assertEquals(90, SessionManager.bookedRunSecs(7))
        assertNull(SessionManager.state.value.setCountdownEndAt)
    }

    @Test
    fun `clearing booked runs forgets the set`() {
        SessionManager.clear()
        SessionManager.startSession(1, System.currentTimeMillis())
        SessionManager.startSetCountdown(30, templateId = 7)
        SessionManager.completeSetCountdown()
        SessionManager.clearBookedRuns(7)
        assertNull(SessionManager.bookedRunSecs(7))
    }

    /**
     * Superset: both exercises are done back to back, so one 2-minute stopwatch run covers
     * both sets. A books the measurement, B — registered seconds later — books nothing.
     */
    @Test
    fun `a set logged right after a measured one is already covered`() {
        SessionManager.clear()
        SessionManager.startSession(1, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        SessionManager.recordMeasured(120, now)
        assertTrue(SessionManager.coveredByPreviousMeasure(now + 5_000))
        assertFalse(SessionManager.coveredByPreviousMeasure(now + 25_000))
    }

    @Test
    fun `nothing measured means nothing is covered`() {
        SessionManager.clear()
        SessionManager.startSession(1, System.currentTimeMillis())
        assertFalse(SessionManager.coveredByPreviousMeasure())
    }
```

Add `import org.junit.Assert.assertNull` to the file's imports if it isn't there already.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SessionManagerTimerTest*'`
Expected: FAIL — `Unresolved reference: completeSetCountdown`.

- [ ] **Step 3: Extend SessionManager**

In `app/src/main/java/dev/allan/workoutapp/session/SessionManager.kt`, add to `TimerState`
(after `lastRestEndedAt`):

```kotlin
        /** Seconds already booked per timed set, keyed by templateId (one entry per set). */
        val runSecsByTemplate: Map<Long, Int> = emptyMap(),
        /** Last real measured set duration, offered to a superset partner logged right after. */
        val lastMeasuredSecs: Int? = null,
        val lastMeasuredAt: Long? = null,
```

and add these functions after `cancelSetCountdown`:

```kotlin
    /**
     * A countdown that ran to the end: book its full duration as active time and remember
     * it against the set, so running the same timer twice (left leg, right leg) counts
     * twice and logging the set afterwards doesn't book it a third time (Allan, 02/08).
     */
    fun completeSetCountdown(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val duration = s.setCountdownDurationSecs
        val templateId = s.setCountdownTemplateId
        if (duration <= 0 || templateId == null) {
            cancelSetCountdown()
            return
        }
        _state.value = s.copy(
            setCountdownEndAt = null,
            setCountdownDurationSecs = 0,
            setCountdownPausedSecs = null,
            setCountdownTemplateId = null,
            activeSecs = s.activeSecs + duration,
            runSecsByTemplate = s.runSecsByTemplate + (templateId to (s.runSecsByTemplate[templateId] ?: 0) + duration),
            lastMeasuredSecs = duration,
            lastMeasuredAt = now,
        )
    }

    /** Seconds already booked by finished countdown runs of this set, null = none. */
    fun bookedRunSecs(templateId: Long): Int? = _state.value.runSecsByTemplate[templateId]

    /** Forget a set's booked runs (used when un-logging it). */
    fun clearBookedRuns(templateId: Long) {
        _state.value = _state.value.copy(
            runSecsByTemplate = _state.value.runSecsByTemplate - templateId
        )
    }

    /** Remember when a real measurement was booked, so the set logged right after it knows. */
    fun recordMeasured(secs: Int, now: Long = System.currentTimeMillis()) {
        _state.value = _state.value.copy(lastMeasuredSecs = secs, lastMeasuredAt = now)
    }

    /**
     * True when a measured duration was booked moments ago: in a superset both exercises are
     * done back to back, so that one measurement already spans the set being logged now and
     * it must not book anything of its own (Allan, 02/08).
     */
    fun coveredByPreviousMeasure(now: Long = System.currentTimeMillis()): Boolean {
        val at = _state.value.lastMeasuredAt ?: return false
        return now - at <= dev.allan.workoutapp.data.SetTiming.SHARE_WINDOW_MS
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SessionManagerTimerTest*'`
Expected: PASS.

- [ ] **Step 5: Wire the ViewModel to the new rules**

In `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`:

`ticker()` (~line 340) — a countdown reaching zero must *book*, not just cancel:

```kotlin
                if (countdownRemaining != null && countdownRemaining <= 0) {
                    SessionManager.completeSetCountdown()
                    TimerService.showDefault(getApplication())
                }
```

`logSet` (~line 463) — replace the whole `val active = when (set.valueUnit) { … }` block plus
its `SessionManager.addActiveSecs(active)` line with:

```kotlin
        // Active time. Timed sets: whatever their countdown runs already booked (one run per
        // leg counts twice) — those seconds are on the clock, so the set books nothing more;
        // a timed set that was never run still books its nominal duration. Rep sets: the
        // stopwatch, else nothing at all when a measurement moments ago already spanned this
        // set (superset partner), else the gap since rest ended, else the cadence estimate
        // (docs/FEEDBACK_BATCH_2026-08-02.md A2–A5).
        val tempo = set.tempo
        val active: Int = when (set.valueUnit) {
            ValueUnit.SECS -> if (SessionManager.bookedRunSecs(set.templateId) != null) 0 else set.value
            ValueUnit.REPS -> {
                val measured = SessionManager.consumeStopwatch()
                when {
                    measured != null ->
                        dev.allan.workoutapp.data.SetTiming.measuredActiveSecs(measured)
                            .also { SessionManager.recordMeasured(measured) }
                    // The previous set's timer covered this one too — book nothing.
                    SessionManager.coveredByPreviousMeasure() -> 0
                    else -> SessionManager.gapActiveSecs()
                        ?.let { dev.allan.workoutapp.data.SetTiming.measuredActiveSecs(it) }
                        ?: dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, tempo)
                }
            }
        }
        SessionManager.addActiveSecs(active)
        // What the summary shows for a timed set is the time its runs actually took.
        val loggedActive =
            if (set.valueUnit == ValueUnit.SECS) SessionManager.bookedRunSecs(set.templateId) ?: set.value
            else active
```

In the `SetLog(...)` insert just below, change `activeSecs = active` to `activeSecs = loggedActive`.

In the same function, the block that retires a running countdown when a timed set is logged
(~line 496) should book a *partial* run rather than throwing it away:

```kotlin
        if (set.valueUnit == ValueUnit.SECS &&
            SessionManager.state.value.setCountdownTemplateId == set.templateId
        ) {
            SessionManager.cancelSetCountdown()
            TimerService.showDefault(getApplication())
        }
```

stays as it is — a countdown the user cut short is deliberately not booked; only completed
runs count. Add this comment above it so nobody "fixes" it later:

```kotlin
        // Only completed runs book time (SessionManager.completeSetCountdown); a countdown
        // stopped early is discarded on purpose.
```

`unlogSet` (~line 530) — give back the booked runs too, inside the existing `let` block after
`SessionManager.addActiveSecs(-it)`:

```kotlin
                SessionManager.clearBookedRuns(set.templateId)
```

- [ ] **Step 6: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Emulator pass — active time**

On `testphone`, in a workout with one timed set (e.g. 45 s) and one superset pair:
1. Run the timed set's countdown to the end twice, then register it → end the workout →
   the summary's active time contains 90 s for that set, not 45 s.
2. In the superset, run the stopwatch across both exercises (e.g. 2 min), register A, then
   register B within a few seconds → the session's active total grows by ~2 min once, not by
   2 min + 40 s. With no stopwatch at all, registering A then B books a default for each.
3. Register a rep set with a cadence `1-0-1-0` and no timer → books 2 s × reps.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/session/SessionManager.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/test/java/dev/allan/workoutapp/SessionManagerTimerTest.kt
git commit -m "Count every timer run, and share one duration across a superset pair"
git push origin main
```

---

### Task 6: The panel offers the countdown for timed sets (A1)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:1194-1279` (`TimerPanel`)
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`
- Test: `app/src/test/java/dev/allan/workoutapp/SessionFlowRegressionTest.kt`

**Interfaces:**
- Consumes: `SessionUiState.currentStep`, `SessionUiState.exercises` (existing).
- Produces: top-level `fun SessionUiState.pendingTimedSet(): SessionSet?` in `SessionViewModel.kt` — the current step's set when it is a SECS set that isn't done, else null; `SessionViewModel.startCurrentSetCountdown()`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/dev/allan/workoutapp/SessionFlowRegressionTest.kt`:

```kotlin
    /**
     * Allan, 02/08: with a timed set current, the panel showed the stopwatch until you
     * pressed play on the row. It now offers that set's countdown straight away.
     */
    @Test
    fun `the current timed set is the panel's pending countdown`() {
        val ex = exercise(
            0,
            listOf(set(1, 0, unit = ValueUnit.SECS), set(2, 1, unit = ValueUnit.SECS)),
        )
        val state = SessionUiState(exercises = listOf(ex), currentStep = 0 to 1L)
        assertEquals(1L, state.pendingTimedSet()?.templateId)
    }

    @Test
    fun `rep sets have no pending countdown`() {
        val ex = exercise(0, listOf(set(1, 0)))
        val state = SessionUiState(exercises = listOf(ex), currentStep = 0 to 1L)
        assertNull(state.pendingTimedSet())
    }

    @Test
    fun `a finished timed set is not pending`() {
        val ex = exercise(0, listOf(set(1, 0, done = true, unit = ValueUnit.SECS)))
        val state = SessionUiState(exercises = listOf(ex), currentStep = null)
        assertNull(state.pendingTimedSet())
    }
```

(The file's existing `set(...)` helper already takes `done` and `unit`; `exercise(index, sets)`
already exists — reuse both as they are.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SessionFlowRegressionTest*'`
Expected: FAIL — `Unresolved reference: pendingTimedSet`.

- [ ] **Step 3: Add the state helper and the start action**

In `SessionViewModel.kt`, next to `openingExercise`:

```kotlin
/**
 * The timed set the panel should offer a countdown for: the current step, when it is an
 * unfinished SECS set. Null for rep sets — the panel keeps its stopwatch then.
 */
fun SessionUiState.pendingTimedSet(): SessionSet? {
    val (exerciseIndex, templateId) = currentStep ?: return null
    val set = exercises.getOrNull(exerciseIndex)?.sets?.firstOrNull { it.templateId == templateId }
        ?: return null
    return set.takeIf { it.valueUnit == ValueUnit.SECS && !it.done }
}
```

and in the class, next to `startSetCountdown`:

```kotlin
    /** Play on the panel with a timed set current: start that set's countdown. */
    fun startCurrentSetCountdown() {
        _state.value.pendingTimedSet()?.let(::startSetCountdown)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SessionFlowRegressionTest*'`
Expected: PASS.

- [ ] **Step 5: Show it in the panel**

In `SessionScreen.kt` `TimerPanel`, add below the three existing role flags (~line 1200):

```kotlin
    // Nothing running + a timed set current → the panel is that set's countdown, ready to start.
    val pendingTimed = if (!restRunning && !setCountdownRunning && !setCountdownPaused)
        state.pendingTimedSet() else null
```

Label (~1208) — add the pending case before the stopwatch fallback:

```kotlin
                    when {
                        restRunning -> R.string.rest
                        setCountdownRunning || setCountdownPaused || pendingTimed != null -> R.string.set_timer
                        else -> R.string.log_set_duration
                    }
```

Value (~1218):

```kotlin
                when {
                    restRunning -> fmt(state.restRemainingSecs ?: 0)
                    setCountdownRunning -> fmt(state.setCountdownRemainingSecs ?: 0)
                    setCountdownPaused -> fmt(state.setCountdownPausedSecs ?: 0)
                    pendingTimed != null -> fmt(pendingTimed.value)
                    else -> fmt(state.stopwatchSecs)
                },
```

Buttons — insert this branch in the `when` (~1237) between the countdown branch and `else`:

```kotlin
                    pendingTimed != null -> IconButton(onClick = vm::startCurrentSetCountdown) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.start_timer),
                        )
                    }
```

Also make sure `import dev.allan.workoutapp.ui.session.pendingTimedSet` resolves — the
function is in the same package, so no import is needed.

- [ ] **Step 6: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Emulator pass**

Open a workout whose current set is a 45 s timed set → the panel reads "Set timer / 0:45"
with a play button; pressing it counts down. Move to a rep set → the panel is the stopwatch
again. Screenshot both into `screenshots/`.

- [ ] **Step 8: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/test/java/dev/allan/workoutapp/SessionFlowRegressionTest.kt \
        screenshots
git commit -m "Offer the timed set's countdown before it is started"
git push origin main
```

---

### Task 7: Pace note against the cadence (A6)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt` (`ExercisePage`, after the tempo row ~line 790)
- Modify: `app/src/main/res/values/strings.xml`, `values-pt-rBR/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `SetTiming.pace`, `SetTiming.expectedSecs` (Task 4); the measured seconds computed in `logSet` (Task 5).
- Produces: `SessionUiState.paceNote: SessionViewModel.PaceNote?` where `data class PaceNote(val exerciseIndex: Int, val fast: Boolean, val actualSecs: Int, val expectedSecs: Int)`; `SessionViewModel.clearPaceNote()`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt`:

```kotlin
    @Test
    fun `a set with no cadence has no pace verdict`() {
        assertNull(SetTiming.expectedSecs(reps = 10, tempo = ""))
    }

    @Test
    fun `exactly at the tolerance edge is still on tempo`() {
        // 40 s expected, 15 % tolerance → 34 s is the first "fast" reading.
        assertEquals(SetTiming.Pace.ON_TEMPO, SetTiming.pace(actualSecs = 34, expectedSecs = 40))
        assertEquals(SetTiming.Pace.FAST, SetTiming.pace(actualSecs = 33, expectedSecs = 40))
    }
```

- [ ] **Step 2: Run the test to verify it fails or passes**

Run: `./gradlew test --tests '*SetTimingTest*'`
Expected: PASS (Task 4 already implements this) — these two cases pin the boundary before
the UI depends on it. If either fails, fix `SetTiming.pace`/`expectedSecs` before continuing.

- [ ] **Step 3: Produce the note in the ViewModel**

In `SessionViewModel.kt`, add to `SessionUiState` (next to `finished`):

```kotlin
    /** Brief feedback after logging a set with a cadence, cleared on the next log. */
    val paceNote: PaceNote? = null,
```

and above `SessionUiState`:

```kotlin
/** How the just-logged set compared to its cadence estimate (only "too fast" warns). */
data class PaceNote(
    val exerciseIndex: Int,
    val fast: Boolean,
    val actualSecs: Int,
    val expectedSecs: Int,
)
```

In `logSet`, right after the `val active: Int = when (...)` block from Task 5:

```kotlin
        // Cadence check: only a measured duration says anything about pace, and only being
        // faster than the cadence is a problem (Allan, 02/08).
        val expected = dev.allan.workoutapp.data.SetTiming.expectedSecs(set.value, tempo)
        val paceNote = if (expected != null && set.valueUnit == ValueUnit.REPS &&
            active != dev.allan.workoutapp.data.SetTiming.defaultActiveSecs(set.value, tempo)
        ) {
            PaceNote(
                exerciseIndex = exerciseIndex,
                fast = dev.allan.workoutapp.data.SetTiming.pace(active, expected) ==
                    dev.allan.workoutapp.data.SetTiming.Pace.FAST,
                actualSecs = active,
                expectedSecs = expected,
            )
        } else null
```

and set it on the state — in the `_state.value = when { … }` block at the end of `logSet`,
add `paceNote = paceNote` to each of the three `copy(...)` calls, e.g.:

```kotlin
        _state.value = when {
            next == null -> _state.value.copy(timerPanelVisible = true, showList = true, paceNote = paceNote)
            next.first != exerciseIndex -> _state.value.copy(
                timerPanelVisible = true,
                pendingSwipeTo = next.first,
                swipeToken = _state.value.swipeToken + 1,
                paceNote = paceNote,
            )
            else -> _state.value.copy(timerPanelVisible = true, paceNote = paceNote)
        }
```

Add the clear action next to `clearPendingSwipe`:

```kotlin
    fun clearPaceNote() {
        _state.value = _state.value.copy(paceNote = null)
    }
```

- [ ] **Step 4: Show it under the cadence row**

In `SessionScreen.kt` `ExercisePage`, right after the tempo-info `AlertDialog` block (~line 790):

```kotlin
        // Pace feedback for the set just logged on this exercise — fades out after 4 s.
        state.paceNote?.takeIf { it.exerciseIndex == page }?.let { note ->
            LaunchedEffect(note) {
                kotlinx.coroutines.delay(4000)
                vm.clearPaceNote()
            }
            Text(
                if (note.fast) stringResource(R.string.pace_too_fast, note.actualSecs, note.expectedSecs)
                else stringResource(R.string.pace_on_tempo),
                style = MaterialTheme.typography.labelMedium,
                color = if (note.fast) MaterialTheme.colorScheme.error else DoneGreen,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
```

Strings — `values/strings.xml`:

```xml
    <string name="pace_too_fast">Faster than the cadence (%1$ds vs %2$ds)</string>
    <string name="pace_on_tempo">On cadence</string>
```

`values-pt-rBR/strings.xml`:

```xml
    <string name="pace_too_fast">Mais rápido que a cadência (%1$ds vs %2$ds)</string>
    <string name="pace_on_tempo">Na cadência</string>
```

`values-de/strings.xml`:

```xml
    <string name="pace_too_fast">Schneller als die Kadenz (%1$ds statt %2$ds)</string>
    <string name="pace_on_tempo">In der Kadenz</string>
```

- [ ] **Step 5: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Emulator pass**

Set a cadence of `3-1-2-1` on an exercise with 10 reps (expected 70 s). Start the stopwatch,
register after ~20 s → red "Faster than the cadence" note appears and disappears after 4 s.
Repeat with ~70 s → green "On cadence". Register a set on an exercise with no cadence →
no note at all. Screenshot the red note.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-pt-rBR/strings.xml \
        app/src/main/res/values-de/strings.xml \
        app/src/test/java/dev/allan/workoutapp/SetTimingTest.kt \
        screenshots
git commit -m "Warn when a set is rushed against its cadence"
git push origin main
```

---

### Task 8: Rest time readable in the notification (C2)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/session/TimerService.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-pt-rBR/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `TimerService.showCountdown(context, endAt, label)` (unchanged public API).
- Produces: no new public API — the service now re-posts the notification every second while a countdown runs.

- [ ] **Step 1: Add the ticking text**

In `app/src/main/java/dev/allan/workoutapp/session/TimerService.kt`, add a ticker field next
to `alertRunnable`:

```kotlin
    private var tickRunnable: Runnable? = null
```

Replace `buildNotification` with a version that carries the remaining time as plain text
(HyperOS does not tick `setChronometerCountDown`, so the countdown looked static — Allan, 02/08):

```kotlin
    private fun buildNotification(title: String, chronometerBase: Long, countDown: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply {
                // The chronometer is not ticked by every skin (HyperOS), so the remaining
                // time is spelled out and re-posted every second by scheduleTick().
                if (countDown) {
                    val remaining = ((chronometerBase - System.currentTimeMillis()) / 1000L)
                        .coerceAtLeast(0L).toInt()
                    setContentText(
                        getString(R.string.rest_remaining, remaining / 60, remaining % 60)
                    )
                }
            }
            .setContentIntent(tapIntent)
            .setUsesChronometer(true)
            .setChronometerCountDown(countDown)
            .setWhen(chronometerBase)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }
```

Add the tick scheduler next to `scheduleAlert`:

```kotlin
    /** Re-post the countdown notification once a second so the remaining time really moves. */
    private fun scheduleTick(label: String, endAt: Long) {
        cancelTick()
        val r = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() >= endAt) return
                notify(buildNotification(title = label, chronometerBase = endAt, countDown = true))
                handler.postDelayed(this, 1000)
            }
        }
        tickRunnable = r
        handler.postDelayed(r, 1000)
    }

    private fun cancelTick() {
        tickRunnable?.let(handler::removeCallbacks)
        tickRunnable = null
    }
```

Hook it into the three places that change the notification's role — in `onStartCommand`:

```kotlin
            ACTION_SHOW_COUNTDOWN -> {
                val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.rest)
                notify(buildNotification(title = label, chronometerBase = endAt, countDown = true))
                scheduleAlert(endAt)
                scheduleTick(label, endAt)
            }
            ACTION_SHOW_DEFAULT -> {
                cancelAlert()
                cancelTick()
                notify(defaultNotification())
            }
            ACTION_STOP -> {
                cancelAlert()
                cancelTick()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
```

and in `scheduleAlert`'s runnable, after `fireAlert()`:

```kotlin
        val r = Runnable {
            fireAlert()
            cancelTick()
            // Countdown over — swap the notification back to session elapsed time.
            notify(defaultNotification())
        }
```

Strings — `values/strings.xml` (keep `rest_until`, it is still used nowhere else; if it is now
unreferenced, delete it from all three files in this same step):

```xml
    <string name="rest_remaining">%1$d:%2$02d left</string>
```

`values-pt-rBR/strings.xml`:

```xml
    <string name="rest_remaining">faltam %1$d:%2$02d</string>
```

`values-de/strings.xml`:

```xml
    <string name="rest_remaining">noch %1$d:%2$02d</string>
```

- [ ] **Step 2: Build**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Emulator pass**

Register a set with a 90 s rest, pull down the notification shade, and watch: the text counts
down every second, both unlocked and on the lock screen. Stop the rest → the notification goes
back to the session elapsed time and stops updating. Screenshot the shade.

- [ ] **Step 4: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/session/TimerService.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-pt-rBR/strings.xml \
        app/src/main/res/values-de/strings.xml \
        screenshots
git commit -m "Spell the remaining rest out in the notification"
git push origin main
```

---

### Task 9: Last summary from the start screen (C1)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/plans/PlansViewModel.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/MainActivity.kt` (Home tab ~line 568, `MainScreen` signature and its call site)
- Modify: `app/src/main/res/values/strings.xml`, `values-pt-rBR/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Produces: `SessionDao.lastFinishedSessionFlow(): Flow<Session?>`; `PlansViewModel.lastFinishedSession: StateFlow<Session?>`; `MainScreen(..., onOpenSummary: (Long) -> Unit)`.

- [ ] **Step 1: Add the query**

In `SessionDao` (`Daos.kt`), next to `finishedSessionsFlow`:

```kotlin
    /** Most recent kept session — the one the Home card links to (Allan, 02/08). */
    @Query("SELECT * FROM session WHERE status IN ('FINISHED','AUTO_ENDED') ORDER BY startedAt DESC LIMIT 1")
    fun lastFinishedSessionFlow(): Flow<Session?>
```

- [ ] **Step 2: Expose it**

In `PlansViewModel.kt`, next to the existing `runningSession` declaration (follow whatever
`stateIn` pattern that one already uses; if it is written as
`val runningSession = db.sessionDao().runningSessionFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)`,
mirror it exactly):

```kotlin
    val lastFinishedSession = db.sessionDao().lastFinishedSessionFlow()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)
```

- [ ] **Step 3: Show the card**

In `MainActivity.kt`, add the parameter to `MainScreen`'s signature next to `onResumeSession`:

```kotlin
    onOpenSummary: (Long) -> Unit,
```

pass it at the call site (next to `onResumeSession = { navController.navigate("session/$it") }`,
~line 254):

```kotlin
                onOpenSummary = { navController.navigate("summary/$it") },
```

collect the flow next to `val runningSession by vm.runningSession.collectAsState()` (~line 488):

```kotlin
    val lastFinishedSession by vm.lastFinishedSession.collectAsState()
```

and add the card in the `Tab.Home` branch, right after the `runningSession?.let { … }` block
(~line 581):

```kotlin
                    // The summary lives on a nav destination, so a process kill while the
                    // screen was locked took it away with the back stack (Allan, 02/08).
                    lastFinishedSession?.let { session ->
                        item {
                            Card(
                                onClick = { onOpenSummary(session.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.last_summary),
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
```

Strings — `values/strings.xml`: `<string name="last_summary">Last workout summary</string>`;
`values-pt-rBR/strings.xml`: `<string name="last_summary">Resumo do último treino</string>`;
`values-de/strings.xml`: `<string name="last_summary">Letzte Trainingsübersicht</string>`.

- [ ] **Step 4: Build**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Emulator pass**

Finish a workout, close the summary, go Home → the card is there and opens the same summary.
Kill the app from recents, reopen → the card is still there. Discard a workout instead →
no new card (the discarded session must not appear). Screenshot the Home tab.

- [ ] **Step 6: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt \
        app/src/main/java/dev/allan/workoutapp/ui/plans/PlansViewModel.kt \
        app/src/main/java/dev/allan/workoutapp/MainActivity.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-pt-rBR/strings.xml \
        app/src/main/res/values-de/strings.xml \
        screenshots
git commit -m "Reach the last workout summary from the start screen"
git push origin main
```

---

### Task 10: Reps steppers, narrower weight button (D1)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:889-931`

**Interfaces:**
- Consumes: `SessionViewModel.updateWeight(exerciseIndex, set, weightKg)`, `updateSet(exerciseIndex, set)` (existing).
- Produces: no new API.

- [ ] **Step 1: Rework the two cells**

Replace the weight `Row` and the reps `OutlinedButton` (lines 889-931) with:

```kotlin
                    // − weight + and − reps + share one stepper shape, so both numbers are
                    // the same size (Allan, 02/08). RowWeights.WEIGHT still gets the wider
                    // column because "137.5" has to fit on one line.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(RowWeights.WEIGHT).padding(horizontal = 2.dp),
                    ) {
                        IconButton(
                            onClick = { vm.updateWeight(page, set, (set.weightKg - 1.0).coerceAtLeast(0.0)) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { editTarget = set to "weight" },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "${if (set.weightKg % 1.0 == 0.0) set.weightKg.toInt() else set.weightKg}",
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        IconButton(
                            onClick = { vm.updateWeight(page, set, set.weightKg + 1.0) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(RowWeights.VALUE).padding(horizontal = 2.dp),
                    ) {
                        IconButton(
                            onClick = {
                                vm.updateSet(page, set.copy(value = (set.value - 1).coerceAtLeast(0)))
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { editTarget = set to "value" },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${set.value}", maxLines = 1, softWrap = false)
                        }
                        IconButton(
                            onClick = { vm.updateSet(page, set.copy(value = set.value + 1)) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
```

Then rebalance the columns — `private object RowWeights` at `SessionScreen.kt:1110` currently
gives weight 4.2 against reps 1.2 (46 % vs 13 % of the row). Both cells are steppers now, so
they share the space more evenly:

```kotlin
private object RowWeights {
    const val TYPE = 0.7f
    const val WEIGHT = 3.0f
    const val VALUE = 2.4f
    const val TARGET = 1.3f
    const val PLAY = 0.45f
    const val CHECK = 0.65f
    const val DELETE = 0.7f
}
```

Keep them `const val` inside the `private object` — the header row and the set rows both read
these, which is what keeps the columns aligned.

- [ ] **Step 2: Build**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Emulator pass — the 3-digit check**

Set one set's weight to `137.5` and another to `100`, with reps at `16`:
- Both numbers render on one line, no ellipsis, no clipping.
- The weight and reps buttons are visibly the same height and comparable width.
- `−`/`+` on reps step by 1 and never go below 0; the value persists after backgrounding
  the app (it goes through `updateSet` → `saveDraft`).
- If `137.5` still clips, raise `RowWeights.WEIGHT` in 0.2 steps until it fits and note the
  final value in the commit message.

Screenshot the row into `screenshots/`.

- [ ] **Step 4: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt screenshots
git commit -m "Give reps the same stepper treatment as weight"
git push origin main
```

---

### Task 11: Pinned note under the exercise image (E1)

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/Entities.kt` (`ExerciseNote`)
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/data/db/AppDatabase.kt` (version 11)
- Modify: `app/src/main/java/dev/allan/workoutapp/data/PlanRepo.kt` (`saveExerciseNote`)
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt`
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt` (`ExercisePage`, after the image `Box` ~line 668)
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/common/ExerciseInfoSheet.kt` (note editor)
- Modify: `app/src/main/res/values/strings.xml`, `values-pt-rBR/strings.xml`, `values-de/strings.xml`

**Interfaces:**
- Consumes: `SessionViewModel.saveNote(exerciseId: String, text: String)` (extended), `openDescription(exerciseId, withImage)` (existing).
- Produces: `ExerciseNote.pinned: Boolean`; `SessionDao.pinnedNote(exerciseId: String): String?`; `SessionDao.noteIsPinned(exerciseId: String): Boolean`; `PlanRepo.saveExerciseNote(db, exerciseId, text, pinned)`; `SessionExercise.pinnedNote: String?`; `SessionViewModel.saveNote(exerciseId, text, pinned)`; `SessionUiState.descriptionNotePinned: Boolean`.

- [ ] **Step 1: Schema**

`Entities.kt` — add the column to `ExerciseNote`:

```kotlin
data class ExerciseNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val sessionId: Long? = null,
    val text: String,
    val updatedAt: Long,
    /** Shown as a line under the exercise image during the session (Allan, 02/08). */
    val pinned: Boolean = false,
)
```

`Daos.kt` — next to `noteText`:

```kotlin
    /** Latest note text, but only when it is pinned (the in-session line). */
    @Query(
        """
        SELECT text FROM exercise_note WHERE exerciseId = :exerciseId AND pinned = 1
        ORDER BY updatedAt DESC LIMIT 1
        """
    )
    suspend fun pinnedNote(exerciseId: String): String?

    @Query(
        """
        SELECT COALESCE((SELECT pinned FROM exercise_note WHERE exerciseId = :exerciseId
        ORDER BY updatedAt DESC LIMIT 1), 0)
        """
    )
    suspend fun noteIsPinned(exerciseId: String): Boolean
```

`AppDatabase.kt` — `version = 11`, plus:

```kotlin
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercise_note ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }
```

added to `addMigrations(...)`.

- [ ] **Step 2: Carry the flag through the repo and the ViewModel**

`PlanRepo.saveExerciseNote` — add a `pinned: Boolean = false` parameter and write it into the
`ExerciseNote` it inserts (keep the rest of that function as it is).

`SessionViewModel.kt` — add to `SessionExercise`:

```kotlin
    /** Pinned note text, shown as a line under the image; null = nothing pinned. */
    val pinnedNote: String? = null,
```

add to `SessionUiState`:

```kotlin
    val descriptionNotePinned: Boolean = false,
```

in `startOrResume`, inside the `SessionExercise(...)` construction, add:

```kotlin
                pinnedNote = db.sessionDao().pinnedNote(we.exerciseId)?.takeIf { it.isNotBlank() },
```

in `openDescription`'s `load()`, next to `descriptionNote = …`:

```kotlin
                    descriptionNotePinned = db.sessionDao().noteIsPinned(exerciseId),
```

and replace `saveNote`:

```kotlin
    fun saveNote(exerciseId: String, text: String, pinned: Boolean) {
        _state.value = _state.value.copy(descriptionNote = text, descriptionNotePinned = pinned)
        viewModelScope.launch {
            PlanRepo.saveExerciseNote(db, exerciseId, text, pinned)
            val shown = text.takeIf { pinned && it.isNotBlank() }
            _state.value = _state.value.copy(
                exercises = _state.value.exercises.map {
                    if (it.exerciseId == exerciseId) it.copy(pinnedNote = shown) else it
                }
            )
        }
    }
```

- [ ] **Step 3: The pin toggle in the note editor**

`ui/common/ExerciseInfoSheet.kt` currently declares `note: String? = null` and
`onSaveNote: (String) -> Unit = {}` (lines ~49-51), and its note block (lines ~99-113) only
shows the save button when `noteText.trim() != note`. Change the parameters to:

```kotlin
    /** Persistent per-exercise note (kept across sessions). null hides the note editor. */
    note: String? = null,
    /** Whether that note is pinned under the exercise image during a session. */
    notePinned: Boolean = false,
    onSaveNote: (String, Boolean) -> Unit = { _, _ -> },
```

and rewrite the note block's body so the toggle sits above the save button and a toggle change
also arms the button (otherwise pinning an unedited note can't be saved):

```kotlin
            if (note != null) {
                var noteText by remember(note) { mutableStateOf(note) }
                var pinned by remember(note, notePinned) { mutableStateOf(notePinned) }
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = pinned,
                        onCheckedChange = { pinned = it },
                    )
                    Text(
                        stringResource(R.string.pin_note),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (noteText.trim() != note || pinned != notePinned) {
                    Button(
                        onClick = { onSaveNote(noteText.trim(), pinned) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
```

Keep the surrounding modifiers of the existing `OutlinedTextField` and `Button` if they differ
from the sketch above — only the toggle, the `pinned` state and the two-argument callback are
new. Then update every call site the compiler flags (`SessionScreen.kt` and the library/editor
screens that open the same sheet) to pass `notePinned = state.descriptionNotePinned` and
`onSaveNote = { text, isPinned -> vm.saveNote(exerciseId, text, isPinned) }`.

- [ ] **Step 4: The line under the image**

In `SessionScreen.kt` `ExercisePage`, immediately after the image `Box` closes (~line 668):

```kotlin
        // Pinned note: the one thing about this exercise worth seeing every set. Secondary
        // colour — tertiary is the superset chain line, error is the pace warning.
        ex.pinnedNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable { vm.openDescription(ex.exerciseId, withImage = false) },
            )
        }
```

Strings — `values/strings.xml`: `<string name="pin_note">Show this note during the workout</string>`;
`values-pt-rBR/strings.xml`: `<string name="pin_note">Mostrar esta nota durante o treino</string>`;
`values-de/strings.xml`: `<string name="pin_note">Diese Notiz im Training zeigen</string>`.

- [ ] **Step 5: Build**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Emulator pass**

Upgrade from the previously installed build (do **not** wipe app data — the point is to
exercise `MIGRATION_10_11`), then:
1. Open an exercise's info sheet, write a note, enable the pin toggle, save → the line appears
   under the image in the secondary colour.
2. Tap the line → the note editor opens with the text.
3. Turn the toggle off, save → the line disappears, the note itself survives (reopen the sheet).
4. Background/foreground the app → the pinned line is still there (it is reloaded by
   `startOrResume`).

Screenshot the pinned line.

- [ ] **Step 7: Commit and push**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/db/Entities.kt \
        app/src/main/java/dev/allan/workoutapp/data/db/Daos.kt \
        app/src/main/java/dev/allan/workoutapp/data/db/AppDatabase.kt \
        app/src/main/java/dev/allan/workoutapp/data/PlanRepo.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionViewModel.kt \
        app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt \
        app/src/main/java/dev/allan/workoutapp/ui/common/ExerciseInfoSheet.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-pt-rBR/strings.xml \
        app/src/main/res/values-de/strings.xml \
        screenshots
git commit -m "Pin a note under the exercise image"
git push origin main
```

---

### Task 12: Close the batch

**Files:**
- Modify: `docs/PROGRESS.md`
- Modify: `docs/AUDIT_2026-07-25.md`
- Modify: `app/build.gradle.kts` (versionCode / versionName)

- [ ] **Step 1: Record the batch**

Add a checkpoint entry to `docs/PROGRESS.md` naming this plan, the 18 items from
`docs/FEEDBACK_BATCH_2026-08-02.md`, and DB v9 → v11.

- [ ] **Step 2: Update the audit ledger**

In `docs/AUDIT_2026-07-25.md`, mark the touched features (`suggestions`, `active time`,
`set timer`, `notification`, `notes`, `summary`) with their new status, and add to the
"Still to verify" list anything only checked on the emulator — everything in this batch,
since the Redmi pass is deferred to 1.0.

- [ ] **Step 3: Bump the version**

In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName` to the next minor
(e.g. `0.6.0` → `0.7.0`).

- [ ] **Step 4: Full build + tests**

Run: `./gradlew test assembleRelease`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit and push**

```bash
git add docs/PROGRESS.md docs/AUDIT_2026-07-25.md app/build.gradle.kts
git commit -m "Log the 02/08 feedback batch and bump the version"
git push origin main
```

---

## Self-review notes

**Spec coverage** — every item in `docs/FEEDBACK_BATCH_2026-08-02.md` maps to a task:
A1 → Task 6; A2, A3 → Task 5; A4, A5 → Tasks 4 + 5; A6 → Tasks 4 + 7; B1 → Tasks 2 + 3;
B2 → Task 1; B3, B4 → Task 3; C1 → Task 9; C2 → Task 8; D1 → Task 10; E1 → Task 11.

**Assumptions worth flagging to Allan before execution:**
1. A5's "−5 s for getting into position" is applied to *measured* rep-set durations, not to
   timed-set countdowns and not to the tempo/40 s default (his "(not timed sets)" note).
2. A6's threshold is 15 % (the middle of his "10–20 %"), and only "too fast" is a warning.
3. A3's "registered one after the other" window is 20 s (Allan, 02/08: one timer run spans
   both superset sets, so the second books 0; with no timer each books its own default).
4. B2's ADD_WEIGHT at the range max still holds for explicit ranges (14–16 → weight at 16),
   while a fixed target (3×12) allows the rep suggestion up to target + 4 before flipping to
   weight — this is the reading that satisfies both "+4 reps for 16 on a 12 target" and
   double progression.
