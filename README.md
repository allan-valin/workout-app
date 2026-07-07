<a id="readme-top"></a>

# Workout

Single-user, offline-first Android workout tracker (Kotlin + Jetpack Compose + Room).
Built for a Redmi 15 Pro 5G (HyperOS), sideloaded — no Play Store, no accounts,
no network needed after first launch.

## Table of contents

- [What it does](#what-it-does)
- [First-time setup](#first-time-setup)
  - [1. Install the tools](#1-install-the-tools)
  - [2. Clone and point the build at your SDK](#2-clone-and-point-the-build-at-your-sdk)
  - [3. Put the SDK tools on your PATH](#3-put-the-sdk-tools-on-your-path)
  - [4. Create the emulator](#4-create-the-emulator)
- [Everyday: run & test on the emulator](#everyday-run--test-on-the-emulator)
- [Using the app on a phone](#using-the-app-on-a-phone)
- [Release signing — generate your own key](#release-signing--generate-your-own-key)
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

## First-time setup

Everything in this section is done **once**. Works on Linux, Windows, and macOS;
commands below are for Linux — Windows notes inline. No accounts, API keys, or other
credentials are needed anywhere: the exercise database ships in the repo, and release
signing is optional ([see below](#release-signing--generate-your-own-key)).

### 1. Install the tools

| Tool | Version | How |
|---|---|---|
| JDK | 17+ | `sudo apt install openjdk-17-jdk` (Linux) or bundled with Android Studio |
| Android Studio | latest | [developer.android.com/studio](https://developer.android.com/studio) — easiest way to get the SDK, emulator, and `adb` in one install |

During Android Studio's first-run wizard, accept the defaults — it installs the SDK to
`~/Android/Sdk` (Linux) or `%LOCALAPPDATA%\Android\Sdk` (Windows).

### 2. Clone and point the build at your SDK

```bash
git clone https://github.com/allan-valin/workout-app.git
cd workout-app
```

If you open the project in Android Studio once, it writes `local.properties` for you.
Otherwise create it by hand:

```properties
# local.properties (machine-specific, gitignored — never commit)
sdk.dir=/home/YOU/Android/Sdk
# Windows: sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
```

### 3. Put the SDK tools on your PATH

The `emulator` and `adb` commands live inside the SDK and are **not** on your PATH by
default. Linux — run once:

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools
EOF
source ~/.bashrc
```

(Windows: add `%LOCALAPPDATA%\Android\Sdk\emulator` and
`%LOCALAPPDATA%\Android\Sdk\platform-tools` to your user PATH in
Settings → System → Environment variables.)

### 4. Create the emulator

In Android Studio: **Device Manager → Create virtual device** → pick a phone profile
(e.g. Pixel 8) → pick an **API 36** system image → name it `testphone` → under
advanced settings set **RAM ≈ 3 GB**.

Graphics mode: start with **Automatic/Hardware**. If the emulator later dies a few
seconds after booting (silent segfault — this happens on some Linux GL drivers), the
run command below already uses the safe software renderer; see the note there.

Setup done. Everything from here on is repeatable.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Everyday: run & test on the emulator

One block — copy, paste, and ~60 s later the app is open on a running emulator.
It builds only what changed since last time, so use the same block after every code
change (there is **no separate build step** — `installDebug` compiles + installs):

```bash
emulator -avd testphone -gpu swiftshader_indirect >/dev/null 2>&1 &
adb wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'
./gradlew installDebug
adb shell monkey -p dev.allan.workoutapp 1
```

Line by line: starts the emulator in the background (so your terminal stays usable —
don't close it, that kills the emulator), waits until Android has fully booted,
builds + installs the debug APK, opens the app.

- If the emulator is already running, pasting the whole block again is harmless —
  the first line errors quietly and the rest proceeds.
- `-gpu swiftshader_indirect` is the renderer that works reliably everywhere. If you
  want faster graphics, try `-gpu host`; but if the emulator vanishes seconds after
  boot ("No connected devices!" from Gradle), that's the host-GL segfault — go back
  to `swiftshader_indirect`.
- Unit tests need no emulator: `./gradlew test`.
- Done testing? `adb emu kill` shuts the emulator down.

Suggested manual smoke test: create a plan → start a session → lock the screen 2 min
→ log some sets → end session → check the stats page → export a CSV. If you touched
the timers or the foreground service, also test process-death recovery:
`adb shell am kill dev.allan.workoutapp` mid-session, reopen — the session must resume.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Using the app on a phone

The app is sideloaded (no Play Store).

**Phone prep (first time only)**

1. Enable Developer options: Settings → About phone → tap the build/OS version
   entry 7 times.
2. In Developer options, enable **USB debugging** (on Xiaomi/HyperOS also enable
   **Install via USB**).

**Install (every release)**

```bash
./gradlew assembleRelease        # or assembleDebug for a quick unsigned test build
adb install -r app/build/outputs/apk/release/app-release.apk
```

`assembleRelease` produces a *signed* APK only after the
[keystore setup](#release-signing--generate-your-own-key); the debug APK needs no
setup. No USB? Copy the APK to the phone any way you like (file share, cloud drive),
open it from a file manager, and allow "install unknown apps" when prompted.

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

## Release signing — generate your own key

The release keystore is **not in this repository** (and never will be — `*.jks`,
`*.keystore`, and `local.properties` are gitignored). Without signing properties the
release build simply comes out unsigned; nothing breaks after a fresh clone, and the
debug build never needs any of this.

To produce your own signed release builds (one-time):

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
- [First-time setup](#first-time-setup) is all you need — no secrets or private
  services are involved. A fresh `git clone` + JDK 17 + Android SDK 36 builds and
  runs the debug APK on any machine.
- Run `./gradlew test` before handing anyone an APK; see
  [Everyday: run & test on the emulator](#everyday-run--test-on-the-emulator) for
  the manual smoke test.

<p align="right">(<a href="#readme-top">back to top</a>)</p>
