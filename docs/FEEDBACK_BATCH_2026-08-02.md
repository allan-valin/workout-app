# Feedback batch — 2026-08-02 (Allan, second gym session on the Redmi)

18 items, grounded in code. Grouped into batches A–E in implementation order.
Every batch ends with an emulator pass + commit/push (checkpoint discipline).

Answers Allan gave while this was written (2026-08-02) are marked **[decided]**.

---

## Batch A — active time & set timing

### A1. Sec sets: the panel clock should be a countdown, not the stopwatch
- Today: `SessionScreen.kt:1194 TimerPanel` picks its role from what is *running* —
  rest → set countdown → else stopwatch ("log set duration"). With a SECS set current
  and no countdown started, the panel shows the stopwatch.
- Wanted: when the current step (`state.currentStep`) is a SECS set and nothing is
  running, the panel shows that set's duration as a ready-to-start countdown
  (label `set_timer`, value `set.value`), play starts it.

### A2. Every countdown run books active time
- Today: active time for a timed set is booked once, at log time —
  `SessionViewModel.kt:463` `ValueUnit.SECS -> set.value`. Running the same 45 s timer
  twice (left leg, right leg) still books 45 s.
- Wanted: each *completed* countdown run adds its duration to the session's active
  total. Logging a SECS set then books nothing extra if at least one run was booked
  for that set; sets logged with no run at all still book `set.value` (unchanged
  behaviour for people who never press play).

### A3. Supersets: the first set's timer already covers the second
- Today: each `logSet` consumes the stopwatch (`SessionManager.consumeStopwatch()`),
  so the second exercise of a superset — registered seconds after the first, no timer
  restarted — falls through to `gapActiveSecs()` or the rep estimate and *adds* time
  that was already counted.
- Reality (Allan, 02/08): both exercises are done back to back with no chance to touch
  the phone in between, so one timer run of e.g. 2 min covers **both** sets.
- Wanted:
  - First set logged with a **measured** duration (stopwatch) → it books that duration;
    a second set logged within **20 s** books **0** — the measurement already spans it.
  - **No** timer on the first → each set books its own default (A4). Nothing is shared.
  - The 20 s window is what distinguishes "registered one after the other" from two
    genuinely separate sets; it is the only tunable here.

### A4. Default active time from tempo
- Today: rep sets with no stopwatch and no usable gap book `set.value * 3`
  (`SessionViewModel.kt:467`), and `estimateWorkoutSecs` (`SessionViewModel.kt:190`)
  uses a flat 40 s.
- Wanted: no tempo → **40 s** flat. Tempo defined → sum of the tempo's phases × reps.
  `1-1-1-1` × 10 reps = 40 s; `1-0-1-0` × 10 reps = 20 s. Only used when no timer ran.

### A5. Subtract 5 s for getting into position
- **Assumption** (Allan's note: "not timed sets"): the 5 s deduction applies to the
  *measured* active time of REP sets (stopwatch / gap), not to SECS countdowns and not
  to the tempo/40 s default. Floor at 5 s so short sets never go to 0.

### A6. Tempo pace note
- Today: nothing compares real set duration against the tempo.
- Wanted: when a tempo is defined and a measured duration exists, compare it to the
  tempo estimate (A4). **Too fast by more than 15 %** → brief red note under the sets
  ("faster than tempo"). Too slow is *not* a problem (Allan's correction) → green
  "on tempo" note otherwise, no warning.

---

## Batch B — progression suggestions

### B1. Suggestions resurrect, applied values vanish  ← root cause of items 6–10
- `SessionScreen.kt:167` — `ON_RESUME` → `vm.refresh()` → `startOrResume()`, which
  rebuilds every `SessionSet` from draft → previous log → template, and recomputes
  `suggestion` from scratch (`SessionViewModel.kt:300`).
- `applySuggestion` (`SessionViewModel.kt:425`) and `dismissSuggestion`
  (`SessionViewModel.kt:443`) only touch `_state`; neither writes a draft or any
  persisted dismissal.
- Consequences Allan hit, all one bug: applied +1 lost when swapping exercise
  (`startOrResume` also re-runs on every in-session template edit), lost when the app
  goes to background and comes back, chip reappearing after ✕, chip reappearing after
  a manual edit.
- Wanted: applying writes drafts (values survive), and apply / dismiss / manual edit
  all persist a per-session, per-exercise "handled" mark so the chip stays gone.

### B2. Suggestion rules are too eager
- Today `ProgressionEngine.suggest` (`ProgressionEngine.kt:98`) returns ADD_REP from a
  **single** session where every working set reached the range floor. Allan trained
  once → everything suggested +1.
- Wanted **[decided]** — one session is enough, the suggestion shows at the *next*
  workout (Allan, 02/08: "two workouts in a row might be too long, suggest on the next"):
  - Fire only when the **last finished session is uniform**: every working set logged,
    all at the **exact same rep count**, all at the **same weight**. Any spread
    (12/14/14) → no suggestion at all. This is what kills the eager `+1`: a mixed
    session says nothing yet.
  - Ceiling with an explicit range max (`14–16`) = `targetMax`. Reaching it →
    **ADD_WEIGHT**.
  - Ceiling without a range max (`3x12`) = `targetValue + 4`. Reps **above** that →
    ADD_WEIGHT; at or below it → **ADD_REP with the real surplus**
    (`achieved − targetValue`, at least 1). 16 reps on a 12 target → "+4 reps".
  - **Below** the floor (`targetValue`) → **DROP_WEIGHT**, one increment step down,
    floored at 0.
- Reference table (last session's working sets all equal, same weight):

  | Target | Achieved | Suggestion |
  |---|---|---|
  | 3×14–16 | 16 | ADD_WEIGHT |
  | 3×14–16 | 15 | ADD_REP +1 |
  | 3×14–16 | 12 | DROP_WEIGHT |
  | 3×12 | 16 | ADD_REP +4 |
  | 3×12 | 17 | ADD_WEIGHT |
  | 3×12 | 10 | DROP_WEIGHT |
  | 3×14–16 | 12, 14, 14 (mixed) | none |

### B3. Applying a weight change resets reps
- Today `applySuggestion` ADD_WEIGHT only bumps `weightKg`.
- Wanted: ADD_WEIGHT and DROP_WEIGHT also set each undone working set's reps back to
  `targetMin`.

### B4. Manual edit counts as answering the suggestion
- Editing the rep number by hand while a chip is showing marks it handled (B1's
  persisted mark) and hides the chip.

---

## Batch C — summary & notification

### C1. Summary lost after lock/unlock; want it from the start screen
- `MainActivity.kt:346` `summary/{sessionId}` is a nav destination only. HyperOS killing
  the process while the screen is locked relaunches at `main`, and the summary is gone
  with the back stack.
- Wanted: a card on the Home tab (next to the existing `resume_workout` card,
  `MainActivity.kt:568`) linking to the last non-discarded session's summary.

### C2. Rest time not showing in the notification
- `TimerService.kt:103 buildNotification` relies on `setChronometerCountDown` plus a
  static `rest_until HH:mm:ss` line. On HyperOS the chronometer isn't ticking and the
  rest countdown reads as a plain ongoing notification.
- Wanted: the notification text carries an explicit remaining `m:ss`, re-posted every
  second while a countdown runs (cancelled with the countdown), so it is readable with
  no chronometer support.

---

## Batch D — set row controls

### D1. Weight button too wide, reps has no steppers
- Today (`SessionScreen.kt:891-931`): the weight cell is `−  [button]  +` at
  `RowWeights.WEIGHT`; the reps cell is a bare `OutlinedButton` at `RowWeights.VALUE`.
- Wanted: weight button shrunk to the reps button's size (verify with a 3-digit weight,
  e.g. `137.5`, and widen only if it overflows), and `−`/`+` steppers around the reps
  button, step 1, floored at 0.

---

## Batch E — notes

### E1. Pinned note line under the exercise image
- Today notes live only inside the info sheet (`SessionViewModel.saveNote`,
  `ExerciseNote` entity at `Entities.kt:235`).
- Wanted: a pin toggle in the note editor. Pinned → the note text shows as one line
  under the exercise image in a contrasting colour not already used on the screen
  (`tertiary` is taken by the superset chain line → use `secondary`); tapping it opens
  the note editor. Unpinned → nothing extra shows.
- Needs DB migration **9 → 10**: `exercise_note.pinned INTEGER NOT NULL DEFAULT 0`.

---

## Not in this batch

- Drum-practice app idea — separate project, needs its own brainstorm.
- Bounding boxes, accounts, family-tree linking (Desembarque backlog, unrelated).
