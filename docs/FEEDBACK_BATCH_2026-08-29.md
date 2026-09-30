# Feedback batch — 07/08 → 29/08 2026 (Allan, gym sessions on the Redmi)

**Status 2026-09-30 (Phase 34, v0.8.0):** DONE — A1 A2 A3 A4 A5 A6 B1 B3 C1 C2 C3 C4 D1 E1 E2
F1 F2 B2, plus the 16/09 archive/swap weight bug (see the last section). B2 was built per
cardio EXERCISE (wger category / custom flag), not per workout — say if a workout-level flag
is wanted instead. **Open:** F3 (needs the watch model). The [ask] answers assumed are listed in
`PROGRESS.md` Phase 34.

19 items from WhatsApp, grouped into batches A–F in implementation order. Line references are
from the code as of `cb4e821`. Every batch ends with a unit/instrumented test, an emulator pass
and a commit/push (checkpoint discipline).

Open questions are marked **[ask]** — they change what gets built and are cheap to answer.

---

## Batch A — the session screen shows the wrong thing

### A1. Downloaded exercise images don't show during the workout
- Reported: "Images downloaded kit showing during workout, but show on description overview.
  Now shown in notes, previously loaded" — the image is present in the ℹ sheet gallery
  (`SessionScreen.kt:408` `ExerciseImageGallery`) but the session image slot above the sets is
  empty (`SessionScreen.kt:635` `rememberExerciseImages(ex.exerciseId, ex.imagePath)` →
  `:642` `AsyncImage`). It used to render (Phase 6).
- Suspicion: the two call sites resolve media differently — the sheet takes the *sheet's*
  exercise row, the slot takes `ex.imagePath` off the session state, which is built from the
  workout-exercise join and can carry a stale/blank path after a backfill or a media sweep.
- Wanted: the slot uses the same resolution path as the gallery; a regression test that a
  session exercise with a file in `filesDir/exercise_media` reports a non-null image.

### A2. Set type doesn't repaint after being swapped mid-session
- Reported: "Exercise type not visually changing after being swapped during session (but change
  saved)". In-session set editing (Phase 9d) writes the template and the DB keeps it, but the
  row keeps the old letter until the screen is rebuilt.
- Wanted: the type edit updates `_state` for that set immediately (same shape as
  `updateSet`), not only the template.

### A3. Swiping fast between exercises freezes the view mid-animation
- Reported: "Swiping too fast between the exercises during a session breaks the visualization
  and it gets stuck mid movement. Requires resetting it by going to overview."
- Ground: `SessionScreen.kt:306-320` — `pagerState.animateScrollToPage` is launched from a
  `LaunchedEffect` on `pendingSwipeTo`, and `:310` writes `vm.setCurrentIndex(currentPage)` on
  every settle. A fast manual swipe during a queued auto-advance leaves the pager between pages.
- Wanted: cancel/ignore a queued programmatic scroll while the user is dragging
  (`pagerState.isScrollInProgress` / `interactionSource`), and never animate to a page the user
  has already left.

### A4. After skipping an exercise, the "next set" marker sits on the wrong row
- Reported: "If exercise skipped, next set is shown on the one in order instead of the last one
  done (also in superset, highlights the skipped one instead of the second set superset)."
- Wanted: the highlighted "current step" is the first *undone* set of the exercise you are on,
  and inside a superset chain the marker follows `SupersetOrder` past skipped members instead of
  parking on the skipped one.
- Test: extend `SessionFlowRegressionTest` with a skip case (A1 logged, A2 skipped, expect the
  marker on B1, not A2).

### A5. Highlight follows edited numbers
- Reported: "Highlight correctly if set has numbers altered." Editing reps/weight of a row
  mid-session must not move or drop the current-step highlight.

### A6. Cadence reminder disappears in supersets
- Reported: "Cadence reminder not visible in supersets due to screen changing."
- Ground: `SessionScreen.kt:769` takes the tempo from the *page's* exercise
  (`ex.sets.firstOrNull { it.tempo.isNotBlank() }`). In a superset the page flips to the partner
  between sets, so a tempo defined on only one member vanishes.
- Wanted: while a superset chain is active, show the tempo of the set that is actually next.

---

## Batch B — timers

### B1. Log-set button must follow the timer, not the swipe
- Reported: "Button log set should correspond to the currently active exercise if a workout
  timer is active and you swipe by accident."
- Ground: `SessionScreen.kt:1108` — `ex.sets.firstOrNull { !it.done }?.let { vm.logSet(page, it) }`
  uses the pager page.
- Wanted: when a set countdown/stopwatch is running for exercise X, the button logs X's set
  regardless of the page on screen, and says so (button label carries the exercise name).

### B2. Cardio: chain the timed sets automatically
- Reported: "If workout set to cardio, time sets start counting down automatically after the
  previous is finished."
- Wanted: for a cardio exercise, finishing a SECS set's countdown starts the next SECS set's
  countdown (after its `rest_secs`, if any), so a CARDIO 1-style block runs hands-free.
- **[ask]** "Workout set to cardio" = every exercise in it is a cardio exercise, or a new
  per-workout flag? A flag is unambiguous and cheap; auto-detection guesses.

### B3. Timer blinks on the beat when a cadence is defined
- Reported: "Timer section blink every second between two backgrounds if cadence is defined
  (helps with counting)."
- Wanted: while a set is running and the exercise has a tempo, the timer panel background
  alternates between two shades once per second. Off when no tempo, off while resting.
- **[ask]** blink per second, or per tempo phase (3-1-1 → 3 s, 1 s, 1 s)? Per phase is the
  actual counting aid; per second is what was asked.

---

## Batch C — no-surprise UX

### C1. Un-logging a set by accident
- Reported: "Sometimes a user taps to complete set by mistake and removes its completion. Does
  this also remove the last logged set duration from the total?"
- Answer, from the code: **yes**, and deliberately — `SessionViewModel.unlogSet` (`:664`) gives
  back `log.activeSecs` via `SessionManager.addActiveSecs(-it)` and clears the set's booked
  countdown runs, so the time can be booked again on the next log.
- Wanted: make the accident recoverable rather than silent — un-log shows a short
  "Set unlogged · UNDO" snackbar that restores the row and its seconds.

### C2. Pinned notes need their own background
- Reported: "Notes aren't shown in a different background like I asked, to pop out they exist."
- Ground: `SessionScreen.kt:673-677` renders the pinned note as `secondary`-coloured text with
  no container.
- Wanted: a filled/tonal container (rounded, `secondaryContainer`) so the line reads as a note.

### C3. Description editor hidden behind the keyboard
- Reported: "Editing descriptions get the text box hidden behind the keyboard."
- Wanted: `ExerciseInfoSheet`'s editors get `imePadding()` + scroll-to-focused-field; verify in
  the sheet on a 360 dp screen with the IME open.

### C4. Screen must not turn off while the app is active
- Reported: "Make screen not turn off when app active." No `FLAG_KEEP_SCREEN_ON` /
  `keepScreenOn` exists anywhere in the app today.
- Wanted: keep the screen on during a RUNNING session (that is where it hurts), behind a
  Settings switch defaulting to on.
- **[ask]** whole app, or only during a session? Whole-app screen-on drains the battery while
  browsing the library.

---

## Batch D — editor parity

### D1. Set-type colours only exist in the session
- Reported: "Set type color all the same in pure workout editing mode (not in session)."
- Ground: the colour map is private to the session screen
  (`SessionScreen.kt:1194-1202`, W orange / F red / D purple / DoneGreen); the editor's type
  chip (`WorkoutEditorScreen.kt:1411`) only renders a localized label.
- Wanted: extract the map to `ui/common` and use it in the workout editor too, so a set type
  looks the same wherever it is shown.

---

## Batch E — data and correctness

### E1. Muscle attribution lies about the workout's focus
- Reported: "Full body B has no quads but says it targets it the most."
- Ground: `WorkoutViewScreen.kt:132` aggregates `primaryMuscles` + `secondaryMuscles` per
  exercise; the ranking counts an exercise once per listed muscle, so a workout with many
  secondary-quad exercises (lunges, leg press variants, hip thrust) outranks the muscle actually
  being trained, and a wger row with a wrong primary muscle poisons the top slot.
- Wanted: weight the ranking by *sets* (and by primary vs secondary — secondary counts at ~0.5),
  and show the top 3 with their share so the number is inspectable. Unit test with FULLBODY B
  as the fixture.

### E2. Export weight evolution for the active cycle
- Reported: "Function to export a table with active cycles set weight evolution."
- Wanted: a new CSV in Settings — one row per (exercise, session date) for the active plan, with
  the working-set weight, reps and computed volume, so the progression is readable in a
  spreadsheet. `CsvExport.kt` already writes the raw `sets` CSV; this is the pivoted view.
- **[ask]** one row per session per exercise (max working weight), or one row per set?

---

## Batch F — new features, need a brainstorm before any code

### F1. Mirror mode
- Reported: "a mirror mode? Button that opens the front camera so you can use as mirror. Ideally
  recording would be nice (one that does not stop the music playing, which the default camera app
  does)."
- Shape: CameraX preview (front lens) as a full-screen overlay from the session screen; recording
  is the part that needs care — capture without requesting audio focus so Spotify keeps playing
  (video-only `Recorder`, no `AudioSource`), plus where the clips go and when they are deleted.
- Open source only, so CameraX (Apache-2.0) is fine.

### F2. Spotify everywhere
- Reported: "Spotify integration could be shown all over the app."
- Today the App Remote strip exists only in the session pager (Phase 31).
- **[ask]** which screens — Home/Start, workout view, stats? A persistent mini-bar above the
  bottom nav is the cheapest way to get "all over the app" without repeating the widget.

### F3. Watch notifications
- Reported: "Somehow watch notifications?"
- **[ask]** which watch (brand/OS)? A Wear OS companion is a separate module; a Xiaomi/Amazfit
  band only mirrors ongoing notifications, in which case the work is making the rest/set
  notification legible on a small screen instead of building an app.

---

## Not in this batch

- Real-device (Redmi) verification stays deferred to 1.0 — emulator pass per batch.
- The `FORTALECIMENTO - fase 2` cycle import (`plan_fortalecimento_fase2.json`, 2026-09-07):
  the JSON schema still has no per-set `tempo` field, so cadences from the PDF went into the
  exercise `note`. Adding `tempo` to `PlanTransfer.SetDto` (+ the generator doc) is a small
  additive task worth doing before the next cycle.

---

## Added 16/09 — archive copy shows the warm-up weight on every set; swap picks the wrong config

- Reported: "When archiving, weight of first (warmup) set overwrites the ones on the rest, when
  you visualize the archived version. If swapping one for it, it got the weight wrong, I selected
  to use this exercise last config but it used the config from the exercise being swapped out."
- Root cause: `SetTemplate.targetWeightKg` is the day-one target; sessions prefill from the last
  finished log (`previousLogs(weId)`) and never write it back. A copy (`PlanRepo.copyWorkout`)
  gets new ids with no logs, so it restarts at 0 kg, and the session forward-fill then spreads
  the first typed (warm-up) weight over every 0-kg set. The swap's "last config"
  (`findIncomingConfig`) took the highest workout-exercise id = that untrained copy.
- Fixed (commit 6789120): `TemplateCarry.effective` overlays the latest log per slot before
  copying; the swap picks the most recently *trained* use (`TemplateCarry.lastTrained`); the
  forward-fill only fills sets of the same type. Tests: `TemplateCarryTest`, `WeightFillTest`.
