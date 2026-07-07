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

## Status: Phases 0-7 complete (Play Store excluded). Pending real-device test on the Redmi.

Phase 7 EMULATOR-VERIFIED 2026-07-07 (AVD testphone, debug build, pt-BR; screenshots in session scratchpad):
- pt batch: search "testa" → 5 "Tríceps Testa …" generated names; still present after wger sync (re-merge works)
- Injured muscles: Tríceps chip in Settings → same search 0 results; filter-sheet override toggle restores them; chip state persists
- Suggestions: ✨ in workout editor → focus dialog (11 options) → Push filled 4 exercises (2 chest + 2 shoulder-tagged), NO tríceps (injured respected), images auto-downloaded
- Prev/next buttons: toggle in Settings → chevron row under pager, left disabled on page 1, right navigates + story bar advances
- wger refresh: real sync OK — "Atualizado: 818 exercícios, 1511 traduções", button disabled while "Atualizando…"
- Media sweep: on app restart planted orphan wger_99999.jpg deleted, 4 referenced files kept
- NOT visually verified (code-only): GIF playback (no .gif exercise encountered), scroll-collapsing image (test workout tables too short to scroll)

Polish note: muscle "Obliquus externus abdominis" chip shows latin (wger ships no name_en for it) — add to MuscleNames map someday.
