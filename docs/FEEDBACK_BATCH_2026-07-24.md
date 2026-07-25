# Feedback batch — 2026-07-24/25 (Allan, first real gym session on the Redmi)

18 items from WhatsApp notes + import post-mortem. Grounded in code. Grouped into
batches A–F (implementation order) + deferred backlog. Each batch ends with an
emulator pass + commit/push per checkpoint discipline.

## Batch A — session screen fixes

### A1. Play/check share one slot (sec sets)
- Today: `SessionScreen.kt` trailing icons — PLAY cell (weight 0.6, play only for SECS
  sets) + CHECK cell (0.8, always an active log button) + DELETE. Empty play cell on
  rep sets made check/X look misaligned.
- Wanted: merge PLAY+CHECK into ONE centered slot spanning both cells (~1.4 weight).
  - SECS set, not done → play button (starts set countdown), centered.
  - Set done → check (status, green). Play hides once complete.
  - Rep set, not done → slot empty.
  - Logging a set is done ONLY via the bottom register button — the per-row check
    stops being a tap target (becomes status icon).
- Note: bottom register logs first undone set; out-of-order logging falls back to
  drag-reorder or row edit. Accepted per Allan's instruction.

### A2. Weight edit: last digit not erasable
- `SessionScreen.kt` `NumberPadDialog`: text field renders from `Double` state;
  `onValueChange` = `it.toDoubleOrNull() ?: value` → deleting to "" keeps old value
  ("10" → delete → "1" stays; typing 2 → "12"/"21").
- Fix: back the field with String state (allow ""), parse on OK; keep comma→dot.

### A3. Auto-scroll to current set
- Sets table `verticalScroll(tableScroll)` never follows `state.currentStep`.
- Fix: LaunchedEffect on currentStep → animate scroll so the current row is visible.

### A4. Goal column wraps at 5 digits
- Goal text ("14–16") wraps the "16" onto a second line. `RowWeights.TARGET = 1.0`.
- Fix: single line guaranteed — `maxLines=1` + auto-size/smaller style, and/or bump
  TARGET weight; verify vs the other columns on emulator.

### A5. Weight-type change in-session + rename "Por lado"
- Wanted: while editing weight, option to change the weight TYPE (total / per
  dumbbell / per side) — today `weightMode` is edit-time only.
- Rename pt-BR `weight_per_side` "Por lado" → "Por anilha" (confusing next to
  dumbbells). en/de unchanged ("Per side"/"Pro Seite") unless Allan says otherwise.

## Batch B — tempo per exercise

### B1. Tempo is per-exercise, not per-set
- Today tempo lives on each set (`vm.setSetTempo(tempoSet, …)`, editor saves per
  set-row). Allan: edit once per exercise.
- Wanted: one tempo per exercise; edit once (editor + in-session pill); when active
  it stays visible during the workout in the existing centered row under the image
  (pill + (i) pattern kept per UI-affordance rule).
- Migration: existing per-set tempos collapse to exercise-level (first non-blank).

## Batch C — sound / vibration / notification

### C1. Beep not playing on device (only vibration)
- `TimerService.kt:134` `ToneGenerator(STREAM_NOTIFICATION, 90)` — silent on
  HyperOS (notification stream muted/DND). Move to STREAM_MUSIC or SoundPool on
  usage `USAGE_ASSISTANCE_SONIFICATION`, respect in-app volume setting (C2).
- HyperOS caveat: test on emulator + document; real-device confirm at 1.0.

### C2. Volume button on session top bar
- New IconButton (speaker icon) top of session screen → popup with volume slider +
  mute/unmute. Persist in Settings. Beep volume follows it.

### C3. Muted → vibrate double
- When muted (or volume 0), vibration pattern runs twice as long as today.

### C4. Rest timer in notification + lock screen
- Foreground notification exists but shows no countdown; not visible on lock screen.
- Fix: update notification text each tick with remaining rest (chronometer /
  setShortCriticalText), `setVisibility(VISIBILITY_PUBLIC)`, channel importance
  check. Lock-screen visibility also gated by HyperOS settings — document.

## Batch D — overview page (workout view)

### D1. Finished exercises darker background.
### D2. Returning to overview scrolls to current exercise + highlights it.

## Batch E — import pipeline fixes

### E1. Superset propagation bug
- Symptom: one superset recognized → ALL following exercises listed as superset.
- Generated JSON flags are correct pairs (checked `plan_thales4_fortalecimento.json`)
  → bug is app-side: `SupersetOrder.chain` / import mapping (`PlanTransfer.kt:235`).
  Reproduce with that JSON, fix chain grouping.

### E2. Cardio exercise name renders vertically
- "Elíptico" card showed name one char per line — name column collapses when the
  description/notes paragraph is present. Locate screen (WorkoutView/session) and
  give the name min width / description its own block.

### E3. Match order + custom exercises + approximate-match notes
- Import matching must pass: wger DB → free-exercise-db (FedIndex) → EXISTING custom
  exercises → only then create new custom.
- When only an approximate match exists (e.g. "panturrilha em pé na parede" → plain
  standing calf raise), keep the match but ATTACH A NOTE naming the missing detail
  (foam roller / wall variant). Never silently substitute.
- Image gap: wger matches often had no image while free-exercise-db did — prefer or
  merge media when the match is equivalent.

## Batch F — WORKOUT_PLAN_GENERATOR.md process tuning

- Real-world run (Rotina Seca com o Thales 4, PDF → JSON) was painful:
  - First Claude attempt generated EVERYTHING as custom exercises (ignored DBs).
  - Second attempt used wger only, forgot free-exercise-db.
  - wger website blocks robots.txt → browser lookup fails; md must say to use the
    app's bundled DBs / local index, not the website.
  - Cardio sections translated poorly (one exercise w/ paragraph description — ended
    up as E2 layout bug, but the md should also define how cardio maps to sets).
- Rewrite the md: explicit match pipeline (wger → fed → custom → create), require
  approximate-match notes, cardio mapping rule, image expectations.
- Consider (design, discuss before building): in-app per-exercise import wizard —
  walk each unmatched exercise, show candidates + images, let user pick/confirm.
  Allan's suggestion; matches "live edit goes with you per exercise".

## Deferred backlog (need Allan's go)

- **Spotify/media controls** in session screen (play/pause/next/prev). Needs media
  controller + notification-listener permission; design + permission tradeoff.
- **Calories burned** estimate from kg lifted and/or active time (MET-based).
  Needs bodyweight input (have it) + formula choice; shown in summary.
