<a id="readme-top"></a>

# Workout

Single-user, offline-first Android workout tracker (Kotlin + Jetpack Compose + Room).
Built for a Redmi 15 Pro 5G (HyperOS), sideloaded — no Play Store, no accounts,
no network needed after first launch.

## Table of contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Build & install](#build--install)
- [Release signing — generate your own key](#release-signing--generate-your-own-key)
- [Testing on an emulator](#testing-on-an-emulator)
- [Using the app on a phone](#using-the-app-on-a-phone)
- [Import / export](#import--export-settings-)
- [Creating a plan with an LLM](#creating-a-plan-with-an-llm)
- [Project layout](#project-layout)
- [Contributing / further development](#contributing--further-development)

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

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Requirements

Development and testing work on **Linux, Windows, and macOS**. You need:

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | Temurin/OpenJDK fine. Android Studio bundles one. |
| Android SDK | Platform 36 (compileSdk 36) | Easiest via [Android Studio](https://developer.android.com/studio); command-line tools also work. |
| Android device or emulator | Android 10+ (minSdk 29) | Emulator setup below. |
| `adb` | any recent | Ships with the SDK platform-tools; used to install on device/emulator. |

Platform specifics:

- **Linux/macOS**: use `./gradlew …`. SDK usually lands in `~/Android/Sdk`.
- **Windows**: use `gradlew.bat …` (PowerShell/cmd). SDK usually lands in
  `%LOCALAPPDATA%\Android\Sdk`.
- After cloning, point the build at your SDK. Android Studio does this automatically
  by writing `local.properties`; without Studio, create `local.properties` yourself:

  ```properties
  # Linux/macOS
  sdk.dir=/home/YOU/Android/Sdk
  # Windows (escape backslashes)
  sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
  ```

  `local.properties` is machine-specific and gitignored — never commit it.
- Gradle downloads itself (wrapper) and all dependencies on first build; first build
  needs network and a few minutes.

No other credentials, accounts, or API keys are needed: the exercise database is
bundled, and the optional wger refresh uses wger's public API without a key.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Build & install

```bash
./gradlew test assembleDebug            # debug APK — works out of the box, no signing setup
./gradlew test assembleRelease          # release APK — signed only if you set up a keystore (next section)
adb install -r app/build/outputs/apk/debug/app-debug.apk       # or release/app-release.apk
```

(Windows: `gradlew.bat` instead of `./gradlew`.)

For trying the app out or developing, the **debug build is all you need** — Android
signs it with an auto-generated debug key. A release keystore only matters for
long-term installs you intend to update in place.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Release signing — generate your own key

The release keystore is **not in this repository** (and never will be — `*.jks`,
`*.keystore`, and `local.properties` are gitignored). Without signing properties the
release build simply comes out unsigned; nothing breaks after a fresh clone.

To produce your own signed release builds:

1. Generate a keystore (any machine with a JDK; keep it **outside** the repo):

   ```bash
   keytool -genkeypair -v \
     -keystore ~/keys/workout-app.jks \
     -alias workout \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   It prompts for a store password, a key password, and identity fields (values are
   up to you).

2. Put the credentials in your **user-level** Gradle properties file — *not* in the
   repo. That file is `~/.gradle/gradle.properties` on Linux/macOS, or
   `C:\Users\YOU\.gradle\gradle.properties` on Windows (create it if missing):

   ```properties
   WORKOUT_STORE_FILE=/home/YOU/keys/workout-app.jks
   WORKOUT_STORE_PASSWORD=your-store-password
   WORKOUT_KEY_ALIAS=workout
   WORKOUT_KEY_PASSWORD=your-key-password
   ```

3. `./gradlew assembleRelease` now produces a signed
   `app/build/outputs/apk/release/app-release.apk`.

Two things to know about Android app signing:

- **Never lose or regenerate your keystore** once you've installed a release build:
  Android refuses to update an app whose signature changed. You'd have to uninstall
  (losing local data — export a full backup in Settings first) and reinstall.
- APKs signed with *your* key can't update an install signed with *someone else's*
  key. If a friend gave you a signed APK and you later build your own, same rule:
  backup → uninstall → install yours → restore backup.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Testing on an emulator

1. Install Android Studio → **Device Manager** → create a virtual device (any phone
   profile, e.g. Pixel 8) with a **API 36** system image. Give it ~3 GB RAM.
2. **Graphics must be hardware-accelerated.** In the AVD's advanced settings pick
   *Graphics: Hardware*, or in `~/.android/avd/<name>.avd/config.ini` ensure:

   ```ini
   hw.gpu.enabled = yes
   hw.gpu.mode = host
   ```

   With software rendering the emulator crashes shortly after startup and can freeze
   the host under Compose-heavy screens. If host GL is broken on your machine, fall
   back to `-gpu swiftshader_indirect` — but try `host` first.
3. Start the emulator (from Studio, or headless:
   `emulator -avd <name> -gpu host`), then:

   ```bash
   ./gradlew installDebug        # builds + installs on the running emulator
   ```

4. Suggested smoke test: create a plan → start a session → lock the screen 2 min →
   log some sets → end session → check the stats page → export a CSV.
5. If you touched the timers or the foreground service, also test process-death
   recovery: `adb shell am kill dev.allan.workoutapp` mid-session, reopen, session
   should resume.

Unit tests run with plain `./gradlew test` — no emulator needed.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Using the app on a phone

The app is sideloaded (no Play Store). Two ways to get it on:

**Via USB + adb**

1. Enable Developer options: Settings → About phone → tap the build/OS version
   entry 7 times.
2. In Developer options, enable **USB debugging** (on Xiaomi/HyperOS also enable
   **Install via USB**).
3. Plug in, accept the debugging prompt on the phone, then:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

**Without USB**

Copy the APK to the phone any way you like (file share, cloud drive, cable as
storage), open it from a file manager, and allow "install unknown apps" for that
file manager when prompted.

**First run — do not skip**

- Grant the **notification permission** when asked — rest-timer countdowns live in a
  notification.
- On HyperOS/MIUI (and most aggressive Android skins) the first session start walks
  you through granting a **battery-optimization exemption** and **Autostart**. Accept
  both, or the OS kills the timers when the screen is off.
- Everything works offline. The only network feature is the optional exercise-library
  refresh from wger in Settings, triggered manually.

**Updating**: install the new APK over the old one (`adb install -r` or open the APK)
— data is kept, as long as the APK is signed with the same key. Before any risky
update, Settings → **Full backup (JSON)**.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Import / export (Settings ⚙)

| What | Format | Notes |
|---|---|---|
| Import plan | JSON (schema v1) | Matches exercises by wger id → name → alias, falls back to creating custom exercises; anything unresolvable is reported. |
| Export plan | JSON | Round-trips with import — share or edit a plan as text. |
| Export plan | PDF | Printable sheet: exercises, set types, weights, rep ranges, rests, superset links. |
| History | CSV ×3 | Sets, sessions, bodyweight — for spreadsheets or feeding back to an LLM. |
| Full backup | JSON | Everything incl. custom exercises; restore on a fresh install. |

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Creating a plan with an LLM

The app ships the generator contract (`docs/WORKOUT_PLAN_GENERATOR.md`) inside the APK:

1. Settings → **Create a plan with AI** → *Save instructions file (.md)*.
2. Give that file to any chatbot (Claude, ChatGPT, …) and describe your goal, days per
   week, equipment and injuries.
3. It returns a `plan_*.json`; save it and use Settings → *Import plan (JSON)*.

The same card has a **How it works** button with these steps in-app. The JSON schema
supports rep ranges (`value_max`) and superset links (`superset_with_previous`).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

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

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing / further development

Development is resumable by any agent or developer on Linux or Windows:

- Read `docs/PROGRESS.md` first — it is the source of truth for what's done and
  what's next. `docs/IMPLEMENTATION_PLAN.md` is the product spec;
  `docs/MAINTENANCE.md` holds the working rules (offline-first, i18n en/pt-BR/de,
  Room migrations mandatory, import-schema is a versioned contract).
- Everything you need is in [Requirements](#requirements) — no secrets or private
  services are involved. A fresh `git clone` + JDK 17 + Android SDK 36 builds and
  runs the debug APK on any machine.
- Run `./gradlew test` before handing anyone an APK; see
  [Testing on an emulator](#testing-on-an-emulator) for the manual smoke test.

<p align="right">(<a href="#readme-top">back to top</a>)</p>
