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

## Phase 2 — plan editor + wizard (next)

- [ ] Plans CRUD: create/rename/activate/deactivate/delete, cycleWeeks + startedAt
- [ ] "Suggest a split" wizard: days/week (1-7) + cycle weeks → named workouts with weekdays (7d: 4-5 split + 2-3 cardio/physio days; deload banner at cycle end)
- [ ] Workout editor: add exercises from library (+ button), set templates (type/weight/reps-secs/rest), bulk rest apply, weight mode per exercise
- [ ] Custom exercise creation
- [ ] Home shows today's workouts; Active/Inactive tabs list plans
- [ ] Bottom bar hidden while editing

Phases 3–7: see IMPLEMENTATION_PLAN.md §7.
