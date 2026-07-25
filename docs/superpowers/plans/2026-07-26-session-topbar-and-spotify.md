# Session Top Bar & Spotify Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the elapsed/estimated clock from clipping out of the session top bar past 100 minutes, and make the Spotify integration visible again.

**Architecture:** Four independent changes to an existing Android/Compose app. The top-bar fix reclaims horizontal space by collapsing a labelled `TextButton` to an `IconButton`, then makes the clock format adaptive so long workouts stay narrow. A new DataStore boolean lets the estimate half of the clock be hidden during a session. Separately, a manifest `<queries>` declaration restores Android package visibility for `com.spotify.music`, which is what silently disabled the entire Spotify feature.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, AndroidX DataStore Preferences, JUnit4, Gradle (Kotlin DSL).

**Spec:** `docs/superpowers/specs/2026-07-26-session-topbar-and-spotify-design.md`

## Global Constraints

- Module targets `compileSdk = 36`, `minSdk = 29`, `targetSdk = 36` (`app/build.gradle.kts:11-16`). Do not change these.
- Every new user-facing string must be added to all three locales: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-pt-rBR/strings.xml`. A string present in only one locale is an incomplete task.
- Commit and push each task to `main` as it completes. Do not batch the whole plan into one commit.
- Every batch requires an emulator pass via `./run.sh` before it is considered done. Testing on Allan's physical Redmi is deferred except where a task explicitly calls for it.
- Do not refactor `SessionScreen.kt` (1417 lines) beyond the edits named here.
- Do not touch `Settings.SHOW_CLOCK` / key `session_show_clock` (`app/src/main/java/dev/allan/workoutapp/data/Settings.kt:58-67`). It is dormant by design in this change.
- Unit tests run with `./gradlew testDebugUnitTest`. Build/install/launch runs with `./run.sh`.

## File Structure

| File | Responsibility | Tasks |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | Declare Spotify package visibility | 1 |
| `app/proguard-rules.pro` | Already modified in the working tree; commit as-is with Task 1 | 1 |
| `app/src/test/java/dev/allan/workoutapp/SessionClockFormatTest.kt` | New. Unit tests for the clock format boundary | 2 |
| `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt` | Clock format function; top-bar action row; estimate-aware clock block | 2, 3, 4 |
| `app/src/main/java/dev/allan/workoutapp/data/Settings.kt` | `SHOW_ESTIMATE` preference | 4 |
| `app/src/main/java/dev/allan/workoutapp/ui/settings/SettingsScreen.kt` | Estimate switch row in the Workout session card | 4 |
| `app/src/main/res/values/strings.xml` (+ `-de`, `-pt-rBR`) | `elapsed_only`, `show_estimate_setting` | 4 |

Task order matters: Task 2 makes `fmt` `internal`, which Tasks 3 and 4 both read. Tasks 1 and 2 are otherwise independent.

---

### Task 1: Restore Spotify package visibility

The whole Spotify feature is invisible — no Settings row, no mini-player. `SpotifyRemote.available()` (`app/src/main/java/dev/allan/workoutapp/session/SpotifyRemote.kt:51-52`) calls `SpotifyAppRemote.isSpotifyInstalled(context)`, which resolves `com.spotify.music` through `PackageManager`. Under `targetSdk = 36` Android hides packages that are not declared in `<queries>`, so the lookup fails on a device where Spotify *is* installed. Both call sites (`ui/settings/SettingsScreen.kt:436`, `ui/session/SessionScreen.kt:146-152`) then fall into their "unavailable" branch with no error shown.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (insert after the `<uses-permission>` block ending at line 12, before `<application>` at line 14)
- Modify: `app/proguard-rules.pro` (already changed in the working tree — commit, do not re-edit)
- Test: manual, on emulator with Spotify installed

- [ ] **Step 1: Add the `<queries>` declaration**

In `app/src/main/AndroidManifest.xml`, insert this as a direct child of `<manifest>`, between the last `<uses-permission>` line and the `<application>` tag:

```xml
    <!-- targetSdk 30+ hides other packages by default. SpotifyAppRemote.isSpotifyInstalled()
         resolves com.spotify.music through PackageManager, so without this the whole Spotify
         feature reports "not installed" and hides itself with no error (Allan, 26/07). -->
    <queries>
        <package android:name="com.spotify.music" />
    </queries>
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install Spotify on the emulator**

The emulator AVD is `testphone` (`run.sh:11`). Spotify must be present for `isSpotifyInstalled` to return true. Either install it from the Play Store on a Google-APIs AVD, or sideload an APK:

```bash
"${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" install /path/to/spotify.apk
"${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" shell pm list packages | grep spotify
```

Expected: `package:com.spotify.music`.

If Spotify cannot be installed on the emulator at all, stop and report that — do not mark this task done on a build check alone. The point of the task is that a real `com.spotify.music` becomes visible.

- [ ] **Step 4: Verify the Settings row now appears**

Run: `./run.sh`
Then in the app: Settings → scroll to the "Workout session" card.
Expected: a "Spotify controls" switch row is present below "Previous/next exercise buttons". Before this change it was absent entirely.

- [ ] **Step 5: Verify the session mini-player**

Enable the Spotify switch, start playback in Spotify, then start a workout session.
Expected: the mini-player strip with track name, artist, transport controls and the heart appears. Tapping the heart toggles Liked Songs.

If authorization fails here, do **not** start adding intent-filters. The redirect URI `workoutapp://spotify-callback` (`app/build.gradle.kts:33`) must match the Spotify developer dashboard entry for this `applicationId` and signing fingerprint — check the dashboard registration first and report back.

An emulator pass does not close this out. `docs/AUDIT_2026-07-25.md:180-181` already puts the strip, transport and heart on the Redmi checklist, because App Remote was chosen specifically for HyperOS's media-panel behaviour, which no emulator reproduces. Leave that checklist item open; Task 5 records it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/proguard-rules.pro
git commit -m "Declare the Spotify package so the integration stops hiding itself

targetSdk 36 package-visibility filtering hid com.spotify.music from
PackageManager, so SpotifyAppRemote.isSpotifyInstalled() returned false on
a phone with Spotify installed. available() then returned false and both
call sites silently took their unavailable branch: no Settings row, no
mini-player, no error.

Carries the pending proguard widening to -keep class com.spotify.** from
the previous session, which removes R8 as a variable while this is verified.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

### Task 2: Adaptive clock format

`fmt()` at `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:99` is minutes:seconds with no hour rollover, so a 100-minute workout renders `100:23` and keeps growing. Make it roll over to `h:mm:ss` at one hour so the string stays narrow.

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:99`
- Create: `app/src/test/java/dev/allan/workoutapp/SessionClockFormatTest.kt`

**Interfaces:**
- Produces: `internal fun fmt(secs: Int): String` in package `dev.allan.workoutapp.ui.session`. Returns `m:ss` below 3600 seconds and `h:mm:ss` at or above. Tasks 3 and 4 call it unchanged; the test in this task imports it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/allan/workoutapp/SessionClockFormatTest.kt`:

```kotlin
package dev.allan.workoutapp

import dev.allan.workoutapp.ui.session.fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The session clock lives in the TopAppBar title slot, which only gets the width the
 * navigation icon and actions leave behind. Minutes-only formatting grew without bound and
 * clipped the whole value out of view past 100 minutes (Allan, 26/07), so the format rolls
 * over to h:mm:ss at one hour and stays narrow.
 */
class SessionClockFormatTest {

    @Test
    fun `below an hour stays minutes and seconds`() {
        assertEquals("0:00", fmt(0))
        assertEquals("0:59", fmt(59))
        assertEquals("1:00", fmt(60))
        assertEquals("45:12", fmt(45 * 60 + 12))
    }

    @Test
    fun `the last second below an hour is still minutes and seconds`() {
        assertEquals("59:59", fmt(3599))
    }

    @Test
    fun `one hour rolls over to hours minutes seconds`() {
        assertEquals("1:00:00", fmt(3600))
        assertEquals("1:01:01", fmt(3661))
    }

    @Test
    fun `the reported case is narrow again`() {
        // 100 minutes 23 seconds used to render as "100:23" and clipped out of the bar.
        assertEquals("1:40:23", fmt(100 * 60 + 23))
    }

    @Test
    fun `minutes and seconds are zero padded past the hour`() {
        assertEquals("2:05:07", fmt(2 * 3600 + 5 * 60 + 7))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "dev.allan.workoutapp.SessionClockFormatTest"`
Expected: compilation failure — `fmt` is `private` and not visible from the test source set.

- [ ] **Step 3: Make the change**

Replace line 99 of `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt`:

```kotlin
private fun fmt(secs: Int): String = "%d:%02d".format(secs / 60, secs % 60)
```

with:

```kotlin
/**
 * Session clock. Rolls over to h:mm:ss at one hour: the TopAppBar title slot only gets the
 * width the actions leave behind, and unbounded minutes ("100:23") clipped out of view
 * (Allan, 26/07). `internal` so SessionClockFormatTest can reach it.
 */
internal fun fmt(secs: Int): String =
    if (secs < 3600) "%d:%02d".format(secs / 60, secs % 60)
    else "%d:%02d:%02d".format(secs / 3600, secs % 3600 / 60, secs % 60)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "dev.allan.workoutapp.SessionClockFormatTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, no regressions. `fmt` is also used by the rest and set countdowns at `SessionScreen.kt:1204-1205`; those are always well under an hour, so nothing there should change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt app/src/test/java/dev/allan/workoutapp/SessionClockFormatTest.kt
git commit -m "Roll the session clock over to h:mm:ss at one hour

Minutes-only formatting grew without bound, so past 100 minutes the value
outgrew the TopAppBar title slot and clipped out of view entirely.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

### Task 3: Collapse the "Info & notes" action to an icon

This is the actual fix for the reported defect. The labelled `TextButton` in the session top bar consumes roughly 130dp of a 360dp-wide phone; with the navigation icon, volume icon and overflow icon that leaves the title slot about 86dp, which the clock outgrows. An `IconButton` frees roughly 85dp.

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:510-515`

**Interfaces:**
- Consumes: `internal fun fmt(secs: Int)` from Task 2 (no call-site change; the reclaimed width is what makes the h:mm:ss string fit comfortably).

- [ ] **Step 1: Replace the button**

In `SessionTopBar`, replace lines 510-515:

```kotlin
            // Info sheet hosts description + persistent note + video, so one button covers
            // all — the label says so (Allan: "note" alone hid the merged functions).
            TextButton(onClick = { current?.let { vm.openDescription(it.exerciseId, withImage = false) } }) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.info_note), modifier = Modifier.padding(start = 4.dp))
            }
```

with:

```kotlin
            // Info sheet hosts description + persistent note + video, so one button covers all.
            // Icon-only: the "Info & notes" label ate ~130dp of the action row and squeezed the
            // clock out of the title slot past 100 minutes (Allan, 26/07). The content
            // description still drives TalkBack and the long-press tooltip, so the label that
            // explains the merged functions stays reachable.
            IconButton(onClick = { current?.let { vm.openDescription(it.exerciseId, withImage = false) } }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.info_note))
            }
```

`Icons.Default.Info` is already imported at line 42 and `IconButton` is already in use in this same `actions` block, so no import changes are needed. `R.string.info_note` is unchanged and stays in all three locales.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the Kotlin compiler now reports `TextButton`, `Text`, or `Modifier.size` as unused imports, leave them — they are all still used elsewhere in this 1417-line file. Do not remove imports without grepping first.

- [ ] **Step 3: Emulator pass — the reported defect**

Run: `./run.sh`

Start a workout session. To reach the >100 minute state without waiting, either leave a session running and re-open it, or temporarily seed `state.elapsedSecs` while testing. Check the top bar at each of these elapsed values:

| elapsed | expected clock |
| --- | --- |
| ~12 min | `12:30 / 45:00`, fully visible |
| ~59 min | `59:59 / 45:00`, fully visible |
| ~61 min | `1:01:12 / 45:00`, fully visible |
| ~101 min | `1:41:12 / 45:00`, fully visible |

Expected: the value is never clipped at the right edge, and the `elapsed / estimated` caption below it is fully readable. Before this change the ~101 min case was invisible.

- [ ] **Step 4: Emulator pass — the info action still works**

Tap the info icon during a session.
Expected: the same sheet opens as before, with description, persistent note and video. Long-press the icon.
Expected: a tooltip reading "Info & notes".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt
git commit -m "Collapse the session info action to an icon so the clock fits

The labelled Info & notes button took ~130dp of the action row, leaving the
TopAppBar title slot ~86dp on a 360dp phone. The elapsed/estimated clock
outgrew it and clipped out of view. The content description keeps the label
on TalkBack and the long-press tooltip.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

### Task 4: "Show estimated time during workout" preference

A new DataStore boolean, default on. When off, the session top bar shows elapsed only. The overview top bar always shows the pair regardless — it has no competing actions and therefore no space pressure.

**Files:**
- Modify: `app/src/main/java/dev/allan/workoutapp/data/Settings.kt` (insert after the `SPOTIFY_ENABLED` block ending at line 56)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-pt-rBR/strings.xml`
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/settings/SettingsScreen.kt` (insert between line 432 and line 434)
- Modify: `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:473-493`

**Interfaces:**
- Consumes: `internal fun fmt(secs: Int)` from Task 2.
- Produces: `Settings.showEstimate(context: Context): Flow<Boolean>` and `suspend fun Settings.setShowEstimate(context: Context, value: Boolean)` in `dev.allan.workoutapp.data`.

- [ ] **Step 1: Add the preference**

In `app/src/main/java/dev/allan/workoutapp/data/Settings.kt`, insert after the `setSpotifyEnabled` block (ends line 56) and before the `SHOW_CLOCK` block (starts line 58):

```kotlin
    private val SHOW_ESTIMATE =
        androidx.datastore.preferences.core.booleanPreferencesKey("session_show_estimate")

    /** Show the "/ estimated" half of the session clock (default on). */
    fun showEstimate(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_ESTIMATE] ?: true }

    suspend fun setShowEstimate(context: Context, value: Boolean) {
        context.dataStore.edit { it[SHOW_ESTIMATE] = value }
    }

```

- [ ] **Step 2: Add the strings to all three locales**

In `app/src/main/res/values/strings.xml`, add after line 97 (`estimated_time`):

```xml
    <string name="elapsed_only">elapsed</string>
```

and after line 174 (`prev_next_setting`):

```xml
    <string name="show_estimate_setting">Show estimated time during workout</string>
```

In `app/src/main/res/values-de/strings.xml`, add after its `estimated_time` line (97):

```xml
    <string name="elapsed_only">vergangen</string>
```

and after its `prev_next_setting` line (174):

```xml
    <string name="show_estimate_setting">Geschätzte Zeit im Training anzeigen</string>
```

In `app/src/main/res/values-pt-rBR/strings.xml`, add after its `estimated_time` line (97):

```xml
    <string name="elapsed_only">decorrido</string>
```

and after its `prev_next_setting` line (174):

```xml
    <string name="show_estimate_setting">Mostrar tempo estimado durante o treino</string>
```

The lowercase `elapsed` / `vergangen` / `decorrido` matches the existing caption style — `estimated_time` is `elapsed / estimated`, also lowercase.

- [ ] **Step 3: Add the Settings switch row**

In `app/src/main/java/dev/allan/workoutapp/ui/settings/SettingsScreen.kt`, inside the "Workout session" card, insert between the closing `}` of the prev/next `Row` (line 432) and the `// Spotify strip` comment (line 434):

```kotlin
                    // The session top bar is tight on width; hiding the estimate buys the
                    // elapsed value the whole slot (Allan, 26/07). The overview bar keeps the
                    // pair either way — it has no actions competing for space.
                    val showEstimate by dev.allan.workoutapp.data.Settings.showEstimate(context)
                        .collectAsState(initial = true)
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.show_estimate_setting),
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(
                            checked = showEstimate,
                            onCheckedChange = { value ->
                                scope.launch {
                                    dev.allan.workoutapp.data.Settings.setShowEstimate(context, value)
                                }
                            },
                        )
                    }
```

`context` and `scope` are already in scope — declared at lines 412 and 415 of the same card. `Modifier`, `Arrangement`, `Text`, `stringResource` and `collectAsState` are all already imported and used by the prev/next row directly above.

- [ ] **Step 4: Make the session top bar honour it**

In `SessionTopBar` in `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt`, add after the `showClock` line (473):

```kotlin
    val showEstimate by dev.allan.workoutapp.data.Settings.showEstimate(ctx).collectAsState(initial = true)
```

Then replace the two `Text` calls in the title block (lines 483-492):

```kotlin
                Text(
                    fmt(state.elapsedSecs) + " / " + fmt(state.estimatedTotalSecs),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.estimated_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
```

with:

```kotlin
                Text(
                    if (showEstimate) fmt(state.elapsedSecs) + " / " + fmt(state.estimatedTotalSecs)
                    else fmt(state.elapsedSecs),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    stringResource(
                        if (showEstimate) R.string.estimated_time else R.string.elapsed_only
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
```

Leave the overview-mode top bar at lines 185-197 untouched — it always shows the pair.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 7: Emulator pass**

Run: `./run.sh`

Check each of these:

| check | expected |
| --- | --- |
| Settings → "Workout session" card | "Show estimated time during workout" row sits between "Previous/next exercise buttons" and "Spotify controls", switch on |
| Toggle off, start a session | top bar reads `12:30` with the caption `elapsed` |
| From that session, tap the list icon to reach the overview | top bar still reads `name · 12:30 / 45:00` with `elapsed / estimated` |
| Toggle back on | session bar reads `12:30 / 45:00` with `elapsed / estimated` |
| Toggle off, force-stop the app, relaunch | switch is still off — the preference survived the process restart |

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/allan/workoutapp/data/Settings.kt app/src/main/java/dev/allan/workoutapp/ui/settings/SettingsScreen.kt app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-pt-rBR/strings.xml
git commit -m "Let the session clock hide the estimate half

New session_show_estimate preference, default on. Off gives the elapsed
value the whole title slot and captions it 'elapsed'. The overview bar keeps
elapsed / estimated either way — it has no actions competing for width.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

### Task 5: Record the batch in the audit ledger

`docs/AUDIT_2026-07-25.md` tracks per-feature verified / not-verified status and carries a "still to verify" list. This batch touched the session top bar and the Spotify path, so both entries need updating rather than leaving stale.

**Files:**
- Modify: `docs/AUDIT_2026-07-25.md`

- [ ] **Step 1: Update the section A row for the clock**

The table at line 17 currently reads:

```
| Start workout → session, elapsed / estimated clock | 3, 10 | OK |
```

Replace with:

```
| Start workout → session, elapsed / estimated clock | 3, 10 | OK (was FAIL past 100 min — fixed 26/07) |
```

- [ ] **Step 2: Update the section A row for the info sheet**

The row at line 37 currently reads:

```
| Info & notes sheet, video link watch/open | 9, 10 | n/v |
```

Replace with:

```
| Info & notes sheet (icon-only since 26/07), video link watch/open | 9, 10 | OK (sheet + tooltip; video link n/v) |
```

Only claim the sheet and tooltip as `OK` if Task 3 Step 4 actually passed on the emulator. The video link was not exercised by this batch, so it stays `n/v`.

- [ ] **Step 3: Append a new dated section**

Add at the end of the file, following the prose-bullet style of the "Follow-up pass" section at line 171:

```markdown
## Batch — 2026-07-26 (top-bar space, clock format, Spotify visibility)

- Session clock vanished past 100 minutes. Cause was width, not arithmetic: the labelled
  "Info & notes" TextButton took ~130dp of the action row, leaving the TopAppBar title slot
  ~86dp on a 360dp phone, and `fmt` had no hour rollover so the value only grew. Fixed by
  collapsing the action to an icon and rolling the format over to h:mm:ss at one hour.
  FIXED + verified on the emulator at ~12, ~59, ~61 and ~101 minutes.
- New `session_show_estimate` preference (default on) hides the "/ estimated" half of the
  session clock; the overview bar keeps the pair either way. Verified on the emulator,
  including persistence across a force-stop.
- Spotify showed nothing at all — no Settings row, no mini-player. Cause: targetSdk 36
  package-visibility filtering hid `com.spotify.music` from PackageManager, so
  `isSpotifyInstalled()` returned false on a phone that had it installed, and both call
  sites took their silent "unavailable" branch. Fixed with a manifest `<queries>` entry.
  The strip, transport and heart remain on the Redmi checklist — the emulator result does
  not settle the HyperOS media-panel case that motivated App Remote in the first place.
```

Adjust the last bullet's claims to match what Task 1 actually produced. If Spotify could not be installed on the emulator, say so plainly rather than implying it was verified.

- [ ] **Step 4: Commit**

```bash
git add docs/AUDIT_2026-07-25.md
git commit -m "Record the 26/07 top-bar and Spotify batch in the audit ledger

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push
```

---

## Deviation from the spec's commit plan

The spec sketched three commits, grouping the icon-only action and the adaptive format together. This plan splits them into Tasks 2 and 3 with a commit each, because a reviewer could reasonably accept the format change while rejecting the icon collapse, or vice versa. Task 5 adds a documentation commit the spec did not name. Five commits, same scope.
