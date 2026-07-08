# Maintenance Guide — for Claude (Opus / Claude Code) updating this app

You are maintaining Allan's personal single-user Android workout app. He is the only user;
there is no backend, no analytics, no accounts. Read `IMPLEMENTATION_PLAN.md` first — it is the
product spec. `WORKOUT_PLAN_GENERATOR.md` defines the import JSON schema; **it is a contract**:
never change it without bumping `schema_version` and keeping the importer backward-compatible.

## Repo layout (expected)

```
workout-app/
  app/                      Android module (Kotlin, Jetpack Compose, Room)
  docs/                     this file + plan + generator reference
  tools/wger-snapshot/      script that builds the bundled exercise DB from wger API
```

## Build & install

- JDK 17, Android SDK targetSdk 36, minSdk 29. `./gradlew assembleRelease`.
- Signing: release keystore lives OUTSIDE the repo at `~/keys/workout-app.jks`; properties in
  `~/.gradle/gradle.properties` (`WORKOUT_STORE_FILE`, `WORKOUT_STORE_PASSWORD`,
  `WORKOUT_KEY_ALIAS`, `WORKOUT_KEY_PASSWORD`). **Never regenerate the keystore** — a new key
  means Android refuses to update the installed app (uninstall = data loss unless backup exported first).
- Install: `adb install -r app/build/outputs/apk/release/app-release.apk`, or copy the APK to the
  phone and open from a file manager.
- Bump `versionCode` (+1) and `versionName` on every release Allan will install.

## Single-file web demo (`docs/demo.html`)

- `docs/demo.html` is a self-contained HTML demo of the app (CSS/JS/data all inline, no
  external requests): create plans/workouts, edit sets, run a session with rest/stopwatch
  timers, superset ordering, en/pt-BR/de, dark/light. Everything is in-memory only —
  refresh resets; nothing is ever saved. Purpose: show functionality without cloning the
  repo (download the one file, or serve it via GitHub Pages → Settings → Pages → branch
  `main`, folder `/docs` → `https://allan-valin.github.io/workout-app/demo.html`).
- **RULE: when app behavior or UI flows change, update `docs/demo.html` in the same
  change-set** so the demo never lies about the app. It mirrors the app at a functional
  level, not pixel-for-pixel; port logic faithfully (e.g. `SupersetOrder.nextStepFrom`
  has a direct JS port inside the file).
- Smoke test after editing: extract the `<script>` body and `node --check` it; the logic
  runs headless under a tiny DOM stub (see Phase-10 session notes) or just open the file
  in a browser and click through create-plan → add exercise → start → log → end.

## Emulator (AVD `testphone`)

- Launch with `-gpu swiftshader_indirect`. On this machine `-gpu host` segfaults the
  emulator seconds after boot completes (exit 139, log ends right after "Boot completed";
  Gradle then reports "No connected devices!") — verified 2026-07-07 evening, reproducible.
  swiftshader_indirect boots, installs, and runs the Compose UI stably.
- Keep `hw.ramSize = 3072M` in `~/.android/avd/testphone.avd/config.ini`.
- Working start-to-app-open block (also in README "Everyday" section):
  `~/Android/Sdk/emulator/emulator -avd testphone -gpu swiftshader_indirect >/dev/null 2>&1 &` →
  (full path required — `emulator` is not on PATH, only `platform-tools`/`adb` is) →
  `adb wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'` →
  `./gradlew installDebug` → `adb shell monkey -p dev.allan.workoutapp 1`.

## Device compatibility checklist (Redmi 15 Pro 5G, HyperOS 3.x)

When Allan reports "broken after update" or asks for a compatibility pass:

1. Check current HyperOS Android base; raise `targetSdk` only when needed and re-test the items below.
2. **Foreground service** (workout session timers): verify notification appears and timers survive
   screen-off ≥ 10 min. HyperOS kills aggressively — the app's first-run onboarding must still
   correctly deep-link to battery-optimization exemption and Autostart settings (these Settings
   intents change between HyperOS versions; test them).
3. Notification permission (runtime, API 33+) still granted/requested.
4. Storage Access Framework import/export still works from Allan's file manager.
5. Room schema changes: ALWAYS ship a `Migration`, never `fallbackToDestructiveMigration` —
   this DB holds his entire training history.

## Rules for adding features

- Offline-first is non-negotiable: no feature may require network for core use; network only on
  explicit user action, HTTPS only, wger hosts only (or ask Allan before adding a new host).
- Every tappable Compose element gets ripple feedback (shared `Modifier` — reuse it).
- Global back rule: back closes the topmost overlay/sheet/dialog first, navigates only when
  nothing is overlaid.
- Bottom navigation stays hidden during plan editing and running sessions.
- All user-facing strings go through resources in en / pt-BR / de — never hardcode. If you add
  strings, add all three translations.
- New stats must be derivable from `SetLog`/`Session` rows so history export stays complete.
- Keep dependencies minimal; prefer AndroidX/Compose first-party. Justify any new library.
- After any change to import/export: round-trip test (export → wipe emulator → import → diff).

## Testing before handing Allan an APK

- Unit tests for: stats math (volume attribution incl. PER_DUMBBELL ×2 and PER_SIDE bar formula),
  plan JSON importer (good file, missing fields, unknown exercise, wrong types), session
  recovery (RUNNING < 5 h resumes; > 5 h auto-ends).
- Manual smoke on emulator API 36: create plan → start session → lock screen 2 min → log sets →
  end → stats page → export CSV.
- If touching timers/service: also test process-death recovery (`adb shell am kill`).

## Updating the bundled exercise database

`tools/wger-snapshot/` fetches wger API data (exercises, translations en/pt-BR/de, muscles,
equipment, image URLs) and produces the pre-packaged Room DB + license attributions. Re-run only
on request; custom exercises and user data must never be touched by a snapshot update (snapshot
tables are separate from user tables and replaced atomically).

## When Allan asks for something ambiguous

Default to the simplest version that works offline, matches JEFIT-style conventions, and doesn't
add settings. One clarifying question max; otherwise decide and note the decision in
`IMPLEMENTATION_PLAN.md` §7 phase table or a new `docs/decisions.md` entry.
