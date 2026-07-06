# Workout App — Implementation Plan

Single-user offline-first fitness tracker for Android.
Owner: Allan. Target device: Redmi 15 Pro 5G, HyperOS 3.0.301.0 (Android 16 base).

Decisions locked 2026-07-06:

| Area | Decision |
|---|---|
| Stack | Kotlin + Jetpack Compose (Material 3), native Android |
| Exercise data | wger database snapshot, bundled offline (CC-BY-SA attribution required) |
| Distribution | Sideloaded APK (signed release build, installed via file manager) |
| Formats | Workout plans: JSON. Training history/stats export: CSV. Full backup: JSON |
| Languages | English, Brazilian Portuguese (pt-BR), German |

---

## 1. Tech stack & project setup

- **Language/UI:** Kotlin 2.x, Jetpack Compose, Material 3, dark + light theme.
- **Architecture:** MVVM. `ui/` (Compose screens + ViewModels), `data/` (Room DAOs, repositories), `domain/` (use cases only where logic is non-trivial: stats, import/export, session recovery).
- **Persistence:** Room (SQLite). Settings in Jetpack DataStore (language, weight-display mode, height, flags).
- **Serialization:** `kotlinx.serialization` for plan JSON; hand-rolled writer for CSV (no dependency needed).
- **Images:** Coil for loading; downloaded exercise images/GIFs stored in app-internal storage, resized to screen width before saving (see §6).
- **Charts:** Vico (Compose-native) for bodyweight/volume progress graphs.
- **Navigation:** Compose Navigation, single activity.
- **SDK levels:** `minSdk 29` (Android 10 — allows a future hand-me-down device), `targetSdk 36` (Android 16, matches HyperOS 3).
- **Build:** Gradle version catalog, JDK 17, `./gradlew assembleRelease` produces a signed APK (keystore checked into a private location, NOT the repo; see MAINTENANCE.md).
- **No internet permission needed for core use.** `INTERNET` permission is declared but used only for: initial/updated wger sync, image download when adding an exercise, YouTube link opening (delegated to browser).

## 2. Data model (Room)

```
Exercise            id (wger id or local uuid), primaryMuscleId, secondaryMuscleIds,
                    equipment, isCustom, isCardio, imagePath?, videoUrl?, licenseAttribution
ExerciseTranslation exerciseId, languageCode (en|pt-BR|de), name, aliases[], description,
                    formCues
MuscleGroup         id, nameEn/namePt/nameDe, parentId? (sub-muscle support)
Plan                id, name, isActive, createdAt
Workout             id, planId, name, orderIndex, daysOfWeek[]  ← one workout, N weekdays
WorkoutExercise     id, workoutId, exerciseId, orderIndex, note,
                    weightMode (TOTAL|PER_DUMBBELL|PER_SIDE), barWeightKg (default 20)
SetTemplate         id, workoutExerciseId, setIndex, type (WARMUP|NORMAL|FAILURE|DROP|SUPERSET),
                    targetWeightKg, targetValue, valueUnit (REPS|SECS), restSecs
Session             id, workoutId, startedAt, endedAt?, status (RUNNING|FINISHED|DISCARDED|AUTO_ENDED)
SetLog              id, sessionId, workoutExerciseId, setIndex, type, weightKg, value, valueUnit,
                    activeSecs?, restSecs?, completedAt
ExerciseNote        exerciseId, sessionId?, text        ← memo button during workout
BodyMetric          date, weightKg; height stored once in DataStore (editable)
```

- Previous performance loading: when a session starts, prefill each set's weight/value from the most recent `SetLog` for that `WorkoutExercise`, falling back to `SetTemplate`.
- Supersets: consecutive `SetTemplate`s marked `SUPERSET` are visually grouped; no rest timer between them.
- Cardio: `isCardio` exercises default to `valueUnit = SECS`; a workout may be all-cardio.

## 3. Exercise database & languages

- **Bundled snapshot:** ship a pre-packaged Room DB built at development time from the wger public API (`exercises`, `translations` for en/pt-BR/de, `muscles`, `equipment`). App works fully offline from first launch.
- **Sync (optional, explicit):** a "Refresh exercise database" button in Settings hits `https://wger.de/api/v2/` only when tapped. Safety: HTTPS only, response size cap (10 MB), `kotlinx.serialization` strict parsing (unknown fields ignored, malformed → abort whole sync, keep old data), no HTML rendered (descriptions stripped to plain text), images fetched only from wger's own hostnames.
- **Custom exercises:** user-created, stored with `isCustom = true`, translatable manually, exportable in backup, never overwritten by sync.
- **Language behavior:**
  - App UI strings: standard Android resources (`values/`, `values-pt-rBR/`, `values-de/`). First launch = system locale (fallback en); flag button on home screen top bar cycles/chooses language, persisted in DataStore via `AppCompatDelegate.setApplicationLocales`.
  - Exercise search defaults to current app language. Filter sheet contains: muscle group chips **and** a "search language" selector (en/pt-BR/de/all).
  - "Show alternate names" — discrete toggle inside the filter sheet; when on, result rows show a second line with names in other languages + same-language aliases.
- **Physio/injury features:** schema supports them now (secondary muscles, `MuscleGroup.parentId`); UI deferred to Phase 7 (injured-muscle toggle excluding primary+secondary matches; physiotherapy exercise category if a usable open dataset is found — none is bundled at v1).

## 4. Screens

### 4.1 Home
- Top bar: app name, language flag button, settings gear.
- "Today" section: workouts whose `daysOfWeek` contain today, from active plans.
- Below: all active plans (collapsible), tap → plan detail.
- Bottom navigation (4 tabs): **Home · Active plans · Inactive plans · Statistics**. Hidden during workout editing and during a running session.
- Every tappable surface uses Material ripple (your "color feedback everywhere" requirement — comes free with Compose `clickable`, enforced in a shared modifier).

### 4.2 Plan editor / exercise picker
- Top row: back button · search field · filter button.
- **Deferred search:** typing does NOT query. A "Search" button appears under the field once text is non-empty (plus keyboard IME search action). Rationale honored: no per-keystroke DB scans. (Room FTS4 index makes even live search cheap later if you change your mind.)
- Filter sheet: muscle-group chips (browse all exercises of a group with empty query), search-language selector, alternate-names toggle, "custom only" toggle.
- Result row: name (+ alt names line if enabled), muscle tag, `+` button to add; tapping the text opens exercise detail (description, form cues, image/GIF, YouTube link).
- After adding: per-exercise config — sets list (type, weight, reps/secs), rest timer per set with "apply to all sets" bulk action, weight mode (total / per dumbbell / per side, bar default 20 kg editable).
- Workouts within a plan: name each, assign weekdays (multi-select), reorder.
- **Plan wizard (decided 2026-07-06):** new plans start empty, but a "Suggest a split" wizard asks
  (a) training days per week (1–7) and (b) cycle length in weeks. It proposes a split structure —
  e.g. 7 days: 4–5 muscle-split days + 2–3 cardio/physio/core days; fewer days: full-body or
  upper/lower variants — as named empty workouts with assigned weekdays that the user fills with
  exercises. `Plan.cycleWeeks` + `Plan.startedAt` let the app count weeks and banner a suggested
  deload week at cycle end (reminder only, no automatic changes).
- **Rest-timer alert (decided):** vibration + sound on countdown end, works screen-off via the
  foreground service; toggleable in Settings.
- Back button always closes the topmost overlay/sheet first; only navigates back when nothing is overlaid (global rule, implemented via `BackHandler` priority — applies to every screen).

### 4.3 Workout view (not started)
- Top: back arrow · **Start Workout** · Edit training.
- Compact exercise list: "3 × 10 reps · 40 kg".
- Tap exercise → detail page (form description, image/GIF from local storage, YouTube link).
- **Start** converts button into elapsed-time display. `startedAt` written to DB immediately → crash-proof.

### 4.4 Session (workout running)
- Layout top→bottom: back (to exercise list) · note-edit icon · "⋯" menu (End & save / End & discard, both with confirmation) → image/GIF (~1/5 height, collapses on scroll, reappears on scroll-up past table) → story-style progress bar (one segment per exercise) → exercise name → weight-mode indicator → sets table (only the table scrolls if too tall) → bottom row: clock icon (toggle timer panel) · **Log set** button.
- Sets table columns: set type dropdown · weight (editable, "kg" suffix) · value (editable) · unit dropdown (Reps/Secs) · play button (Secs → countdown of set duration; Reps → stopwatch) · checkmark (gray→green).
- Number editing overlay: ±1.25 / 2.5 / 5 / 10 / 15 / 20 buttons + direct numeric input, OK/Cancel; system back closes overlay without saving.
- **Log set:** marks checkmark green, writes `SetLog`, opens timer panel, auto-starts that set's rest countdown. Tapping the checkmark directly does the same.
- Timer panel (bottom sheet): countdown (rest or timed set) + independent stopwatch; pause / stop-reset.
- Time accounting per session: **active** = sum of set execution times (timed sets exactly; rep sets measured stopwatch time when used, else estimated 3 s × reps), **rest** = sum of rest-timer elapsed, **idle** = wall-clock − active − rest, floored at 0.
- Navigation between exercises: swipe left/right (HorizontalPager); optional small side/bottom prev-next buttons (Phase 6 polish, behind a setting). Swipe past either end → exercise list.
- **Foreground service** with persistent notification keeps stopwatch/rest timers alive when screen locks. HyperOS note: app must be exempted from battery optimization and allowed "autostart"; first-run dialog walks the user through both (HyperOS kills background apps aggressively).
- **Crash recovery:** on app start, any `Session` with `status = RUNNING` resumes with elapsed = now − `startedAt`. If > 5 h elapsed → mark `AUTO_ENDED`, keep logged sets, generate stats.

### 4.5 Session end & statistics
- End (from list page or ⋯ menu) → confirmation → stats page: per-muscle total volume (Σ weight × reps, attributed to primary muscle; per your example 3×10×1 kg with 2 chest + 1 quad sets → 30 kg total, 20 chest, 10 quads), active/rest/idle breakdown, close → home; back → the finished workout in view mode.
- Statistics tab: averages of all tracked metrics, bodyweight-over-time graph, total-volume-over-time graph (per muscle filter), session duration trend. Height entered once, editable in Settings; bodyweight quick-add on the stats page.

## 5. Import / export

- **Plans: JSON** — schema defined in `WORKOUT_PLAN_GENERATOR.md` (source of truth; app and browser-Claude both follow it). Import via Android document picker (`ACTION_OPEN_DOCUMENT`) or share-to-app intent; export via `ACTION_CREATE_DOCUMENT`. Importer validates against schema, resolves exercises by wger ID → exact name match (any language) → offers to create as custom; never crashes on bad input, shows a per-item error report.
- **History: CSV** — one row per logged set: `session_id, date, plan, workout, exercise_id, exercise_name, set_index, set_type, weight_kg, weight_mode, value, unit, active_secs, rest_secs`. Plus a second CSV for sessions (`session_id, workout, started_at, ended_at, status, active_secs, rest_secs, idle_secs, total_volume_kg`) and one for body metrics.
- **Full backup/restore: JSON** — everything (plans, custom exercises, logs, settings) in one file for phone migration.
- All file I/O through Storage Access Framework — no storage permissions needed.

## 6. Media handling

- When an exercise is added to any workout, its image/GIF downloads once (Wi-Fi or any connection, user-visible progress), is downscaled to ≤ 1080 px width / WebP, stored in `filesDir/exercise_media/`. Removed when no workout references it (ref-count sweep on plan save).
- No image → deterministic placeholder (muscle-group icon).

## 7. Phases & milestones

| Phase | Deliverable | Done when |
|---|---|---|
| 0 | Project skeleton, CI-less local build, signing config | Signed APK installs on the Redmi |
| 1 | Room schema + bundled wger snapshot + language switching | Search & browse exercises offline in 3 languages |
| 2 | Plan/workout editor incl. deferred search, filters, set templates, rest timers | Create the plan you actually train with |
| 3 | Session engine: table, logging, timers, foreground service, crash recovery, swipe nav | Full workout tracked with screen off periods |
| 4 | Stats: end-of-session page, statistics tab, bodyweight graphs | Numbers match a hand-calculated session |
| 5 | JSON plan import/export, CSV history export, full backup | Browser-Claude-generated plan imports cleanly |
| 6 | Polish: alternate names UI, image pipeline, HyperOS battery onboarding, prev/next buttons setting, dark theme pass | Daily-driver quality |
| 7 | Later: injured-muscle exclusion toggle, physio category, auto workout suggestions, optional Play Store | — |

Build order rationale: session engine (3) is the highest-risk piece (timers + process death + HyperOS killing), so it lands before any cosmetic work.

## 8. Risks / notes

- **HyperOS background killing** is the #1 practical risk for rest timers — mitigated by foreground service + battery-exemption onboarding; test with screen locked 10+ min.
- wger pt-BR coverage is thinner than en/de; missing translations fall back to English with a subtle "(en)" marker. Claude can batch-generate missing pt-BR names later as custom aliases.
- CC-BY-SA: exercise descriptions from wger require attribution — shown in Settings → About and kept in `licenseAttribution` per exercise.
- Single-user app: no accounts, no analytics, no network calls without explicit user action.
