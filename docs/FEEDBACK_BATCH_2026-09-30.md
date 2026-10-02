# Feedback batch — 30/09 2026 (Allan, first gym session on 0.8.0)

**Status 2026-10-02 (Phase 35, v0.8.1):** DONE — S1 S2 S3 T1 X1 N1 (unit-tested; S1/S3/T1/N1 emulator-verified 2026-10-02, see PROGRESS Phase 35);
S2 Spotify lifecycle rebuilt, needs the Redmi (no Spotify on the AVD). **Added as a standing task:** inline
documentation pass over the whole code base (Allan's request; `IMPLEMENTATION_PLAN.md` §0).

Allan's framing: *"you introduced bugs, breaking working functions: the opposite of what I explicitly told
to do (not break anything)."* Four of the eight items are regressions from Phase 34 (0.8.0). Root cause of
each is written down here so the same mistake is not made twice.

---

## S — Supersets (REGRESSION from Phase 34 A4/A5, `1723933`)

### S1. First set of a superset does not hand over to the partner
- Reported 20:38 / 21:55: "the first set in a superset does not automatically move to the next exercise
  (after the second it's working)"; "if you don't notice it keeps logging the entire set on the same
  exercise, pauses don't trigger, and if you finish the sets, the second exercise of the superset is
  skipped when you finish".
- Root cause: `SupersetOrder.activeMembers` (added 30/09 for the 29/08 "skipped partner" rule) treated
  **any member with no logged set as skipped once another member had one**. Right after A1 the partner
  B has no log either — but B1 is exactly what comes next. So the marker went to A2 (no pager swap), A2
  then went without rest (`restSkipped` saw B1 still open), and B was deferred to the very end.
- Fix: a member is skipped only when **its turn was passed over** — another member has a logged set
  that comes AFTER the member's first set in the interleaved order. A1 → B1; A1+A2 with B untouched → B
  skipped (29/08 rule intact). `SupersetOrderTest`: three new tests incl. a full A1→B1→A2→B2→done trip.

### S3. "First set not activating pause timer" (21:43)
- Same root cause: the rest after A1 is correctly skipped (B1 is next), but because the marker stayed on
  A, the user logged A2 next and that rest was skipped too. Fixed by S1; the rest test
  (`rest is skipped after the first half of a pair and taken after the second`) still passes.

## T — Time accounting

### T1. "40 minutes idle in two hours"
- Reported 22:14: "I barely stop between sets … I think active time is capping too early. Probably using
  the 40 seconds active even if I log 3 minutes. Only over 5 minutes is to be disregarded."
- Root cause: `SessionManager.gapActiveSecs` booked a flat 40 s for any gap over **180 s** (Phase 32).
  Allan's sets regularly take ~3 min, so each lost ~2.5 min to "idle".
- Fix: cutoff is 5 min (`MAX_GAP_SECS = 300`); a longer gap returns null so the cadence default applies
  instead of a literal 40. `gapActiveSecs(now)` takes a clock for tests. `SessionManagerTimerTest`: 200 s
  booked as 200, 300 s still counts, 301 s disregarded.

## X — Export

### X1. CSV loses ç à á ã
- Reported 20:40. Root cause: CSV written as bare UTF-8 (`String.toByteArray()`); Excel and phone
  spreadsheet apps decode that as the system code page.
- Fix: `CsvExport.fileBytes` prefixes the UTF-8 BOM; only CSV, never JSON (our own import would choke).
  `CsvFileBytesTest`.

## N — Exercise names

### N1. pt-BR names from the plan file replaced by literal machine translations
- Reported 21:40: "Source exercises are already given in pt-br but when selecting exercises it gets
  selected using English from database and literally translating names instead of correct names."
- Root cause: the plan JSON carries the Portuguese name in `match.names`, but the resolver matched the
  wger row (by id or English name) and the app displayed its on-device ML Kit translation of the English
  name (`ExerciseTranslation.machine = true`), or English when there was none.
- Fix: on import, `PlanTransfer.localNameFor` picks the first name in `match.names` that is not a name or
  alias of an existing translation and installs it as the app-language name — only when that language
  has no translation or a machine one; a human translation is never overwritten. The old machine name is
  kept as an alias so search still finds it. `ImportLocalNameTest`. Existing imported plans: re-import
  (merge) or rename by hand; names are not rewritten retroactively.
- Generator doc: put the app-language name right after the English one.

## S2 — Spotify (REGRESSION from Phase 34 F2)

### S2a. Spotify pops up on every app switch (21:50)
- Root cause: F2 moved the connection to AppRoot with **disconnect on ON_STOP / connect on ON_START**.
  Every return to the app reconnected; on HyperOS (Spotify's process killed freely) a reconnect wakes
  Spotify to the foreground. The session screen also still called `connect`, so two connects raced.
- Fix: one connection per process. Opened once when the setting is on (launch / switch flipped),
  **never** re-opened on foreground return; `connect` is a no-op while connected or connecting; the
  automatic attempt passes `showAuthView(false)`; a lost connection is retried only from a tap on the
  strip.

### S2b. "Make the bar visible and occupy the space when the commands are not there" (21:50)
- `SpotifyStrip` renders the controls when connected, otherwise a same-height placeholder "Spotify not
  connected · tap to connect" (en/pt/de). The tap is the only path that may show Spotify's auth sheet.

### S2c. Like button stopped working (22:08)
- Root cause: the ON_STOP disconnect reset `State()` (heart disabled: `canSave = false`) and the
  ON_START reconnect — racing the session screen's own connect — did not reliably restore it before the
  tap. Fix: persistent connection (S2a) + `canSave` is assumed true as soon as a track is known and only
  cleared when Spotify's library call says the item cannot be saved; a failed call after Spotify died
  flips the strip back to the placeholder instead of leaving dead buttons.
- Needs the Redmi: the AVD has no Spotify. Compile-green only.

## Standing task added (Allan, 02/10)

"Add as a task to comment the code and add inline comments for everything, this way maybe you can avoid
messing up in the future." → `IMPLEMENTATION_PLAN.md` §0 and `PROGRESS.md` Phase 36 (unchecked): a
file-by-file pass adding a header comment per file and inline comments on every non-trivial block,
with the invariants the 30/09 regressions broke spelled out where they live.
