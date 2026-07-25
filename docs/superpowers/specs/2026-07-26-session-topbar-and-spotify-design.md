# Session top bar space, adaptive clock format, and the missing Spotify UI

Date: 2026-07-26
Status: approved, ready for implementation plan

## Problem

Two unrelated defects reported after a phone session on 2026-07-26.

**1. The elapsed/estimated clock vanishes past 100 minutes.**

`SessionTopBar` (`app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt:468-527`) puts the
clock in the `TopAppBar` title slot. Material3 gives the navigation icon and the actions row their
measured width first; the title gets whatever is left. Current actions:

| element | approx width |
| --- | --- |
| navigation icon (list) | 48dp |
| volume `IconButton` | 48dp |
| "Info & notes" `TextButton` (icon 18dp + 4dp pad + label + button padding) | ~130dp |
| overflow `IconButton` | 48dp |
| **total consumed** | **~274dp** |

On a 360dp-wide phone that leaves ~86dp for the title. `fmt()` (`SessionScreen.kt:99`) is
`"%d:%02d".format(secs / 60, secs % 60)` — minutes:seconds, not hours:minutes — so at 100 minutes the
string grows from `99:23 / 75:00` to `100:23 / 75:00`, roughly 115dp at `titleMedium`. It no longer
fits and clips out of view. The value was already marginal below 100 minutes; 100 is just where it
crosses.

**2. Nothing Spotify-related appears anywhere in the app.**

Both entry points gate on `SpotifyRemote.available(context)`
(`app/src/main/java/dev/allan/workoutapp/session/SpotifyRemote.kt:51-52`):

- the Settings switch, `ui/settings/SettingsScreen.kt:436`
- the session-screen connect, `ui/session/SessionScreen.kt:146-152`

`available()` calls `SpotifyAppRemote.isSpotifyInstalled(context)`, which resolves `com.spotify.music`
through `PackageManager`. The module targets `targetSdk = 36` (`app/build.gradle.kts:16`) and
`app/src/main/AndroidManifest.xml` declares no `<queries>` element, so Android package-visibility
filtering hides the Spotify package from this app. `isSpotifyInstalled` returns false on a device
where Spotify is installed, `available()` returns false, the Settings row is not composed at all, and
`connect()` returns immediately. The feature is invisible with no error surfaced.

The `proguard-rules.pro` change already in the working tree (widened to `-keep class com.spotify.**`)
was the other suspect from the previous session. It is retained — harmless, and it removes R8 as a
variable while the manifest fix is verified.

## Non-goals

- Refactoring `SessionScreen.kt` (1417 lines). Out of scope for this change.
- Touching `Settings.SHOW_CLOCK` / key `session_show_clock` (`data/Settings.kt:58-67`). It gates the
  whole clock block at `SessionScreen.kt:480`, has no label string and no Settings row, and is
  effectively pinned to `true`. It stays dormant. It is unrelated to `tempo_show_clock`
  (`res/values/strings.xml:261`), which labels the cadence/timed-set feature reached via the
  `Icons.Default.Timer` button at `SessionScreen.kt:1016`.
- A user-facing time-format preference. The adaptive format below removes the need for one.

## Design

### 1. Collapse "Info & notes" to an icon

`SessionScreen.kt:512-515`: replace the `TextButton` with an `IconButton` carrying
`Icons.Default.Info` and `contentDescription = stringResource(R.string.info_note)`. The content
description drives both TalkBack and the Material3 long-press tooltip, so the label stays reachable.

Frees ~85dp. Title slot goes from ~86dp to ~171dp, which fits every case in section 2. This is the
fix for the reported defect; sections 2 and 3 are refinements on top of it.

Action order is unchanged: list · [clock] · volume · info · overflow.

The overview-mode top bar (`SessionScreen.kt:185-197`) is not touched. It has no actions competing
for width, and its one-line `name · elapsed / estimated` constraint from 2026-07-25 still holds.

### 2. Adaptive clock format

`fmt()` at `SessionScreen.kt:99` becomes:

- `secs < 3600` → `m:ss` (unchanged behaviour, e.g. `45:12`)
- `secs >= 3600` → `h:mm:ss` (e.g. `1:40:23`)

Each side of the `elapsed / estimated` pair formats independently, so a normal workout reads
`12:30 / 45:00` and a long one reads `1:40:23 / 1:15:00`. Worst realistic case at `titleMedium` is
~135dp, inside the ~171dp available after section 1.

`fmt()` is also used by the rest and set countdowns (`SessionScreen.kt:1204-1205`). Those are always
well under an hour in practice, so their rendering is unchanged; the branch is simply never taken.

`fmt()` is currently `private`. Change it to `internal` so it can be unit-tested, matching
`ui/stats/StatsTab.kt:140`.

### 3. "Show estimated time during workout" toggle

New preference, following the `SPOTIFY_ENABLED` pattern at `data/Settings.kt:48-56`:

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

Surfaced as a `Switch` row in the existing "Session" card in `SettingsScreen.kt`, inserted after the
prev/next row (ends line 432) and before the Spotify block (starts line 434). Same
`Row` / `SpaceBetween` / `Switch` shape as its neighbours.

Behaviour in `SessionTopBar`:

| setting | value line | caption line |
| --- | --- | --- |
| on (default) | `12:30 / 45:00` | `elapsed / estimated` (`R.string.estimated_time`) |
| off | `12:30` | `elapsed` (new `R.string.elapsed_only`) |

The overview top bar (`SessionScreen.kt:185-197`) always shows the pair regardless of this setting —
per the request, the estimate stays visible where there is room for it.

The post-workout summary is unaffected: it never showed an estimate. It reports total / active /
rest / idle time via its own `fmtHm` (`ui/session/SummaryScreen.kt:121,156-159`), which this change
does not touch.

New strings `elapsed_only` in `values`, `values-de`, `values-pt-rBR`.

### 4. Spotify package visibility

Add to `app/src/main/AndroidManifest.xml`, as a direct child of `<manifest>`:

```xml
<queries>
    <package android:name="com.spotify.music" />
</queries>
```

This restores `isSpotifyInstalled`, which restores `available()`, which un-hides the Settings row and
lets `connect()` run.

Open item to verify on device, not to fix blind: the manifest declares no intent-filter for the
`workoutapp://spotify-callback` redirect URI set at `app/build.gradle.kts:33`. App Remote's
`showAuthView(true)` normally completes inside Spotify without an inbound intent, so this may not be
needed — but the URI must match the Spotify developer dashboard entry for this applicationId and
signing fingerprint exactly. If authorization still fails after the `<queries>` fix, check the
dashboard registration first, then consider adding the intent-filter.

## Testing

Unit tests (`app/src/test/java/dev/allan/workoutapp/`):

- New test for `fmt()` covering the boundary: 0s, 59s, 60s, 3599s, 3600s, 3661s, and a
  representative long value. Requires the `internal` visibility change from section 2.

Emulator pass (required every batch, per the standing rule):

- Session top bar at simulated elapsed values on both sides of 60 min and 100 min — clock must stay
  fully visible in every case.
- Info icon opens the same sheet the old button did; long-press shows the "Info & notes" tooltip.
- Estimate toggle off → session bar shows elapsed only with the `elapsed` caption; overview bar still
  shows the pair.
- Settings row renders in the Session card and survives a process restart.
- Spotify: install Spotify in the emulator, confirm the Settings row now appears, enable it, and
  confirm the mini-player strip and heart appear in a session.

Device pass on the Redmi for the Spotify path specifically, since the HyperOS media-panel behaviour
is what motivated App Remote in the first place.

## Files touched

| file | change |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | add `<queries>` for `com.spotify.music` |
| `app/src/main/java/dev/allan/workoutapp/ui/session/SessionScreen.kt` | icon-only info action; adaptive `fmt()`; `internal` visibility; estimate-aware clock block |
| `app/src/main/java/dev/allan/workoutapp/data/Settings.kt` | `SHOW_ESTIMATE` preference |
| `app/src/main/java/dev/allan/workoutapp/ui/settings/SettingsScreen.kt` | estimate switch row in the Session card |
| `app/src/main/res/values/strings.xml` (+ `-de`, `-pt-rBR`) | `elapsed_only` |
| `app/src/test/java/dev/allan/workoutapp/` | `fmt()` boundary test |
| `app/proguard-rules.pro` | already modified in the working tree; commit alongside |

## Commit plan

Per the standing commit rule, each checkpoint is committed and pushed to `main` rather than batched
into one commit:

1. Spotify `<queries>` fix + the pending proguard change.
2. Icon-only info action + adaptive `fmt()` + its test.
3. `SHOW_ESTIMATE` preference, Settings row, strings.
