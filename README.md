# Workout

Single-user, offline-first Android workout tracker (Kotlin + Jetpack Compose + Room).
Built for Allan's Redmi 15 Pro 5G (HyperOS), sideloaded — no Play Store, no accounts,
no network needed after first launch.

## What it does

- **Plans → workouts → exercises → sets.** A plan holds workouts (e.g. Push / Pull / Legs),
  each assigned to weekdays. Home shows a Mon–Sun ring row (checkmark = finished session
  that day) and today's workouts.
- **Exercise library**: ~820 exercises from [wger.de](https://wger.de) (CC-BY-SA 4.0),
  bundled offline with images, en/pt-BR/de names, muscle filters, injured-muscle
  exclusion, plus custom exercises. Refreshable from wger in Settings.
- **Session engine**: wall-clock timers that survive app kills (foreground-service
  notification with live rest countdown), per-set logging with a number pad,
  prefill from your previous session, summary with volume per muscle.
- **Supersets**: mark an exercise "superset with previous" — the session alternates
  A1, B1 (no rest between), rest, A2, B2… and highlights the set you're on.
- **Rep ranges + progression hints**: sets have a target range (e.g. 10–12). When you
  hit the top of the range at the same weight two sessions in a row, a bubble suggests
  a weight increase (~2.5% upper / ~5% lower body, 1.25 kg steps); inside the range it
  suggests +1 rep. Tap to apply, ✕ to dismiss — never applied automatically.
  See `docs/PROGRESSION.md` for the sports-science sources.
- **Statistics**: session averages, volume-over-time and bodyweight charts.
- **Split wizard** and ✨ auto-suggestions (pick focus + how many exercises) to fill
  workouts from the library.

## Build & install

```bash
./gradlew test assembleRelease          # signed APK (keystore via ~/.gradle/gradle.properties)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Requirements: JDK 17+, Android SDK 36. Release signing reads the `WORKOUT_*` properties;
without them the release build is unsigned. Details in `docs/MAINTENANCE.md`
(includes emulator setup — enable host GPU or the AVD crashes/freezes).

## Import / export (Settings ⚙)

| What | Format | Notes |
|---|---|---|
| Import plan | JSON (schema v1) | Matches exercises by wger id → name → alias, falls back to creating custom exercises; anything unresolvable is reported. |
| Export plan | JSON | Round-trips with import — share or edit a plan as text. |
| Export plan | PDF | Printable sheet: exercises, set types, weights, rep ranges, rests, superset links. |
| History | CSV ×3 | Sets, sessions, bodyweight — for spreadsheets or feeding back to an LLM. |
| Full backup | JSON | Everything incl. custom exercises; restore on a fresh install. |

## Creating a plan with an LLM

The app ships the generator contract (`docs/WORKOUT_PLAN_GENERATOR.md`) inside the APK:

1. Settings → **Create a plan with AI** → *Save instructions file (.md)*.
2. Give that file to any chatbot (Claude, ChatGPT, …) and describe your goal, days per
   week, equipment and injuries.
3. It returns a `plan_*.json`; save it and use Settings → *Import plan (JSON)*.

The same card has a **How it works** button with these steps in-app. The JSON schema
supports rep ranges (`value_max`) and superset links (`superset_with_previous`).

## Project layout

```
app/src/main/java/dev/allan/workoutapp/
  data/           Room entities & DAOs, DataStore settings, stats, media,
  data/           SuggestionEngine (✨ fill), ProgressionEngine (double progression)
  data/transfer/  Plan JSON import/export, CSV export, PDF export, full backup
  data/sync/      wger refresh; data/snapshot/ bundled snapshot + pt-BR aliases
  session/        SessionManager (wall-clock timers) + TimerService (FGS notification)
  ui/             Compose screens: home/plans/workout editor/session/stats/settings
docs/             IMPLEMENTATION_PLAN.md, PROGRESS.md (checkpoint log — start here),
                  WORKOUT_PLAN_GENERATOR.md (LLM contract), PROGRESSION.md, MAINTENANCE.md
tools/            wger snapshot fetcher, pt-BR alias generator
```

Development is resumable by any agent/dev: read `docs/PROGRESS.md` first — it is the
source of truth for what's done and what's next.
