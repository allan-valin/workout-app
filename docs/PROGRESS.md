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

## Status: Phases 0-13 complete (Phase-10 Active/plan-management block closed by 09c/09e —
checkboxes retro-marked 2026-07-11). Real-device test on the Redmi intentionally deferred
until a trusted 1.0 (Allan); emulator verification required every batch. Open: DEMO DEBT
(deferred by Allan), unique-name enforcement on RENAME (future item).

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
- [x] Only ONE plan active at a time. Tapping the Active tab goes straight to the
      workout-selection / day-assignment page of that plan (no plan list). (done 09c)
- [x] Drop the left checkbox there; tapping a workout opens the same page "today's workout"
      opens from the main screen. That page gains, next to the edit icon, the actions the
      checkbox selection used to expose: download, archive, delete. (done 09c)
- [x] The 4 bottom nav buttons stay visible on Active (and everywhere) — they only disappear
      during an in-progress workout. (done 09e global bottom nav)
- [x] Plan editor, workout checked: share icon swapped for a download (arrow-down) icon.
- [x] Archiving a workout: ask confirmation, then DETACH it from the parent plan and move it
      to Archive. (done 09c; Phase 12 archiveWorkoutFully detaches from EVERY plan)
- [x] "Add workout" overlay won't scale to dozens of workouts: replace with a button opening
      a full screen (back arrow) listing ALL workouts, active and inactive, multi-select to
      add several. Two add modes, visually unmistakable: (a) link the existing workout —
      single copy shown in several plans, edits propagate everywhere; (b) use as base — an
      independent copy is created. (done 09c AddWorkoutScreen; reworked Phase 11 into the
      3-option chooser, "link" renamed "Import")
- [x] Each workout row on the Active page gets a right-side button to start that workout
      immediately (opens the training-summary page). (done 09c ▶)
- [x] Archive screen: two big square buttons splitting the space evenly — "Plans" and
      "Workouts". Plans → all inactive plans. Workouts → ALL workouts including active ones
      (label the active ones), independent of plans; from there a workout can be added to the
      active plan. Everything archive-based now. (done 09c ArchiveHubContent)

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
- Global bottom nav (Allan 2026-07-09e, corrected): the 4-tab NavigationBar now shows on
  EVERY screen except an in-progress workout (session). Hoisted into AppRoot: a single outer
  Scaffold (contentWindowInsets 0 so only the bar adds bottom padding) wraps the NavHost;
  `showBottomBar = route not startsWith "session/"`. Selected tab hoisted to AppRoot and
  shared with MainScaffold (which lost its own bottomBar + `selected` state); AppBottomBar
  extracted as the shared bar. A tab tap from a drill-in screen navigates back to "main"
  (popUpTo main inclusive, launchSingleTop) and selects that tab. Back arrows on drill-in
  screens are KEPT (both, as Allan asked). Only SessionScreen is full-height with no bar.
- demo.html: real JSON import/export (was an alert stub). Workout-view ⬇️ downloads that one
  workout (app transfer shape: name + exercises + sets). Settings sheet gained Export data
  (full in-memory state → workout-app-demo.json) + Import data (file picker → full backup
  restore, or a single workout/plan file, unknown exercise ids skipped). Still in-memory only
  (refresh resets). Node DOM-stub smoke test green (5 flows: workout export, full export,
  full re-import, single-workout import w/ unknown-id skip, bad-payload guard).
- Compile + unit tests green (testDebug + testRelease); release APK built. NOT emulator-
  verified (favorite star, search reorder, suggestion prioritization are logic/UI-only) —
  Allan is intentionally staying off-device until a trusted 1.0.
- DEMO DEBT PAID (2026-07-09e): demo.html synced for favorite star (list + info sheet, sort
  favorites first, suggestions prefer them), per-exercise note in the info sheet, per-set
  tempo (editor column + big display of the current set's tempo in session), session clock
  toggle (🕐 in the session header, hides elapsed/ETA), and in-session set editing (tap type
  letter to cycle, tap reps to edit, add-set button, ✕ to remove). favorites/notes also in
  the demo export/import. Node DOM-stub smoke test now 9 flows green. demo.html now current.

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

## Phase 11 — Allan feedback (2026-07-10)

SESSION 2026-07-10 (second feedback batch of the day — compile + unit-test green, NOT
emulator-verified; Allan staying off-device until a trusted 1.0):
- [x] In-session delete "x": no confirmation (SessionScreen removeSetTarget dialog + state
      removed; x calls removeSessionSet directly). Long-press a set row now DRAG-REORDERS
      instead of deleting.
- [x] Add-set duplicates the CURRENT last row (was cloning a stale snapshot). Editor addSet
      reads the latest sets from the DB inside edit{} (async write race). Session addSessionSet
      also carries the last row's live weight/reps (draft, not template) into the new set.
- [x] Session table header alignment: trailing controls (optional SECS play + check + close)
      packed into ONE fixed-width 112dp slot (play always reserved) so every row + header line
      up on the same columns; header spacer widened 80->112.
- [x] Cadence row = justified "Cadence: 1-2-3-4 (i)": label left, centered value, existing info
      button right (was a labelled TextField + trailing info). Tempo already commits on change.
- [x] Nav transitions: NavHost enter/exit/pop slide+fade (220ms) — kills the "two screens
      merged for a frame" flash and the sluggish drill-in feel (incl. the "..." end menu).
- [x] Add-workout consolidation + bug fix (Allan: don't delete AddWorkoutScreen, reuse it):
      * Root bug — AddWorkoutScreen got a fresh nav-scoped PlansViewModel whose activePlan
        StateFlow was still null (async Room emit) when Link/Base was tapped -> return@launch
        no-op. Fixed with a suspend planDao().activePlanNow() + addWorkoutsToPlan(planId,...);
        the same null-race latent in Archive "Add to plan" is fixed too.
      * Add-workout removed from the plain Active view — now ONLY in the plan editor overlay +
        Archive (per Allan). Plain Active just lists workouts + start.
      * Plan-editor overlay is now the single 3-option chooser: (a) From scratch (empty workout,
        opens editor), (b) Import an archived workout as-is (shared link, archived-only list),
        (c) Use as base (independent copy, all workouts). AddWorkoutScreen parametrized with
        planId + AddWorkoutMode(IMPORT/BASE): IMPORT lists archived only + links; BASE lists all
        + copies. Single action button.
      * "Vincular"/link renamed to "Import" (import_workout string, 3 langs) + new hint strings.
- [x] Stats bodyweight date: the picker trigger is now a bordered OutlinedButton with a calendar
      (DateRange) icon so it reads as tappable (was a plain TextButton).
- [x] Drag-reorder (both editor + session): added sh.calvin.reorderable 2.4.3 (ReorderableColumn,
      non-lazy, neighbours slide out of the drop path). Long-press any set row = handle.
      Editor moveSet renumbers setIndex. Session moveSessionSet renumbers templates AND remaps
      already-logged SetLogs (they key on setIndex; drafts key on templateId so untouched).
- [x] EXPERIMENTAL muscle map (Allan wants to SEE it before finalizing): data/MuscleMap.kt maps
      the stable wger muscle ids -> coarse BodyRegions + regionLoad() (primary 1.0, secondary
      0.5). ui/common/BodyMap.kt draws a front+back WIRE body on Canvas, filling targeted regions
      orange, DEEPER orange = more exercises hitting it (over/under-trained signal). Shown under
      the exercise list on WorkoutViewScreen (per-workout) and on the Active tab (unioned across
      the whole cycle). NOTE: blocks are approximate, NOT anatomical — first pass. If Allan likes
      the idea, replace the Canvas blocks with wger's own per-muscle SVG overlays.
- wger research (Allan: "look at their repo for stats I missed"; diet excluded): their Django
  apps we DON'T mirror = **measurements** (arbitrary body circumferences / body-fat % over time
  with charts, beyond just bodyweight) and **trophies** (achievements). The "pie chart" Allan saw
  = wger's per-muscle exercise distribution on a routine (we now show it as the body map instead).
  Candidate next features: custom body-measurement tracking (waist/arm/etc.) in Statistics.
- DEMO DEBT: docs/demo.html NOT synced for this batch (add-workout 3-option overlay, drag-reorder,
  justified cadence row, muscle map, stats date affordance). Sync next session (MAINTENANCE rule).
- STILL OPEN in Phase 11: the "swap exercise" block below (untouched this session).

SESSION 2026-07-11 (Allan's follow-up on the 07-10 batch — compile + unit-test + assembleDebug
green; NOT emulator-verified):
- [x] Session row: trailing play/check/delete were bunched at the right edge -> Arrangement
      changed End->SpaceBetween so they spread across the 112dp slot (header still aligned).
- [x] Nav: dropped the fade (it made content pop in top-to-bottom); now a clean horizontal
      swipe — new screen slides fully in from the right, old parallaxes 1/3 out (260ms).
- [x] Workout rows (Active tab): bottom-right "Last trained <date>" in the device's localized
      short-date pattern (planDao.lastTrainedFlow MAX(startedAt) per workout, FINISHED/AUTO_ENDED;
      PlansViewModel.lastTrained; formatDateShort via DateTimeFormatter.ofLocalizedDate SHORT).
- [x] Active tab: the add/import-cycle FAB now shows ONLY when there's no active cycle (hidden
      while one is active — a second cycle would just deactivate this one).
- [x] Plan editor: active toggle replaced by a button — "Deactivate" (Archive icon, setActive
      false) when active, "Activate" (deactivateAllPlans + activate) when not — mirroring the
      Archive screen's activate action. deactivate string added (3 langs).
- [x] Archive > Workouts: top-bar + action creates a NEW workout straight into the archive
      (PlansViewModel.createArchivedWorkout, archived=true, no plan link) and opens the editor
      (onEditWorkout -> workout/{id}). (The add-training I failed to acknowledge last session.)
- [x] Muscle body reworked from the abstract stick-figure to ANATOMICAL: bundles wger's own
      grayscale front/back body SVGs + per-muscle overlay SVGs (assets/body/, fetched from
      wger.de, CC-BY-SA) rendered via coil-svg. Base drawn as-is; each targeted muscle's overlay
      tinted orange by load (deeper = more exercises) with ColorFilter.tint. MuscleMap now keys by
      wger muscle id (FRONT_IDS/BACK_IDS from snapshot is_front) instead of abstract regions.
      Added a legend row: "Shade = training volume" + colored squares 1/2/3/4+ exercises (3 langs).
      Shown on WorkoutViewScreen (per workout) + Active tab (cycle union). STILL EXPERIMENTAL.
- wger research DROPPED at Allan's request (not his use case; he expected gym-mgmt/PT features
  which their Django apps do have but he's not pursuing).
- DEMO DEBT still open (07-10 batch + this one) — deferred by Allan, do NOT spend resources yet.
- Committed + pushed (checkpoint discipline restored this session).

Workout editor — swap exercise (active OR archived workout) — DONE 2026-07-11:
- [x] Top-bar "Swap" action (SwapVert icon) shown only when EXACTLY one exercise is selected via
      its checkbox, left of select-all/delete. Clears selection and opens the library.
- [x] Library opens in swap mode (title "Swap exercise", SwapVert per-result button). Picking a
      result hands "weId:exerciseId" back to the editor via the nav savedStateHandle, pops.
- [x] Replace IN PLACE: only WorkoutExercise.exerciseId changes (orderIndex + supersetWithPrev
      preserved). Then a set-config dialog: (a) keep current config; (b) use the incoming
      exercise's last config — offered only if another workout_exercise with that exercise has
      templates (findIncomingConfig, newest by id); (c) default 3×10 @ 60 s. Routed through the
      editor's edit{} so it's undoable. New DAO: workoutExercise(id), workoutExercisesByExercise,
      deleteSetTemplatesForExercise. Strings swap_* in 3 langs.
- Compile + assembleDebug + unit tests green. NOT emulator-verified.
- Committed + pushed.

Phase 11 COMPLETE (all feedback + swap). Remaining app-wide open item: DEMO DEBT (demo.html
behind for both 07-10 and 07-11 batches) — deferred by Allan.

## Phase 12 — Allan feedback (2026-07-11 night) — DONE 2026-07-11 (Fable session; compile +
unit tests + assembleDebug green; NOT emulator-verified per off-device-until-1.0 rule)

Validation of the 07-11 Opus batch first (Allan asked): row-spread "fix" was a mathematical
no-op — the 112dp trailing slot held 40+40+28=108dp of icons, so SpaceBetween had 4dp to
distribute. Tab-switch anim genuinely missed (state change, not a NavHost destination).
Swap-config gate was ALREADY correct (see below). Archive add + FAB parity were real misses.

Row / alignment:
- [x] Session set-row reworked to fully WEIGHTED cells (RowWeights object in SessionScreen:
      type/weight-group/value/target/play/check/delete = 0.7/3.4/1.2/1.0/0.9/0.9/0.7). Header
      uses the same weights (trailing = one spacer summing the 3 icon weights) so alignment
      holds by construction; spacedBy removed from both. Icons sit centered in their own cells
      and spread with screen width — no fixed slot left to clump in.

Nav transitions:
- [x] Bottom-nav tab content now animates: AnimatedContent keyed on selectedTab wraps the main
      LazyColumn, direction-aware horizontal slide (enter from right when moving right in tab
      order, mirrored back), same 260ms tween + 1/3 parallax as the drill-in transition.

Add-workout / add-cycle consistency:
- [x] The 3-option overlay extracted to ui/plans/AddWorkoutChooser.kt (AddWorkoutChooserDialog +
      AddWorkoutOption moved out of PlanEditorScreen). Archive > Workouts now shows the SAME
      overlay: scratch → createArchivedWorkout + editor; import as-is → moves a workout into
      the Archive (PlanRepo.archiveWorkoutFully: detach from every plan + archived=true); use
      as base → independent archived copy (copyWorkout targetPlanId now nullable → archived
      standalone copy). AddWorkoutScreen takes planId: Long? — null = archive target (IMPORT
      lists non-archived, BASE lists all); new route addWorkoutArchive/{mode}.
- [x] Archive > Workouts top-bar IconButton replaced by the same "+" FAB as the Active tab.
- [x] Archive > Cycles got the same FAB → full NewPlanDialog (moved to ui/plans/NewPlanDialog.kt,
      shared) incl. the Settings import pipeline (own SAF launcher + PlanImportDialogs).

New-cycle overlay:
- [x] "Repeat an archived cycle" button (reactivate_cycle, 3 langs) below import-JSON →
      ReactivateCycleDialog picker of inactive plans → setPlanActive (one-active rule). Hidden
      when no archived cycles exist, and on Archive > Cycles (circular there — the list behind
      the dialog IS the archive).

Muscle graphics:
- [x] "(experimental)" stripped from muscles_worked / muscles_worked_cycle in en/pt-BR/de.
- [x] Drawn-body asset: KEPT the wger xray body (Allan's fallback clause). wger only ships the
      xray-style per-muscle overlays; a drawn MuscleWiki-style set would be a new asset hunt +
      license review — decide on-device whether it's worth it.

Swap exercise:
- [x] VERIFIED already gated correctly: the dialog shows "use last config" only when
      prompt.hasIncoming, and findIncomingConfig returns non-null only for another
      workout_exercise of that exercise WITH non-empty set templates. No fix needed.

Cadence (tempo) editor redesign:
- [x] Shared overlay ui/common/CadenceDialog.kt: 4 digit boxes (numeric keyboard, direct
      typing), up-triangle above / down-triangle below each box (±1, 0..99), OK on the same
      row; all-zero confirms as "" (clears). Editor SetRow: inline text field replaced by a
      CENTERED OutlinedButton ("Edit cadence" / "Cadence: 4-0-2-0"), info (i) kept in place.
- [x] In-session mirror: the big tempo display is now tappable → same overlay (writes through
      SessionViewModel.setSetTempo → editTemplate, so the end-of-session keep-vs-one-time
      prompt covers it); when the current set has no tempo, a centered "Edit cadence"
      TextButton appears instead. Strings edit_cadence / cadence_value in 3 langs.

DEMO DEBT still open (07-10 + 07-11 batches + this one) — deferred by Allan, do NOT spend
resources yet.

EMULATOR-VERIFIED 2026-07-11 (AVD testphone via run.sh, debug build, pt-BR dark; screenshots in
session scratchpad). RULE CLARIFIED by Allan: only the REAL Redmi is deferred until 1.0 —
emulator verification is expected EVERY batch (the 07-09→11 "off-device" readings were wrong).
- Set-row weights: check/✗ each in own cell, spread across row, header aligned ✓
- Tab switch: horizontal slide + parallax captured mid-frame via screenrecord ✓
- Archive > Treinos: FAB + same 3-option overlay; import-as-is moved Pernas to archive
  ("Adicionar ao plano" appeared), re-linked back — round-trip clean ✓
- Archive > Planos: FAB + full new-cycle dialog (reactivate entry correctly absent there) ✓
- Active FAB: deactivated Teste → FAB reappears → "Repetir um ciclo arquivado" → picker →
  Teste reactivated, workouts intact ✓
- Muscle titles without "(experimental)" ✓ (Ativos + workout view)
- Cadence overlay: 4 boxes prefilled, ▲/▼ steppers, OK same row; session write-through
  5-0-2-0 → reverted; edit correctly triggers the keep-vs-one-time prompt at session end ✓
- Session cadence AFFORDANCE FIX after Allan feedback mid-session: the big bare tempo text
  didn't read as tappable and had no (i) — replaced by the editor's exact row (centered
  outlined pill "Cadência: 4-0-2-0" / "Editar cadência" + info (i) opening the legend).
  Verified on emulator. RULE recorded: tappable = container/icon, bare Text never;
  "mirror X" = whole pattern incl. (i).

Drawn-body asset hunt (Allan asked status): xray body still in place. Best candidate found:
melihcolpan/MuscleMap (MIT) — flat segmented vector body, friendly, per-muscle paths, but
SwiftUI path data (port = convert paths to Android). Runner-up: HichamELBSI/
react-native-body-highlighter (MIT, 249★) — same flat style, SVG path strings in TS, easily
converted to per-muscle overlay SVGs feeding the EXISTING BodyMap pipeline; muscle slugs need a
map to wger ids. Neither is wger-style line-art; both beat the xray on friendliness. Decision
+ port = own session; screenshots shown to Allan 2026-07-11. → Allan picked react (blue fits
the app); PORTED same day, see Phase 13.

## Phase 13 — Allan feedback on Phase 12 + body port (2026-07-11 evening)

- [x] Session set row: kg·total column widened (weight 3.4→4.2 + 6dp side padding), play cell
      slimmed (0.9→0.6), check 0.8 — closes the gap Allan disliked between goal and check/x.
- [x] Nav transitions replaced by Material SHARED-AXIS X (300ms, ~30dp slide = width/13,
      90ms fade-out then 210ms fade-in): two opaque screens never overlap — this was the
      "clunky/overlapping" complaint with the full-width slide. Same spec on the NavHost AND
      the bottom-nav tab AnimatedContent (helpers sharedAxisEnter/Exit in MainActivity).
- [x] Plan editor: Add-workout button moved above the workout list (below weeks box).
- [x] ReactivateCycleDialog: LazyColumn + 420dp height cap (scales to dozens of cycles).
- [x] Archive > Cycles create now asks "Activate the new cycle?" [Activate / Keep in archive]
      (PendingCycle holder; createBlankPlan/createWizardPlan gained activate: Boolean).
- [x] Unique names per kind (cycle / workout): collision appends "MM/yy", then "(2)"… —
      PlanRepo.uniqueName/uniquePlanName/uniqueWorkoutName; applied to blank+wizard cycle
      create (wizard batch-aware), editor from-scratch (createWorkout), createArchivedWorkout,
      copyWorkout. RENAME enforcement done 2026-07-11 (see Phase 14) — the "future item"
      label here was wrong; it was always part of the plan.
- [x] Archive: "last trained" on workout rows AND plan rows (planLastTrained = max over member
      workouts via new allPlanWorkoutsFlow + planWorkoutIds map).
- [x] Archive expandable rows (chevron right): plan rows expand to member-workout buttons
      (open workout view); workout rows expand to containing-cycle buttons (open plan editor)
      or "No cycles linked". ExpandChevron in ActiveArchiveScreens.
- [x] BODY MAP PORTED to react-native-body-highlighter art (MIT, vendored TS + LICENSE +
      tools/body-highlighter/convert.py which regenerates assets/body/*.svg): flat vector
      body, muscles tinted BLUE by load (Blue 200→800; matches app accent — Allan's pick over
      MuscleMap's orange). Slug→wger-id approximations: serratus(3)→obliques,
      brachialis(13)→forearm, soleus(15)+gastrocnemius(7)→calves, lats(12)→upper-back.
      viewBox 724x1448 per view (back offset x+724). Settings gains body_attribution line.
      EMULATOR-VERIFIED: blue body renders front+back with sensible loads on the Teste cycle.

INCIDENT (2026-07-11, owned by Claude): tap automation mis-created 2 junk wizard cycles ("a")
with 6 empty duplicate workouts, and during cleanup a coordinate tap on a reshuffled list
deleted the REAL "Pernas" workout (2 exercises, never trained — no session history lost).
The 07-09 export in emulator Downloads predates its exercises, so the 2 exercise entries are
unrecoverable; Pernas recreated empty in the Teste cycle. Junk cycles/workouts deleted.
LESSON (also in memory): on destructive UI automation, re-screenshot and verify the exact
target before EVERY tap — list positions shift after each mutation.

## Phase 14 — rename uniqueness + set-row polish (2026-07-11 night; EMULATOR-VERIFIED)

- [x] Unique-name rule now enforced on RENAME (was mislabeled "future item" in Phase 13 —
      it was always in scope): new DAO queries planNamesExcept/workoutNamesExcept (a name
      only collides with OTHER rows of the same kind); PlanEditorViewModel.finalizeName +
      WorkoutEditorViewModel.finalizeName dedupe the final name on exit (silent, same
      "MM/yy" → "(2)" scheme as create). Plan editor: back arrow + new BackHandler both
      route through finalizeName (renames persist per keystroke there). Workout editor:
      Save button + keep-changes exit route through it (discard path needs none — it
      restores the opening snapshot).
- [x] Workout rename UI existed nowhere (renameWorkout was dead code since the editor
      rework) — added a "Workout name" OutlinedTextField as the first editor list item,
      mirroring the cycle-name field in the plan editor; hidden in single-exercise
      quick-edit from a running session (focusExerciseId != null). Renames flow through
      edit{} so they're undoable.
- [x] Set-row polish (Allan mid-session): TintedDropdown (set-type letter + Rep/Sec unit)
      raised 40→56 dp to match the OutlinedTextFields sharing the row; each set (details +
      cadence rows) wrapped in ONE 12dp-rounded outlineVariant border so a set reads as a
      unit; row weights rebalanced (target 1.6→1.4, rest 0.8→1.0) after the border padding
      clipped "90" in the rest field.
- [x] EMULATOR-VERIFIED (AVD testphone, debug, pt-BR dark; screenshots in session
      scratchpad): workout "Pernas"→"Empurrar (Push)" saved as "Empurrar (Push) 07/26"
      (list + DB), rename back to "Pernas" passes untouched; cycle "Teste"→"CicloB" backed
      out as "CicloB 07/26" (DB), restored; bordered set rows + equal-height controls + "90"
      unclipped; name field renders; bonus re-verified: archive-Planos FAB → new-cycle
      dialog → "Ativar o novo ciclo?" prompt → keep-in-archive; archive plan row
      Ativar/trash/chevron; plan delete confirm. Test cycle CicloB deleted; DB back to
      pre-test state.
- [x] Phase-10 retro-mark audit (Allan questioned it): all 7 Active/plan-management items
      confirmed present in code (ArchiveHubContent, AddWorkoutScreen, archiveWorkoutFully,
      deactivateAllPlans, AppBottomBar/showBottomBar, per-row ▶) — the unchecked boxes were
      stale doc, the features shipped in 09c/09e.
- Known minor: WorkoutViewScreen title shows a stale name right after an editor rename
  (name loaded once, not collected); refreshes on re-entry. Polish later.
- DEMO DEBT unchanged (deferred by Allan).

## Phase 15 — Allan feedback batch (2026-07-11 late; EMULATOR-VERIFIED same session)

- [x] First-load pop-in (perf): every BodyMap/session-image composition built its OWN Coil
      ImageLoader, so each screen's first visit re-decoded the body SVGs from scratch.
      New ui/common/AppImageLoader singleton (SVG + GIF decoders, 150ms crossfade) shared
      by BodyMap + session image slot — one memory cache process-wide; late arrivals fade
      instead of popping.
- [x] In-session ℹ button relabeled "Info & notes" (info_note, 3 langs) — it merged
      description/video AND note-taking without saying so.
- [x] Muscle map "Switch model" (switch_model, 3 langs) TextButton on the legend row:
      toggles male/female body, persisted (Settings.bodyFemale). Female path data vendored
      (bodyFemaleFront/Back.ts, same MIT repo); convert.py regenerates BOTH sets
      (female = -f suffix, viewBoxes from SvgFemaleWrapper: front -50 -40 734 1538,
      back 756 0 774 1448). Male SVGs byte-identical to before. Verified: toggle swaps to
      female with correct blue overlays front+back, toggles back.
- [x] WorkoutViewScreen: when a session is RUNNING, an outlined "End workout" button sits
      under "Resume workout" → green-Save / red-Discard dialog (mirrors the session's "…"
      dialog); VM.endRunningSession mirrors SessionViewModel.endSession (books timers, stops
      TimerService, drops drafts); Save → summary (new onSummary nav). Verified: discard
      ends the session, button row flips back to "Start workout", session row DISCARDED in DB.
- [x] Session top bars: "≈" dropped; small "estimated time" caption under the clock line
      (estimated_time, 3 langs; list + pager bars).
- [x] Session exercise list: caption "Tap an exercise to log its sets" (tap_to_log_sets,
      3 langs) above the rows; per-row ✎ replaced by a chevron-down opening the shared info
      sheet (image + description + video + note — sheet now resolves the exercise by id, not
      pager index, and gains an image slot via extraContent); End-workout button raised off
      the gesture area (navigationBarsPadding + 28dp). All verified on emulator.
- [x] Fast scrollbar app-wide (ui/common/FastScrollbar.kt): thin 3dp thumb always visible
      when content overflows; grows to 10dp draggable handle while scrolling/touching;
      pressing the track above/below the thumb creeps steadily in that direction (never
      jumps). LazyScrollbar/ColumnScrollbar overlays + ScrollbarLazyColumn/ScrollbarColumn
      drop-in wrappers. Applied: main tab content, exercise library results, Active/Archive
      lists, AddWorkout screen, plan editor, workout editor, workout view, session list +
      sets table, Settings, Summary, Bodyweight + Progression screens. Verified: library
      "a" search (hundreds of rows) thumb-drag jumped A→E instantly.
- [x] Polish: WorkoutViewScreen title no longer stales after an editor rename (planDao
      .workoutFlow + stateIn replaces the one-shot load).
- Compile + unit tests + assembleRelease green. Test session discarded; DB left clean.
- DEMO DEBT unchanged (deferred by Allan) — now also behind on: body-model switch, session
  list rework, scrollbars, end-from-view.

## Phase 15b — Allan corrections on 15 (2026-07-11 ~23:00; CHECKPOINT at 94% session usage)

COMMITTED (part 1, pushed earlier this session): stats cards slimmed (bodyweight = current
weight + 30d delta, graph only on detail page; progression = one line, averages block moved
to detail top), archive-create "Swap" wording when a cycle is active, clock caption
right-aligned + renamed "elapsed / estimated" (3 langs), sheet-image rules (pager top-bar
sheet = no image; workout view active+archived = image), workout-view start/end gap 2dp +
tap-for-details caption, scrollbar idle fade (alpha 0.15) + edgePadding wired at every site
so the bar hugs the screen edge (clears the set-delete ✕).

COMMITTED (part 2, 2026-07-12 session: compile fixed — missing `setValue` import in
ExerciseImageGallery.kt — then compile + unit tests green, committed + pushed):
- Library rework: filters now an inline FilterPanel dropping from the search bar (opens on
  search-field focus; old ModalBottomSheet removed); Search = filled Button, Custom
  exercises = OutlinedButton; result images hidden behind per-row chevron (default
  collapsed, no load pop-in). ExerciseRow gained showImage/onToggleImage.
- DB v7 (MIGRATION_6_7): exercise_user_image + exercise_image_pref tables (+ DAO flows +
  full-backup fields userImages/imagePrefs).
- ui/common/ExerciseImageGallery.kt: rememberExerciseImages (wger + user photos, pref
  wins), rememberUserImagePicker (PickVisualMedia → copy to filesDir/exercise_media →
  insert + prefer), ExerciseImageGallery (◀ ▶ wrap-around pager, "Choose an image" caption,
  last page = big circular + w/ "Add your own image reference"; last-viewed image becomes
  the representative). Wired into: session sheet (only when descriptionWithImage), workout
  view sheet, editor sheet (wgerPath resolved in-component); session pager image slot now
  shows the representative image, or the big centered + when the exercise has none.

15b CLOSED 2026-07-12 (EMULATOR-VERIFIED, AVD testphone, pt-BR dark, screenshots in session
scratchpad; versionCode 5 / 0.5.0; testDebug+testRelease+assembleRelease green; committed+pushed):
- v6→v7 migration clean over real data. Library rework verified: FilterPanel drops on
  search-field focus, filled Buscar + outlined Custom exercises, per-row chevron expands the
  image (wger URL load), favorite star round-trip. Gallery verified: info-sheet gallery pages
  wger image ↔ add-page, photo-picker pick links + prefers a user image, session pager shows
  the representative image ("last viewed wins").
- Scrollbar "only-first-page" bug NOT reproducible — bar present on pager page 2 with
  overflow (pixel-scan proof). Real defect found instead: the thumb sat 13dp off the screen
  edge EVERYWHERE (left-aligned inside its 16dp touch box; the part-1 edgePadding only moved
  the box). Fixed: thumb right-aligned (align TopEnd) in FastScrollbar — verified idle at the
  edge + grows/brightens while scrolling.
- Buttons sweep: dialog confirm/cancel TextButtons kept (Material convention); icon'd
  TextButtons kept (satisfy the container/icon rule); upgraded the two bare-text sites —
  library "New custom exercise" and BodyMap "Switch model" → OutlinedButton.
- BUG found by the emulator pass + FIXED (DB v8, MIGRATION_7_8): templatesChanged flag and
  templateSnapshot lived only in VM memory, so a process death mid-session (HyperOS kill!)
  silently kept in-session plan edits — no keep-vs-one-time prompt, no restore. Now the
  pre-edit snapshot is persisted as session.templateSnapshotJson on the FIRST in-session plan
  edit, recovered on resume, cleared at session end (both end paths). Verified: add set →
  am kill → resume → end → "Manter alterações no plano?" appears; Descartar restored 3 sets
  (DB-checked), snapshot column cleared. v7→v8 migration clean.
- Emulator DB repaired after tests (junk sets + test image rows removed via run-as sqlite;
  Voador back to 3×10; test photo deleted).
- OPEN (small): no UI to remove/unlink a user gallery image once added — needs a delete
  affordance on the gallery add/manage page someday. Demo debt still deferred.

## Phase 16 — exercise-image/GIF dataset research (DONE 2026-07-12, research only)

Question: a free dataset in the 3D-body-orange-muscle style (MuscleWiki/Gymvisual look)?
Answer: NO genuinely open one exists — that style IS Gymvisual's commercial catalog.

- yuhonas/free-exercise-db: Unlicense (public-domain claim), 800+ exercises, JSON w/
  primary/secondary muscles + instructions, 2 static JPEGs each (~850x567, ~70 KB → ~115 MB
  total, download-on-add only). Style = real-gym PHOTOS (sample checked), NOT the wanted 3D
  style; photos look like bodybuilding.com shots — provenance dubious despite the license
  claim. Name-based wger mapping feasible (pt-aliases-style fuzzy match). NOT recommended.
- hasaneyldrm/exercises-dataset (1,324 GIFs) + ExerciseDB/Kaggle (1,500+): EXACTLY the wanted
  style, but the media is (c) Gymvisual — that repo has special permission at 180x180 only;
  downstream reuse needs YOUR OWN Gymvisual license. Every other repo with these GIFs is
  pirating them.
- MuscleWiki itself: proprietary videos, no open dataset/API.
- Gymvisual direct (gymvisual.com): royalty-free per-item purchase — GIFs ~$0.90/item (10+),
  illustrations <$0.75, videos ~$6; pack discounts on request. Buying GIFs only for the
  exercises Allan actually trains (~30-50 => ~$30-45) is the legit path to that style.
- RECOMMENDATION: keep wger art as the base; if Allan wants the 3D style, buy the handful of
  Gymvisual GIFs he uses — the v7 gallery (user images + pref, GIF-capable Coil loader)
  already lets them be attached per exercise with ZERO new code (gallery + button). A bulk
  importer would only make sense after a pack purchase; fuzzy name→wger-id mapping is proven
  (pt-aliases) if that day comes.

Sources: github.com/yuhonas/free-exercise-db, github.com/hasaneyldrm/exercises-dataset,
gymvisual.com, github.com/exercemus/exercises (MIT list curated from wger/exercises.json).

## Phase 17 — Allan feedback batch (2026-07-12 late; EMULATOR-VERIFIED same session,
screenshots in session scratchpad; testDebug+testRelease+assembleRelease green)

- Library: top-bar filter icon (dead — the panel opens itself on search-field focus) replaced
  by a SEARCH icon that runs the search; inline Buscar button kept. Verified: "remada" via
  top-bar icon → results, panel closes.
- Workout editor set rows: details row is now Alignment.Bottom — the floating kg/×/s labels
  make the OutlinedTextFields taller than their boxes, so CenterVertically floated the
  56dp TintedDropdowns a few px above the field bases (Allan's diagnosis was right).
- Editor set-row corners: exercise Card now uses colorScheme.background (both themes) and the
  drag Surface is transparent + 12dp-rounded — no rectangular fill peeking past the rounded
  set borders; 6dp gap added between sets. Verified dark AND light.
- Statistics: bodyweight current kg + 30d delta and the Progression "Sessões: N" line are
  now centered (numbers-only variant Allan asked to see).
- In-session exercise list: chevron-down (promised an expand) swapped for outlined (i) —
  it opens the info sheet.
- MOVEMENT ANIMATIONS (yuhonas free-exercise-db, Unlicense): assets/fed_map.json maps
  wger ids → fed slugs (tools/fed-map/generate.py; EXACT normalized-name matches only,
  92/818 — fuzzy tiers produced wrong-variant pairs, a wrong movement is worse than none).
  data/FedMedia.kt downloads the 2 start/end photos on demand (all-or-nothing) into
  exercise_media/fed_*.jpg. Gallery gains a Movement page between stills and the add-page:
  download button → 2-frame animation at 1 s per frame with a play/pause overlay
  (default playing; pause verified to freeze). Verified on Voador Peitoral → "Butterfly".
- LATENT BUG FIXED on the way: MediaStore.sweep deleted every file in exercise_media whose
  name wasn't a referenced exercise id — that included ALL v7 user gallery photos
  (user_*.jpg) and would have eaten fed_* frames. Sweep now keeps user-image paths (DB) and
  fed_-prefixed files.
- DEMO DEBT unchanged (deferred): now also behind on this batch.

## Phase 18 — Allan feedback (2026-07-12 night; EMULATOR-VERIFIED same session,
screenshots in scratchpad; testDebug+testRelease+assembleRelease green)

Editor (collapse + chrome):
- Exercises COLLAPSIBLE: chevron toggle in the header (the old move-arrows' spot); default =
  first exercise open, rest closed (expandedIds, null = default; single-exercise quick-edit
  always expanded). Collapsed card = header only (checkbox/name/(i)/chevron).
- Move up/down relocated to the Total/Per-dumbbell/Per-side line, icons now FULL arrows
  (ArrowUpward/Downward) — arrowheads mean collapse/expand exclusively.
- Exercise card got a 1dp outline border (background stays = screen, both themes);
  drag Surface already transparent. Verified dark; 10-exercise workout now ~1.5 screens.
- Bottom-nav taps now respect the editor's unsaved-edits guard (NavExitGuard single-slot
  handler + rememberUpdatedState so the registered handler reads CURRENT dirty): tab tap →
  "Manter alterações?" → Descartar reverted the added set (DB-checked) and navigated on.

Free Exercise DB as a second searchable database (Allan: fed images not "rolled back" —
the movement page only existed on the 92 exact-matched wger exercises):
- Library FilterPanel gains "Database": wger | Free Exercise DB (one at a time). fed source
  searches assets/fed_index.json (873 entries, generated by tools/fed-map/generate.py with
  muscles PRE-MAPPED to wger ids; ~670KB). Language row hidden for fed (English-only).
- fed hits ride the normal ExerciseHit pipeline: muscle subtitles localized, (en) marker,
  favorites, injured-muscle filter, chevron expands the REMOTE image (download-on-demand,
  Coil disk cache).
- First engagement (detail open or add-to-workout) imports the exercise as a regular row
  id "fed:<slug>" (FedIndex.ensureImported) — notes/links/sessions/body map all work.
  Backup: customExercises() now also exports fed rows (+ WgerSync's snapshotExercises
  excludes them). MediaStore.ensureImage allows the fed raw.githubusercontent host, so
  add-to-workout persists the image offline as usual.
- Movement page now works for ALL fed exercises (FedMedia.slugFor resolves fed: ids
  directly) and the library detail sheet gained the shared gallery (incl. movement download)
  + correct attribution line (free-exercise-db public domain vs wger CC-BY-SA).
- Frame cache policy (Allan): fed_ frames swept after 2 DAYS unless the exercise is
  referenced by a workout (then permanent). Verified: fed search "curl"/"hammer", remote
  row image, detail import, movement download + animation + pause, all in pt-BR dark.

INCIDENT (owned by Claude, 2nd offense): blind coordinate taps around the t12 discard
dialog earlier tonight deleted Cross-over's FAILURE set (id 17) and added a junk set.
Repaired from a pre-batch DB copy (F set restored verbatim, junk removed, DB verified).
Rule re-affirmed: screenshot-verify EVERY tap target, dialogs included — no replaying
coordinates from a previous dialog instance.

Allan's fuzzy-matching pushback (names describe equipment/position/movement): true, but the
0.8-Jaccard tier still paired variants that differ by exactly one qualifier ("Incline Close
Grip Barbell Bench Press" → "Close-Grip Barbell Bench Press"; "Barbell Reverse Wrist Curl" →
"Reverse Barbell Curl"). With fed searchable directly, the map only feeds wger-exercise
movement pages — exact-match stays. Hand-review of the ~40 near-misses remains the safe
coverage path if wanted.

## Phase 19 — Allan feedback (2026-07-13 ~00:00; EMULATOR-VERIFIED same session;
testDebug+testRelease+assembleRelease green)

Filter panel UX (Allan's report):
- Panel header row w/ RETRACT arrow (˄) — collapsible any time, not only after a search.
- Panel body capped at 400dp with internal scroll + scrollbar: the Custom/Search row never
  gets shoved off screen again regardless of source section mix (wger vs fed).
- Re-tap reliability fixed: panel now opens on EVERY press of the search field via an
  interactionSource PressInteraction listener — onFocusChanged alone missed re-taps while
  the field was still focused after a search.
- BUG found while testing the new filters: DAO search LIMIT 400 + alphabetical ORDER BY
  truncated blank-query results before the letter R, so post-filters (favorites float was
  also affected in theory) could see a truncated set → "Rosca" filter returned zero.
  LIMIT raised to 2000 (818 wger exercises; the list is lazy anyway).

Structured name filters (Allan: names describe equipment / body position / movement):
- data/NameFilters.kt: canonical keys with en/pt/de token variants for EQUIPMENT (7),
  POSITION (7), PATTERN (14) + localized labels. Three dropdowns in the filter panel
  ("Name filters"); VM post-filters hits by token-matching the localized name. Works on
  both databases. Verified: Movimento=Rosca → only curls (incl. "Nordic Curl" via the
  en token).

Custom-exercise structured builder:
- CustomExerciseDialog: Movement/Equipment/Position dropdown slots (each with "Custom…"
  free-text mode + dropdown-icon to return to presets), optional Variant field (grip,
  angle…), auto-composed name in an EDITABLE field (stops auto-updating once hand-edited;
  pt order "Rosca Sentado com Halteres", en/de "Seated Dumbbell Curl"-style; bodyweight
  contributes no equipment word). Verified composition on emulator.

Auto-translation research (Allan's question): best fit = ML Kit on-device Translation
(com.google.mlkit:translate) — free, unlimited, offline after a one-time ~30MB model
download per language pair, en→pt/de supported. Alternatives rejected: DeepL/Google Cloud
(paid/API keys), LibreTranslate public instances (rate limits, availability), MyMemory
(5k chars/day). Plan if wanted: "Translate" button on description sheets → translate +
cache into exercise_translation as lang rows (marked machine-translated), model download
over Wi-Fi w/ consent. NOT implemented yet — awaiting Allan's go.

Allan note: emulator data is expendable during testing — blind taps acceptable (bug-finding
value); keep the pre-session DB snapshot habit for cheap repairs.

## Phase 20 — Allan feedback (2026-07-13 ~12:40; CHECKPOINT — token limit hit mid-verification)

DONE + EMULATOR-VERIFIED:
- Filter panel phantom scroll space: root cause = scrollbar's fillMaxHeight stretched the
  unconstrained overlay Box (and the card) to the whole leftover screen while the content
  column capped at 400dp. Fix: heightIn(max=400dp) moved from the Column to the Box.
  Verified: panel now ends flush after "Mostrar nomes alternativos".
- Filter panel scrollbar overlapped chips → ColumnScrollbar edgePadding=12.dp cancels the
  card padding; thumb hugs the card edge (verified mid-scroll screenshot).
- Custom dialog: NamePartSlot now reports the canonical key; Movimento pre-selects the
  muscle group via NameFilters.PATTERN_MUSCLE (press→chest, curl→biceps, row/pulldown/
  pull-up→lats, raise→shoulders, extension→triceps, fly→chest, squat/lunge→quads,
  deadlift→hamstrings, crunch/plank/twist→abs) until the user picks a chip by hand
  (muscleEdited flag). Verified: Rosca → Bíceps chip selected.
- Cardio toggle hides the whole muscle-group picker and forces muscleId=null on create.
  Verified both directions.
- Duplicate custom detection: createCustomExercise checks exerciseIdByName (case-
  insensitive, any language) first; on hit shows "Exercício já existe — usando o
  existente: X" and passes the EXISTING id to onAdd (picker mode adds it to the workout,
  no duplicate row). Verified with name "teste".
- Custom exercises no longer show the wger attribution line in the detail sheet
  (attribution only when !hit.isCustom; fed prefix keeps fed attribution). Verified.

- ML Kit on-device auto-translation (Allan approved, scope = name/description of
  UNtranslated exercises only): com.google.mlkit:translate:17.0.2; data/AutoTranslate.kt
  (ensure(db, id, lang): skips en/existing-lang rows, translates the en row's name+
  description, inserts machine=true row, 120s timeout, model download Wi-Fi only);
  DB v9 adds exercise_translation.machine flag; wired into ALL four description loaders
  (library openDetail, session openDescription, editor openDescription, workout-view
  openDetail — "check all searches" per Allan) with load→ensure→reload-if-still-open;
  ExerciseInfoSheet shows "Tradução automática (no aparelho)" label for machine rows.
  EMULATOR-VERIFIED 2026-07-13 (resumed session): fed "Cable Crunch" sheet in pt shows
  full Portuguese description + "Tradução automática (no aparelho)" label + machine pt
  alias in "Também conhecido como". The earlier "still English after 4 min" was just the
  one-time ~30MB en→pt model download in flight — a later sheet open picked the
  translation up exactly as designed (ensure() reruns per open). requireWifi KEPT
  (worked on emulator wifi; intended for the Redmi too). AutoTranslate failures are no
  longer silent: runCatching now logs to logcat tag "AutoTranslate" (was a blind
  getOrNull — the debugging dead-end this phase).
  MT-quality note: machine pt NAME can be comical ("Cable Crunch" → "Crise de cabo");
  description quality is fine. Names of en-only exercises keep the en display in lists
  (search hit lang); only the alias row exposes the machine name. Flag to Allan if it
  bothers him — could restrict MT to descriptions only.
- Unit tests (testDebug+testRelease) + assembleRelease GREEN 2026-07-13.

Open question answered in chat: gear-menu "update database" button — not worth it now;
wger snapshot + fed index are bundled at build time, media is on-demand; revisit at 1.0
with a real remote-index refresh.

## Phase 21 — Allan feedback (2026-07-13 afternoon; EMULATOR-VERIFIED same session;
testDebug+testRelease+assembleRelease green; committed+pushed)

Body map alignment (Allan: female front bigger, female back taller than male):
- Root cause measured: the library wrappers' viewBoxes give each view a different
  figure-share of the box (male 86% of height, female front 93%, female back 99%) while
  BodyMap lays views out width-first — so equal widths ⇒ different figure sizes.
- convert.py now computes each view's real content bbox (own SVG path-bbox parser, control
  points included; arcs' fused flags handled) and emits NORMALIZED viewBoxes: every figure
  fills 85.93% of height, 6.64% top margin, aspect 0.5. BodyMap.kt uses one BODY_ASPECT
  (0.5) for all views (per-sex aspect constants deleted). Emulator-verified male + female:
  heads level, feet on one baseline, equal heights.

Stats charts reworked (ui/stats/TimeSeriesChart.kt replaces PointAreaChart + windowed()):
- Range chips shortened to fit: 7d/1m/3m/6m/1y|1a|1J/All|Tudo|Alle (squished-right bug).
- X-axis ticks+date labels per range (Allan's spec): 7d = every day, today rightmost;
  1m = every 7 days back from today; 3m = monthly; 6m = 2-month steps; 1y = 3-month
  steps; Tudo = calendar years when data spans >2y, else 3-month steps.
- Y-axis dotted gridlines on round values, adaptive step (0.5→1→2→5→10…50k, ≤5 lines
  for the spread), value label on each line; integral values drop the ".0".
- Granularity chips (Diário/Semanal/Mensal): buckets by day / Monday-start week / calendar
  month; bodyweight averages per bucket (MEAN), volume sums (SUM). Bucket x = latest
  sample in bucket so the newest point stays at "now". Bodyweight defaults DAY,
  Progression defaults WEEK (a workout runs ≤3x/week).
- Horizontal pan back through the whole history: canvas stays screen-sized and remaps
  time on drag (no giant scroll layer / texture limit), window clamps to data; right
  edge starts at now, resets on range/data change.
- Progression screen: granularity row added; muscle cards now show whenever the series
  has any data (windowed() emptiness check gone — panning reaches old data anyway).
- Unit tests: TimeSeriesChartTest (12) — bucket day/week/month + MEAN/SUM, gridStep
  ladder, xTicks for all six ranges incl. year-labels vs quarterly fallback.
- Emulator-verified (81 seeded body_metric rows over 8 months left in the emulator DB —
  useful for future chart testing): chips fit, 1m ticks 15/6…13/7, 7d day labels with
  today rightmost, pan back 7→4/7, Tudo+Semanal weekly means w/ quarterly ticks + step-2
  gridlines, Progression weekly volume point.
- Follow-up (Allan, same day): y-axis now scales to the VISIBLE window only, padded one
  grid step top+bottom (extremes sit one line inside the edges) and rescales live while
  panning — a flat month inside a 20 kg history gets the 0.5 kg grid. Series drawing
  clipped to the plot band (off-window neighbors can map far outside the visible scale).
  Verified: 1m w/ 94 kg spike → step-5 grid 75–95; same data panned to a flat June week
  in 7d → single 79.5 line (spread < 0.5). NOTE: emulator body_metric rows for 7/8–7/13
  reverted to pre-seed values between runs — SOLVED: Allan edited them by hand in the
  open emulator (not a WAL problem; seeding procedure was fine).
- Follow-up 2 (Allan): near-flat window showed a single gridline — yTop now extends so
  the bottom line always has ≥2 lines above it (min 3). Verified: flat June week shows
  79.5/80/80.5.
- Follow-up 3 (Allan spotted live): off-window neighbor points (they anchor the
  entering/exiting line segments) are excluded from the y-scale but their DOTS could
  still render at the canvas edge far off-scale — dots now draw only for in-window
  points (lines still enter/exit, clipped). yTop additionally guarantees one gridline
  ABOVE the max visible value. Verified: spike window 93–94.5 w/ top line above max;
  off-window 79 kg point enters as clipped line from below, no phantom edge dot.
