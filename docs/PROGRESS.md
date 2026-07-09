# Progress / Checkpoint Log

Resume rule for Claude: read this file + IMPLEMENTATION_PLAN.md, then continue from the first
unchecked item. Update this file and commit after every milestone (checkpoint discipline —
session may be interrupted at any time).

## Environment (done 2026-07-06)

- [x] JDK 21 (system, `/usr/bin/java`) — works for AGP
- [x] Android SDK at `~/Android/Sdk` (cmdline-tools latest, platform-tools, platforms;android-36, build-tools;36.0.0)
- [x] Gradle 8.13 at `~/tools/gradle-8.13` (only needed to bootstrap wrapper; project uses `./gradlew`)
- [x] `ANDROID_HOME` + PATH + aliases (`wapp`, `wbuild`, `winstall`) in `~/.bashrc`; documented in `~/Desktop/txt notes/my-aliases.txt`
- [x] Release keystore `~/keys/workout-app.jks` (alias `workout`), credentials in `~/.gradle/gradle.properties` (WORKOUT_* properties). NEVER regenerate; NEVER commit.

## Phase 0 — skeleton

- [x] Gradle project: AGP 8.12.0, Kotlin 2.2.0, Compose BOM 2025.06.01, minSdk 29 / targetSdk 36
- [x] App module `dev.allan.workoutapp`: MainActivity, Material 3 theme (dynamic color), 4-tab bottom bar placeholder, adaptive launcher icon, strings in en/pt-BR/de
- [x] Release signing config reading WORKOUT_* properties, minify+shrink on
- [x] `./gradlew assembleRelease` green, signed APK produced (1.4 MB, cert SHA-256 8c9526…75b0)
- [x] git repo + private GitHub remote (allan-valin/workout-app), initial commit pushed
- [ ] APK installed and launches on Redmi 15 Pro 5G (Allan: `winstall` via USB, or copy APK to phone)

## Phase 1 — data layer (done 2026-07-07)

- [x] Room 2.7.1 (KSP) + DataStore + kotlinx.serialization + navigation-compose + appcompat
- [x] Full schema (all entities incl. Session/SetLog for later phases), schemas exported to app/schemas
- [x] `tools/wger-snapshot/fetch_wger.py` → assets/wger_snapshot.json (818 exercises; en 818 / de 627 / pt 66), imported once on first launch
- [x] Language flag button (en→pt-BR→de) via AppCompatDelegate, persisted (autoStoreLocales); theme switched to AppCompat descendant — KEEP IT, Material theme crashes AppCompatActivity
- [x] Exercise library: deferred search (button, not per-keystroke), muscle-group + search-language filters, alt-names toggle, detail bottom sheet
- [x] Emulator-verified (AVD `testphone`, headless): home en/pt, search "bench", detail sheet. Screenshots in session scratchpad.

Decisions: wger pt = Brazilian content but only 66 exercises translated — search in pt falls back visibly with (en)/(de) lang markers. Muscle names pt/de hardcoded in MuscleNames.kt.

## Phase 2 — plan editor + wizard (done 2026-07-07)

- [x] Plans CRUD (PlansViewModel), active/inactive toggle, delete, cycleWeeks + startedAt
- [x] Split wizard (SplitWizard.kt): 1-7 days/week templates, 7-day = 5 muscle days + 2 cardio/core; cycle-week + deload banner in plan editor
- [x] Plan editor: rename, cycle weeks, weekday chips per workout (java.time narrow names), add/delete workout
- [x] Workout editor: add/remove/reorder exercises, weight-mode chips (Total/Per dumbbell/Per side + bar weight), per-set rows (type/kg/value/unit/rest), add set (copies last), bulk rest dialog
- [x] Exercise picker = library in picker mode (pickerWorkoutId) with + buttons and toast; custom exercise dialog (name/description/muscle/cardio) in current language
- [x] Home Today list (workoutsForDay ISO day), Active/Inactive tabs with plan cards + FAB new-plan dialog
- [x] Emulator-verified in pt-BR: wizard 7-day plan "Ciclo Teste" (8-week cycle, "Semana 1 de 8" banner), searched "supino", added 2 exercises, editor renders, Home shows "Costas" on Tuesday

Known polish item: set-row field labels wrap ("Re ps", "De sc.") — widen/shorten in Phase 6.

## Phase 3 — session engine (done 2026-07-07, emulator-verified)

- [x] SessionManager: wall-clock-instant timers (rest/set countdown/stopwatch, active+rest accumulators) — survive minimize/kill by design
- [x] TimerService (FGS specialUse): chronometer notification; live countdown notification via setChronometerCountDown when rest/set timer runs (Allan's request); vibrate+beep alert at countdown end, then reverts to session chronometer; POST_NOTIFICATIONS requested at session start
- [x] SessionViewModel: start/resume RUNNING session, prefill from previous logs, logSet (SetLog write, superset skips rest), active time = secs value | stopwatch | 3s/rep estimate, end save/discard
- [x] SessionScreen: list mode + HorizontalPager mode, story progress bar, image placeholder (media Phase 6), sets table w/ tap-to-edit NumberPad overlay (±1.25/2.5/5/10/15/20), checkmark gray→primary, timer panel, note dialog, ⋯ end menu with confirmations
- [x] WorkoutViewScreen (compact "3× 10 Reps", Start/Edit), SummaryScreen (total/active/rest/idle, volume per muscle via StatsCalc), auto-end >5h in WorkoutApp, Home resume card
- [x] Nav: home→view/{id}→session/{id}→summary/{sessionId}; summary back→view, close→main
- [x] EMULATOR VERIFIED 2026-07-07: full flow (view→start→pager→NumberPad +20kg→log set→rest 1:28 in panel), background countdown notification LIVE in shade ("Descanso · 01:05" ticking with app minimized), resume brought session back (2:20, 1/3), end+confirm→summary: 200 kg total = 20×10 ✓, Dorsais 200 ✓, active 0:30 (3s/rep) ✓, idle = total−active−rest exactly ✓
- Polish notes for Phase 6: NumberPad "+1.25" renders as "+1.2" (button width), am-kill resume untested (code path same as reopen)

## Phase 4 — statistics tab (done 2026-07-07, emulator-verified)

- [x] StatsTab: averages card (sessions, duration, volume, active/rest/idle), volume-over-time + bodyweight line charts (dependency-free Canvas LineChart), bodyweight quick-add (BodyMetric, one per epochDay), height in DataStore (data/Settings.kt)
- [x] Unit tests green: StatsCalc (TOTAL/PER_DUMBBELL ×2/PER_SIDE bar+2×side, SECS no volume, per-muscle attribution incl. Allan's 30kg spec example), SplitWizard (counts 1-7, clamping, unique ISO days, 7-day has 2 cardio)
- [x] Emulator: stats tab renders real data from the Phase 3 test session (1 session, 200 kg, 2:43)

## Phase 5 — import/export (done 2026-07-07, emulator-verified)

- [x] PlanTransfer: schema-v1 JSON import (resolver: wger_id → exact name any lang → exact alias → custom_fallback → skip+report) + export (round-trips). Contract = docs/WORKOUT_PLAN_GENERATOR.md
- [x] CsvExport: sets / sessions / body CSVs per generator-doc columns
- [x] Backup: full JSON export/restore (fresh-install restore, ids verbatim), incl. custom exercises + height; Room entities now @Serializable
- [x] SettingsScreen (gear on main top bar): SAF launchers for all of the above, plan-picker dialog, import report dialog, wger attribution
- [x] Emulator: imported test JSON via SAF → report "1 treinos, 2 exercícios, 1 personalizados criados, 1 ignorados: Nonexistent Exercise XYZ" — all resolver paths hit

## Phase 6 — polish (done 2026-07-07, emulator-verified)

- [x] MediaStore: image download-on-add (wger hosts only, 15MB cap, sampled to ≤1080px JPEG, filesDir/exercise_media) + backfill on session start; shown in session image slot. Verified: Remada Curvada illustration downloaded + rendered offline
- [x] HyperOS onboarding on first session: battery-exemption intent + Xiaomi autostart intent (fallback app details), one-time via DataStore flag. Verified dialog shows/dismisses
- [x] Label fixes: set-row labels now "kg"/"×"/"s" (no wrap), NumberPad increments labelSmall
- [x] Prefill from previous session visible in new session (20 kg on set 1)

## Phase 7 — backlog dev (started 2026-07-07; Play Store explicitly excluded by Allan)

- [x] wger DB refresh button in Settings (data/sync/WgerSync.kt): mirrors fetch_wger.py, fetch-all-then-one-transaction (abort keeps old data), preserves imagePath/licenseAuthor, never touches custom exercises, additive (upstream deletions kept), sanity check ≥500 exercises
- [x] Media ref-count sweep (MediaStore.sweep, runs on app start — no explicit "plan save" moment exists; stale imagePath re-checked by ensureImage); GIF support: .gif URLs saved verbatim (no re-encode), rendered via Coil 2.7 + coil-gif ImageDecoderDecoder in session image slot
- [x] Prev/next exercise buttons behind Settings toggle (chevron row under pager); scroll-collapsing image (NestedScrollConnection on sets table, animateDpAsState 110→0dp, reappears at scroll top)
- [x] Injured-muscle exclusion: Settings "Injuries" chip card (DataStore set), library search hides primary+secondary matches, override toggle in filter sheet (visible only when injuries set). Physio category still skipped — no usable open dataset (plan §3 unchanged)
- [x] Auto workout suggestions: SuggestionEngine (focus recipes muscle-id→count, cardio pool sentinel), ✨ button in workout editor → focus dialog (reuses split_* strings), appends non-duplicate picks preferring illustrated exercises, respects injured muscles. Unit tests (SuggestionEngineTest) green
- [x] pt-BR translation batch: 748/752 names generated (4 brand names skipped: Blaze, Blackroll, Bronco, Limber 11). Tables in tools/pt-aliases/part*.json (en name → pt name), generate.py → assets/pt_aliases.json (wger id → pt name). PtAliases.merge inserts pt translation rows (en description carried over) on app start and after wger sync; wger's own pt always wins (skip-if-exists)

Excluded: Play Store (needs $25 account + 12-tester closed test) — Allan decision 2026-07-07.

## Phase 8 — emulator-feedback batch (started 2026-07-07)

Allan's feedback after first emulator run. DB bumped to v2 (Migration 1→2: workout.archived,
workout_exercise.supersetWithPrev, set_template.targetValueMax — additive, history preserved).

- [x] Emulator fix: AVD `testphone` had hw.gpu.enabled=no (software rendering) → crash after
      startup + host freeze on second ✨ suggestion run. Now gpu host + 3 GB RAM; documented in
      MAINTENANCE.md. Editor also no longer re-resolves every exercise name on each set edit
      (nameCache in WorkoutEditorViewModel).
- [x] ✨ suggestions: dialog now asks focus AND how many exercises (Default/2/4/6/8 →
      SuggestionEngine.scaledRecipe round-robin); button shows spinner + guarded while running
- [x] Delete safety: plan-editor one-tap delete removed → long-press = selection mode
      (checkboxes, contextual top bar) → archive/unarchive or delete w/ confirm. Archived
      workouts: hidden from Today (workoutsForDay), listed in "Archived" section, history kept.
      Editor exercise delete + set delete now confirm first.
- [x] Workout editor UX: column header row (Type / kg·mode / Reps–max / unit / Rest); set-type
      shows initial letter only; type + reps/secs dropdowns have tinted pill background
      (= changeable); rep range fields (min + optional max); "Assign days" caption above weekday
      chips; exercise-count circle badge (plan editor + Home today cards); reorder no longer
      jumps viewport (clearFocus + animateItem slide).
- [x] Superset redefined per Allan: exercise-level link (supersetWithPrev) = alternate A1,B1
      (no rest), rest, A2,B2… SessionViewModel SupersetOrder handles chains >2 too; session
      shows "Superset: A ↔ B" and highlights the current expected set (primaryContainer row);
      legacy SetType.SUPERSET still skips rest but is hidden from new-set choices.
- [x] Rep ranges + progression: session sets show target range as reference next to actual
      input; ProgressionEngine (double progression + 2-for-2, ACSM increments — see
      docs/PROGRESSION.md w/ Consensus links) → tap-to-apply/dismiss bubble, never automatic.
      9 unit tests green (ProgressionEngineTest).
- [x] Home: Mon–Sun circle row above Today (checkmark = finished session that day, today
      outlined); empty-state card w/ create-plan button when no active plans; light/dark toggle
      (DataStore theme_mode, system default until first toggle).
- [x] Settings: "Create a plan with AI" card (in-app how-to dialog + SAF export of
      workout_plan_generator.md, bundled as asset via gradle copy task from docs/ — single
      source of truth); plan PDF export (PdfExport.kt, A4, per-workout sections, set tables,
      superset marks, rep ranges).
- [x] Plan JSON schema: optional value_max + superset_with_previous (v1-compatible, importer
      defaults; exporter emits). Generator doc updated w/ example + field rules.
- [x] README.md written (architecture, build, import/export incl. PDF, LLM workflow).
- [x] Rep-range input iterated after smoke test: separate min/max fields wrapped on 360dp width
      → single RangeField accepting "10" or "10-12" (regex parse), unit chip shows R/s initial
- [x] Tests: ProgressionEngineTest (9) + SupersetOrderTest (7) green; full testDebug/Release green
- [x] versionCode 2 / versionName 0.2.0; release APK built + emulator-smoke-tested 2026-07-07
      (screenshots in session scratchpad): v1→v2 migration over existing data OK (plans/workouts
      intact, no crash), week row w/ today outlined, dark-mode toggle persists, plan editor
      badges/Assign days/long-press hint, workout editor headers + tinted chips + superset chip +
      range field. NOT smoke-tested visually: session superset flow + highlight + suggestion
      bubble (logic unit-tested), PDF export, MD asset export — Allan: try these on the Redmi.
- [x] Commit + push (c6bb546, 2026-07-07)
- [ ] Allan: real-device pass on the Redmi (`winstall`), incl. session superset flow + PDF export

## Status: Phases 0-9 complete; Phase 10 (second QA batch) mostly done through SESSION
2026-07-09e (DB v6, versionCode 4 / 0.4.0 — favorites + demo import/export). Remaining
Phase-10 open items: global bottom-nav on drill-in screens (pending Allan's call, see 09e),
and any leftover sub-items in the Active/plan-management block. Real-device test on the Redmi
intentionally deferred until a trusted 1.0 (Allan). Everything post-emulator is unit-test +
build green, NOT emulator-verified.

Phase 7 EMULATOR-VERIFIED 2026-07-07 (AVD testphone, debug build, pt-BR; screenshots in session scratchpad):
- pt batch: search "testa" → 5 "Tríceps Testa …" generated names; still present after wger sync (re-merge works)
- Injured muscles: Tríceps chip in Settings → same search 0 results; filter-sheet override toggle restores them; chip state persists
- Suggestions: ✨ in workout editor → focus dialog (11 options) → Push filled 4 exercises (2 chest + 2 shoulder-tagged), NO tríceps (injured respected), images auto-downloaded
- Prev/next buttons: toggle in Settings → chevron row under pager, left disabled on page 1, right navigates + story bar advances
- wger refresh: real sync OK — "Atualizado: 818 exercícios, 1511 traduções", button disabled while "Atualizando…"
- Media sweep: on app restart planted orphan wger_99999.jpg deleted, 4 referenced files kept
- NOT visually verified (code-only): GIF playback (no .gif exercise encountered), scroll-collapsing image (test workout tables too short to scroll)

Polish note: muscle "Obliquus externus abdominis" chip shows latin (wger ships no name_en for it) — add to MuscleNames map someday.

## Phase 9 — Allan's first-emulator-QA feedback batch (2026-07-08)

Session (SessionScreen/ViewModel/SessionManager):
- [x] DB v3 (MIGRATION_2_3): `session_set_draft` (unlogged weight/reps typed mid-session persist
      across leaving the screen / process death; dropped on session end) + `exercise_link`
      (user YouTube link per exercise — own table so wger snapshot refreshes never touch it;
      included in full backup).
- [x] Checkmark toggle: tapping a done set un-logs it (SetLog row deleted, active secs refunded).
- [x] Weight forward-fill: editing a set's weight fills later undone 0-kg sets of the exercise.
- [x] Set rows: − / + steppers (2.5 kg) around weight button, "kg" suffix dropped (header has it),
      value button number-only, done-header shows Reps/Secs per exercise, set-type letters
      color-coded (W orange, F error red, D purple, S tertiary), headers centered.
- [x] Timer panel rework: stopwatch play/pause keeps value & resumes; square stop = reset (books
      nothing); stopwatch disabled+grayed while rest runs; explicit "stop rest" text button;
      new Active column (booked active secs + live stopwatch) makes active-time tracking visible.
      SessionManager: stopwatchAccumSecs added; pause no longer books into activeSecs (consume does).
- [x] Auto-advance: after logging, pager follows SupersetOrder.nextStep (superset partner ↔ back,
      next exercise on last set); all sets done → back to exercise list (End workout lives there).
- [x] Top bar: ℹ description button (bottom sheet w/ localized description), notes button now has
      text label; description sheet hosts the YouTube link field (save icon), Watch video
      (WebView embed overlay dialog, autoplay) + open externally (ACTION_VIEW).
- [x] Exercise-list mode: per-exercise ✎ opens WorkoutEditorScreen?focus=weId (editor filtered to
      that one exercise); session VM refreshes templates on nav-entry ON_RESUME.

Plans / import-export:
- [x] WorkoutViewScreen start button says "Resume workout" when this workout's session is RUNNING.
- [x] Plan editor: workout checkboxes always visible (long-press still toggles), selection top bar
      gains single-workout JSON export (Share icon); "Add workout" dialog offers copy-existing
      (any plan, exercises + sets duplicated via PlanRepo.copyWorkout).
- [x] Active/Inactive tabs: plan cards show checkbox + "N workouts" subtitle (plan vs workout
      distinction); top-bar delete with confirm; PlanRepo.deletePlanDeep/deleteWorkoutDeep clean
      child rows (schema has no FK cascades — deleteWorkouts previously orphaned rows).
- [x] PlanTransfer: File.plan now optional + File.workout added (additive v1; LLM contract files
      unchanged). parse() detects plan vs workout; plan-name collision → dialog: rename (auto
      "name (2)") or merge (append workouts); workout files → target-plan picker (or new plan).
      exportWorkout() shares one workout. Settings plan-picker options now bordered buttons.
- [x] Typography bumped app-wide (Material defaults +1–2 sp); editor unit dropdown shows 3 letters
      (Rep/Sec/Seg/Wdh…), tinted dropdowns get outline border; session image 110→165 dp (+50%).
- [x] Strings: all new UI in en/pt-BR/de. Tests green (`./gradlew test`). versionCode 3 / 0.3.0.
- [x] Emulator-verified: v2→v3 migration over existing data OK, home + Active tab render
      (checkbox, treinos count). NOT visually verified: session flow details, import dialogs,
      video overlay — next emulator/Redmi pass.

Env notes (2026-07-08): `-gpu host` segfaults this machine's emulator right after boot — always
launch with `-gpu swiftshader_indirect` (docs/MAINTENANCE.md updated 2026-07-07). AVD `testphone`
now has hw.keyboard=yes (host keyboard typing works). Android nav keyevents verified working
(`adb shell input keyevent 3/4/187`). "App resumed exactly where I left it yesterday" = emulator
quick-boot snapshot restoring the whole OS, not app behavior; cold-boot with `-no-snapshot-load`
to test a fresh start.

NOTE (2026-07-09, end of timed dev block): docs/demo.html is BEHIND the app — it still lacks
the suggestion wizard, stats point-graph pages, editor multi-select and the single-timer
panel details. Sync it (MAINTENANCE.md rule) in the next session. Everything implemented
below is compile+unit-test green but NOT emulator-verified yet.

UPDATE (2026-07-09c): docs/demo.html synced to the many-to-many rework and this session's
changes — flat workouts + plan_workout links; Active tab shows the active plan's workouts
with ▶; Archive hub (Plans/Workouts) + Archive→Workouts link; add-workout screen with
link-vs-base; read-only workout view with download/archive/delete; editor undo/redo + Save;
shared info sheet with editable video link; multi-focus suggest; session elapsed/≈ETA. Still
simplified vs app: stats shows a list (no point-graphs), no per-plan day assignment. Node
DOM-stub smoke test green (12 flows).

## Phase 10 — Allan's second-emulator-QA feedback batch (2026-07-08; IN PROGRESS —
first implementation pass 2026-07-08, emulator-unverified)

Known DB constraint for this batch: wger muscles are a flat list (~15 entries, see
`MuscleNames.kt`) — there is NO native upper/mid/lower-pec style granularity. Sub-muscle
features below are conditional: either add a custom sub-muscle tagging layer or drop those
sub-items. Compound vs isolation IS derivable (primary + secondary muscle count). Decide at
implementation time.

Naming / chrome:
- [x] Rename "plan" to "cycle" in the create-new flow wording (new_plan/plan_name strings, 3 langs).
- [x] Top-bar title reflects the tab ("Start", "Active", "Archive", "Statistics", localized).
- [x] Dark/light toggle, language switcher, and gear icon only appear on the Start tab.
- [x] Renamed "Inactive" tab to "Archive" (label only; archive-screen rework still open below).

Plan create / import:
- [x] New-cycle overlay: import-file button reusing the Settings import pipeline
      (PlanImportDialogs extracted and shared; collision dialogs work from the main screen).

Exercise search / library:
- [x] "New custom" replaced by "Custom exercises" sheet: list of existing customs, checkbox
      select (picker mode adds all selected), ℹ detail, delete with confirm (blocked with a
      message while workouts still use the exercise); create-new lives inside the sheet.
- [x] Search results show the exercise image above the title (local file first, wger URL
      fallback; ExerciseHit gained imagePath).
- [x] Editor: ℹ icon per exercise opens a localized description dialog.
- [x] Library icon moved off the Home tab; now an OutlinedButton at the bottom of the
      Active/Archive tabs (MainActivity.LibraryButton). Adding-to-workout from the browse
      library was REPLACED by favorites (Allan 2026-07-09e): star an exercise instead —
      favorites float to the top of library search and are picked first by the suggestion
      engine. No workout-selection page needed. See SESSION 2026-07-09e.
- [x] YouTube link editing moved to the library detail sheet with an explicit Save/Delete
      link button (blank clears); the in-session sheet is view-only (watch/open).
- [x] YouTube link bugs: save is now a full-width filled Button below the field (contrast
      fixed); blank + save deletes the link (saveVideoLink → deleteVideoLink, already worked);
      "watch video" overlay fixed — YouTube's iframe player was blank without
      settings.domStorageEnabled + a WebViewClient (added). "Open on YouTube" uses ACTION_VIEW;
      on the emulator (no YouTube app) it falls back to a browser — treat as correct, verify
      on the Redmi. Emulator-UNVERIFIED (overlay).

Suggestion flow (workout editor ✨):
- [x] Suggestion wizard step 1: focus buttons are multi-select FilterChips; recipes of all
      selected foci merge (SuggestionEngine.mergedRecipe).
- [x] Duration estimation: wizard now has an editable "Target duration" (min) field that
      back-computes the count (round(mins / 6)); count and duration drive each other. Strings
      suggest_duration / minutes_suffix in 3 langs.
- [x] During a session, total estimate shown to the right of the elapsed clock in both top
      bars (state.estimatedTotalSecs = Σ exercise: 60 s + Σ sets (set secs or 40 s + restSecs);
      estimateWorkoutSecs in SessionViewModel). Emulator-UNVERIFIED.
- [x] "How many exercises" row: "default" chip and fixed chips replaced by editable numeric
      field (min 1) with −/+ steppers.
- [x] Wizard step 3: per-muscle −/+ counts (derived from merged recipes scaled to the
      requested total), Go back keeps all selections, Confirm fills via
      fillWorkoutByMuscles. (No sub-muscle rows — wger list is flat, see constraint.)
- [x] Presets pre-select their recipe muscles in step 3. Full body offers Compound vs
      Isolation chips (ranking by distinct primary+secondary muscle count). Sub-muscle
      spreading impossible (flat wger list).
- [x] Step-1 "Consider injuries" checkbox → step 2 lists all muscles as chips; selections
      are session-only extra injuries merged with the persisted Settings ones.

Active screen / plan management rework:
- [ ] Only ONE plan active at a time. Tapping the Active tab goes straight to the
      workout-selection / day-assignment page of that plan (no plan list).
- [ ] Drop the left checkbox there; tapping a workout opens the same page "today's workout"
      opens from the main screen. That page gains, next to the edit icon, the actions the
      checkbox selection used to expose: download, archive, delete.
- [ ] The 4 bottom nav buttons stay visible on Active (and everywhere) — they only disappear
      during an in-progress workout.
- [x] Plan editor, workout checked: share icon swapped for a download (arrow-down) icon.
- [ ] Archiving a workout: ask confirmation, then DETACH it from the parent plan and move it
      to Archive (today it stays listed under the same screen, which makes no sense).
- [ ] "Add workout" overlay won't scale to dozens of workouts: replace with a button opening
      a full screen (back arrow) listing ALL workouts, active and inactive, multi-select to
      add several. Two add modes, visually unmistakable: (a) link the existing workout —
      single copy shown in several plans, edits propagate everywhere; (b) use as base — an
      independent copy is created. Wording must make the difference explicit.
- [ ] Each workout row on the Active page gets a right-side button to start that workout
      immediately (opens the training-summary page).
- [ ] Archive screen: two big square buttons splitting the space evenly — "Plans" and
      "Workouts". Plans → all inactive plans. Workouts → ALL workouts including active ones
      (label the active ones), independent of plans; from there a workout can be added to the
      active plan. This replaces the activate-toggle + archive dual mechanism: everything is
      archive-based now.

Workout editor (sets/pauses table):
- [x] Editor: per-exercise trash removed; checkbox left of the name multi-selects; top bar
      shows select-all / unselect-all / delete with ONE confirmation for the batch.
- [x] Deleting a single set: no confirmation anymore.

Statistics rework (point graphs):
- [x] Bodyweight card now first, progression (ex-averages) second.
- [x] Bodyweight: height/BMI removed; +Add includes a Material date picker (backfill);
      PointAreaChart (dots + line + filled area, real time x-axis), 1-month card preview;
      tapping opens BodyweightScreen with 1w/1m/3m/6m/1y/all chips and last-7 list with
      pencil edit (upsert by epochDay).
- [x] "Averages" renamed "Progression"; card opens ProgressionScreen: total-volume
      PointAreaChart + one chart per muscle group (primary-muscle attribution), same range
      chips. Old inline volume LineChart removed.

SESSION 2026-07-09e (favorites + demo import/export; DB v6, versionCode 4 / 0.4.0):
- Favorite/star exercises (DB v6, MIGRATION_5_6 adds `exercise_favorite` — own table like
  exercise_link/exercise_note so wger snapshot refreshes never touch it). Chosen over
  "add to workout from the browse library" (that needed a workout-selection page; favorite
  is the lighter path Allan preferred anyway). Star button per row in ExerciseLibraryScreen
  (browse AND picker mode); favorites sort to the top of search (stable) and are picked
  first by SuggestionEngine.fillWorkoutByMuscles (favorites → illustrated → compound/iso,
  favorites taken before the shuffled head so they aren't shuffled away). Backup gains a
  `favorites` list (absent in pre-v6 backups). Strings favorite/en-pt-de.
- Nav-bar visibility: Allan's rule "4 nav buttons always visible except editing, search,
  during a workout" — current behavior ALREADY matches for the tabbed surface: the bottom
  NavigationBar shows on all 4 main tabs and is absent only in the workout editor (editing),
  library/picker (search), and session (workout). NOT changed. DECISION/OPEN: drill-in
  screens (WorkoutView, Archive sub-screens, Bodyweight/Progression, Summary) also have no
  bottom nav (back-arrow only) — a strict reading of the rule would add nav there, but every
  such screen owns its own Scaffold so a shared/global bottom bar is a nested-scaffold
  refactor; deferred pending Allan's confirmation that he wants nav on those too.
- demo.html: real JSON import/export (was an alert stub). Workout-view ⬇️ downloads that one
  workout (app transfer shape: name + exercises + sets). Settings sheet gained Export data
  (full in-memory state → workout-app-demo.json) + Import data (file picker → full backup
  restore, or a single workout/plan file, unknown exercise ids skipped). Still in-memory only
  (refresh resets). Node DOM-stub smoke test green (5 flows: workout export, full export,
  full re-import, single-workout import w/ unknown-id skip, bad-payload guard).
- Compile + unit tests green (testDebug + testRelease); release APK built. NOT emulator-
  verified (favorite star, search reorder, suggestion prioritization are logic/UI-only) —
  Allan is intentionally staying off-device until a trusted 1.0.
- STILL DEMO DEBT (unchanged from 09d): demo.html lacks note-in-info-sheet, tempo, clock
  toggle, in-session set editing, AND now the favorite star.

SESSION 2026-07-09d (note-persistence bug + tempo + in-session set editing; DB v5):
- Note bug FIXED (headline): the in-session note dialog always opened empty and inserted a
  new row each save. Notes are now ONE persistent per-exercise note (PlanRepo.saveExerciseNote
  upserts via delete+insert; noteText() reads latest), pre-filled and shown in EVERY ℹ sheet
  (library/editor/session/workout-view) via the shared ExerciseInfoSheet. The separate broken
  session note dialog was removed. Emulator-VERIFIED: info sheet opens with the saved note
  pre-filled.
- Cadence/tempo per set (DB v5, MIGRATION_4_5 adds set_template.tempo TEXT default ''):
  editable in the workout editor below each set; shown big above the sets for the current set
  during a session.
- Session clock (elapsed/ETA) toggleable in the top bar (Settings.showClock, default on).
- Centered the set-type letter + goal number in session rows.
- In-session set editing (mirrors the editor): tap type letter → type dropdown; tap goal →
  target dialog; long-press row → remove; row-wide "Add set" button. At session end, if the
  plan was edited, a keep-vs-one-time prompt (one-time restores the templateSnapshot captured
  at session-view load). endSession(save, keepPlanChanges).
- Compile + unit tests green; v5 migration clean over existing data. NOT interactively driven
  (classifier outage): tempo display, in-session type/target/add/remove, keep/one-time prompt —
  logic-only, verify on Redmi.
- DEMO DEBT: docs/demo.html not yet synced for note-in-info-sheet, tempo, clock toggle, and
  in-session set editing.

SESSION 2026-07-09c (Active/Archive many-to-many rework + 3 bug fixes, EMULATOR-VERIFIED):
DB v4 (MIGRATION_3_4): workout↔plan is now MANY-TO-MANY via a `plan_workout(planId,
workoutId, orderIndex)` join; the workout table lost planId/orderIndex (rebuilt, rows
backfilled into the join). Verified the v3→v4 migration over Allan's existing "Teste" plan
(3 workouts intact, correct badges, workoutsForDay still works).
- One active plan at a time: setPlanActive/createPlan deactivate all others first
  (deactivateAllPlans). PlansViewModel.activePlan / activePlanWorkouts flows.
- Active tab = the active plan's workouts directly (no plan list): header + edit-plan pencil,
  each workout row has a ▶ start (→ training-summary/view), "Add workout" + library at bottom.
  Verified: Teste → Empurrar/Puxar/Pernas rows with ▶.
- Archive tab = two square buttons "Plans"/"Workouts" (ArchiveHubContent). Plans →
  ArchivePlansScreen (activate/delete inactive plans). Workouts → ArchiveWorkoutsScreen: ALL
  workouts, those in the active plan labelled "In active plan", others get "Add to plan" (LINK).
  Verified: link an orphan workout → it flips to "In active plan".
- Add-workout full screen (AddWorkoutScreen): multi-select any workout, then LINK (shared,
  PlanRepo.linkWorkout) or "Use as base" (independent copy, PlanRepo.copyWorkout).
- WorkoutViewScreen top bar gained download (SAF export) / archive (detach from active plan +
  archived=true) / delete (deleteWorkoutDeep) next to edit; both confirm.
- PlanRepo: linkWorkout / detachWorkout / archiveWorkout / createWorkout added; deletePlanDeep
  now removes joins + plan only (keeps shared/orphan workouts); copyWorkout builds an
  independent workout + join. Backup gains planWorkouts (pre-v4 backups lose membership).
- Also fixed: (1) suggestion wizard step 3 lists every selected-group muscle even at low totals
  (0-filled); (2) editor marks dirty + undoable when an exercise is added from the picker
  (checkExternalChange on ON_RESUME); (3) WorkoutViewScreen exercise detail now shows the
  editable YouTube link (shared ExerciseInfoSheet).
- Compile + unit tests green. Verified on emulator: migration, Active tab, Archive hub,
  Archive→Workouts link. NOT yet driven: Add-workout link/base screen, WorkoutView
  archive/delete, Archive→Plans (all use the same verified VM paths).

SESSION 2026-07-09b (second feedback pass, EMULATOR-VERIFIED — screenshots this session):
- Suggest wizard: per-muscle step (3) now shown ONLY when ≥2 foci or full-body-isolation;
  single focus / full-body-compound skip it. Confirm button dynamically reads "Confirm" vs
  "Next" (steps 1 & 2) based on whether a further step follows. Verified: Push→"Bestätigen",
  Push+Pull→"Weiter". needsMuscleStep + finish()/recompute in SuggestWizard.
- ℹ detail unified into ONE slide-up sheet everywhere (ui/common/ExerciseInfoSheet): library,
  workout editor (was a popup), in-session. All three now have an EDITABLE video link (visible
  filled "save/delete" button — contrast fix) + Watch/Open, so a link is added straight from the
  exercise (no more library-search hassle). VideoOverlayDialog moved to common (domStorage +
  WebViewClient kept). Verified in editor: add link → save button → Watch/Open appear.
- Session ⋯ menu reduced to a single "End workout" item (both old items opened the same
  save/discard dialog anyway).
- Workout editor undo/redo/save/discard (NEW, Allan request): snapshot the workout's full
  content (name+exercises+templates) before each mutation; undo/redo restore via REPLACE inserts
  (ids preserved). Top bar gained undo/redo icons + "Save" (persist-and-exit); back arrow /
  system back with unsaved edits → keep/discard dialog (discard restores the open-state snapshot).
  All editor mutations routed through edit{}/recordChange. Verified on device: add-set enabled
  undo, undo reverted + enabled redo, back→"Änderungen behalten?" dialog, discard restored.
- STILL OPEN from this feedback: undo granularity is per-keystroke on rename (noisy but correct);
  picker-added exercises don't push an undo entry (discard still reverts them via initial snapshot).

SESSION 2026-07-09 (smaller-items pass; Allan chose to DEFER the Active/Archive plan-management
rework — it needs a plan_workout many-to-many schema + migration for the "link workout across
plans" mode, own session): done this pass = YouTube link overlay/contrast/delete, wizard target-
duration input, in-session total-time estimate, library button moved to bottom of Active/Archive
tabs, auto-advance bug B (swipeToken fix). All compile + unit-test green, NOT emulator-verified.
STILL OPEN in Phase 10: the whole "Active screen / plan management rework" block (one-plan-active,
Active tab → workout-selection page, per-row start/edit/archive/delete, archive-detach,
add-workout full-screen link-vs-base, Archive two-button Plans/Workouts), plus adding exercises to
a workout from the relocated library button.

In-progress session:
- [x] Weight − / + steppers: step 1 kg instead of 2.5.
- [x] Set-complete ticks: incomplete = primary (visible on the highlight), completed =
      medium green 0xFF43A047.
- [x] Timer rework done: ONE centered timer with role header (rest → "Rest" + stop-rest
      button; timed set → "Set timer"; otherwise "Log set duration" stopwatch). Active total
      removed from the panel (summary only). Stop books the reading then shows 0:00
      (SessionManager.stopBookStopwatch). No stopwatch → gap since last rest end
      (SessionManager.gapActiveSecs, single-use anchor; >3 min books 40 s); fallbacks to
      3 s/rep only when no anchor exists. Logging mid-stopwatch books it and starts the
      pause (already existing consumeStopwatch path).
- [x] Auto-advance bug A (wrong target): logging a set jumped the pager to the FIRST
      not-fully-completed exercise instead of staying relative to the one just logged.
      Fixed 2026-07-08: `SupersetOrder.nextStepFrom(exercises, fromIndex)` — own chain
      first, then forward, wrapping so skipped exercises come last; used by
      logSet/updateSet/updateWeight (global `nextStep` kept for session start). Unit
      tests cover stay/forward/wrap/superset cases. Not yet emulator-verified.
- [x] Auto-advance bug B (dead): root cause = the pager's LaunchedEffect was keyed on
      state.pendingSwipeTo (the target index), so two successive advances to the SAME page
      index left the key unchanged and the effect never re-fired → auto-advance appeared to
      die. Fixed with a monotonic SessionUiState.swipeToken bumped on every requestSwipe /
      auto-advance; the effect now keys on the token. Emulator-UNVERIFIED.
- [x] Story bar: segments 4→8 dp tall and tappable (pager jumps via pendingSwipeTo).
- [x] End workout: unified dialog with green Save / red Discard buttons and an "N of M sets
      not completed" warning when sets are open.
