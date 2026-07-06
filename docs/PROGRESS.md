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

## Phase 3 — session engine (code done 2026-07-07, emulator verify PENDING)

- [x] SessionManager: wall-clock-instant timers (rest/set countdown/stopwatch, active+rest accumulators) — survive minimize/kill by design
- [x] TimerService (FGS specialUse): chronometer notification; live countdown notification via setChronometerCountDown when rest/set timer runs (Allan's request); vibrate+beep alert at countdown end, then reverts to session chronometer; POST_NOTIFICATIONS requested at session start
- [x] SessionViewModel: start/resume RUNNING session, prefill from previous logs, logSet (SetLog write, superset skips rest), active time = secs value | stopwatch | 3s/rep estimate, end save/discard
- [x] SessionScreen: list mode + HorizontalPager mode, story progress bar, image placeholder (media Phase 6), sets table w/ tap-to-edit NumberPad overlay (±1.25/2.5/5/10/15/20), checkmark gray→primary, timer panel, note dialog, ⋯ end menu with confirmations
- [x] WorkoutViewScreen (compact "3× 10 Reps", Start/Edit), SummaryScreen (total/active/rest/idle, volume per muscle via StatsCalc), auto-end >5h in WorkoutApp, Home resume card
- [x] Nav: home→view/{id}→session/{id}→summary/{sessionId}; summary back→view, close→main
- [ ] EMULATOR VERIFY (next session): start session→log set→HOME→`adb shell cmd statusbar expand-notifications`→countdown notification→alert→reopen→end→summary numbers; `adb shell am kill` resume test

## Phase 4 — statistics tab (next)

- [ ] Stats tab: averages (volume/session, duration, active/rest/idle ratios), bodyweight quick-add + graph, volume-over-time graph (Canvas or Vico)
- [ ] Height once in Settings (DataStore)
- [ ] Unit tests: StatsCalc (PER_DUMBBELL ×2, PER_SIDE bar+2×side), SplitWizard counts

## Phase 5 — import/export | Phase 6 — polish (media download, alt-name labels, HyperOS onboarding)

Phases per IMPLEMENTATION_PLAN.md §7.
