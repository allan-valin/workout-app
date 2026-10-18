# Feedback batch — 09/10–17/10 2026 (Allan, fase 3 on 0.8.2/0.8.3)

**Status 2026-10-18 (0.8.4, Phase 41): W1 (+W3) G1 C1 FIXED** — unit + instrumented + AVD-verified, see PROGRESS Phase 41.
**OPEN:** G2/G3/G4 (history by exercise + profiles — decided 18/10 afternoon, see Phase 42). P1 is a rule for the next plan file; P2 retracted.
Diagnosis as written on the morning of 18/10 follows.
Every item below was traced in the code and, where it is app behaviour, reproduced on the AVD
(release 0.8.3, CORE workout, session 4, discarded afterwards; `set_log` / `session_set_draft` /
`set_template` pulled after each step).

**Did 0.8.2 / 0.8.3 (`ce34e7b`, `9dacbc4`) break any of this? No.** Those two commits touch image
import, the unilateral flag, CSV columns and the active-time estimate. None of the eight items goes
through code they changed. The causes are older: the Phase 3 prefill design (07/07), the Phase 34
cadence display rule (30/09, `1723933`), and the fase 3 plan JSON I generated on 06/10.

---

## W — "values I input keep changing between workouts" (16/10, 17/10 ×3)

### W1. Editing a LOGGED set changes the screen, not the record (root cause of the 17/10 trio)
- Reported 22:43: "I register a set with a weight I was trying, change it for the next and the
  previous retroactively, but it keeps the last set registered even if I change later?" — yes, exactly.
- Mechanism: a done row keeps its live steppers / tap-to-edit. `updateWeight` / `editSetValue` →
  `updateSet` → `saveDraft`: the edit goes to `session_set_draft` only. The `set_log` row written by
  `logSet` is never updated. Next session, `startOrResume` prefills from `previousLogs` (= `set_log`
  of finished sessions), so the number he corrected comes back as the number he first logged.
  Drafts die with the session (`deleteDrafts` on end), so the correction is gone for good.
- Forward-fill makes it worse: `forwardFillWeight` on a done row still spreads the new weight to later
  0 kg undone sets of the same type.
- AVD evidence: logged Plank set 1 at 0 kg (`set_log` 48/0 = 0.0), tapped + on the done row →
  screen shows 1 kg with the green check, `set_log` still 0.0, drafts 122/123/124 = 1.0 (the two
  undone sets filled too). Screenshot `after_edit_done.png`.
- Since: Phase 3 (07/07). Never gated; not a regression of any batch.
- Fix direction (not done): an edit on a done row must rewrite its `set_log` row (weight, value) — or
  the row is locked and un-log → edit → re-log is the only path. The first matches what he does.

### W2. Cadence "keeps changing" (17/10 22:05) — two candidate causes, one confirmed
- (a) CONFIRMED display conflation, see C1: the pill shows the PARTNER's cadence whenever the marker
  is on the partner, so the same exercise reads two different cadences on different visits.
- (b) CLOSED (Allan, 18/10): Descartar reverting everything incl. cadence is intended — "option is
  there mostly to debug or if I made some mistake I don't want to keep". Not a cause.

### W3. "second exercise of the superset set to 10 kg, next time 16 on the first and 8 on the rest" (16/10)
- RESOLVED 18/10 from the Redmi backup (`workout_backup.json` exported over adb, sessions 101 and
  109): Inferiores 2, Stiff unilateral apoiado (we 301, second member of the Cadeira Flexora
  superset, PER_DUMBBELL, template 16/16/16/16 from my JSON). `set_log` of 09/10:
  set 0 = **16.0** kg (logged untouched at the template prefill, active 300 s = the 5-min cap, i.e.
  a long first set while he was adjusting), sets 1–3 = **8.0**. On 16/10 the prefill showed exactly
  those rows: 16 on the first, 8 on the rest. He then logged 10/10/10/10.
- So the number he "set to 10" on 09/10 never reached `set_log`: it was typed on rows already
  logged (W1). The app showed 10 on the drafts for the rest of that session, deleted them at the
  end, and the next session prefilled from the untouched log rows. Same root cause as W1; no
  separate fix.
- Side note for the next plan file: 16 kg per dumbbell on a unilateral stiff was my extrapolation;
  his working weight is 10.

## G — goal / rep ranges

### G1. Adjusting the goal overwrites the reps input (09/10 21:29)
- Reported: "Correct if 0, otherwise change goal and keep last set, maybe ask if keep?"
- Mechanism: `setSetTarget` → `editTemplate` → `startOrResume()` rebuilds every `SessionSet` with
  `value = draft ?: previousLog ?: targetValue`. A set never typed in this session and never logged
  in a finished one has no draft and no log, so its reps input follows the new target.
- AVD evidence: Russian Twist set 3 showed Reps 10 / Meta 10; set Meta to 15 → Reps became 15.
- Since: Phase 3 (the rebuild-from-three-sources design). Not a regression of a batch.
- Fix direction: rebuilds must keep what is on screen (write the current value as a draft before the
  rebuild, or only take the new target when the value is 0 — Allan's rule).

### G2. "PDF = rep ranges, old exercise = reps done; you conflated them" (09/10 21:45)
- This is a schema gap plus my import: the template has `targetValue`/`targetValueMax` (the range)
  and the session prefills "reps done" from `set_log` **by workoutExerciseId**. A new cycle's import
  creates new ids, so there is NO history to prefill from, and the only place I could put his last
  reps was `targetValue` — which is where the PDF range belongs. The fase 3 JSON therefore carries
  his last reps as the goal for repeated exercises and the PDF range only where he had no history.
- Fix direction: prefill history by `exerciseId` (the catalogue exercise), not `workoutExerciseId`,
  so a repeated exercise in a new cycle (or in two workouts — his 10/10 "sync" ask) starts from its
  last logged weight/reps while the template keeps the PDF range. Then regenerate the fase 3 file
  with PDF ranges only.

### G3. "Sync identical exercises across workouts / show last reps and weight" (10/10 20:41)
- Feature ask, same root as G2: history keyed per slot, not per exercise. One change serves both.
- Allan 18/10: sync needs a **profile** per exercise, named by its set pattern (e.g. "3x10",
  "1x14, 1x12, 2x10"), so the same exercise with a different set scheme keeps its own weights.
  Most exercises have only the default profile; profiles visible in edit mode only (space).
  Import picks the profile matching the file's set pattern, else the default; different scheme →
  new profile made by hand (or by me piloting the phone) when he asks.

### G4. Import order, stated 18/10 (supersedes my reading of G2)
- Reusing the catalogue exercise was right. The mistake: I changed the ranges to match what he
  had done before, instead of leaving his numbers alone and setting the RANGE from the PDF.
- Order: import → repeated exercise gets the same weight AND reps done from its last session →
  then change the range (min–max) to what the PDF says. With profiles: import the matching
  profile, hand-edit only the few that differ.

### G5. "The import took session n-1, not n" (Allan, 18/10) — CONFIRMED, stale export
- The fase 3 file was built on 06/10 from `/sdcard/Download/workout_sets (1).csv`, the newest sets
  CSV on the phone. That file ends at session 89, **29/09**. His sessions of 30/09 (FULLBODY B),
  03/10 (A) and 04/10 (B) were not in it, so every repeated exercise got the numbers of the session
  before the last one (e.g. Supino 17.5×20 / 20×17 from 29/09 instead of 17.5×21 / 21.5×12 from
  03/10; Panturrilha na parede 45/37/33 instead of 46/38/34). Checked against the backup: 21 of the
  25 fase 3 exercises with history had a newer session than the CSV knew.
- Root cause: I reused an old export instead of making a fresh one. Rule (memory): before building
  a plan file, export a fresh backup/CSV over adb (Settings → Backup completo, as done today) and
  check its last session date against the phone's last training day; never trust a file already
  in Downloads.

## C — supersets

### C1. "Supersets are using the same cadence; change one and both are altered" (10/10 21:25)
- Mechanism: Phase 34 A6 (`1723933`, 30/09): the page's cadence pill shows the cadence of the
  exercise whose set is NEXT when that is a superset partner (`shownTempo` in `SessionScreen`).
  The data is per exercise (`setExerciseTempo` writes only the page's own templates), the DISPLAY
  is not. Set A's cadence, look at B while the marker is on A → B reads A's cadence.
- AVD evidence: Plank set to 3-0-1-0; Russian Twist page shows "Cadência: 3-0-1-0" while its own
  templates have tempo "" (DB pulled).
- REGRESSION of Phase 34 A6 (shipped 0.8.0). A6 solved "a tempo defined on one partner vanished";
  this is the other side of that coin. Fix direction: show the page's own cadence always; if the
  partner's matters, show it as a second, labelled line.

## P — fase 3 plan file (my import, 06/10) — data, not app code

### P1. Alternating-arm and grip variants collapsed onto the base exercise (14/10 ×2)
- `~/Downloads/plan_fortalecimento_fase3.json`: "Supino reto halter alternado" → `wger_id: 75`
  (Benchpress Dumbbells, simultaneous). The PDF name is the alternating variant. Same exercise id
  as any simultaneous press he logged before → one weight history for two movements.
- "Tríceps na Polia com Barra Reta" → `wger_id: 1185` (Triceps Pushdown); "Tríceps na Polia com
  Corda" → no id (custom). Whether the rope one landed on an existing custom/alias needs the Redmi
  DB; the file itself does not tie them together.
- Allan 18/10: confirmed — wger 75 was wrong; "alternado" at the end makes it a different
  exercise, as always; not in the DB → custom. No correction of the current workout needed.
- Rule for the next file (in memory): a PDF variant word (alternado, unilateral, corda, barra
  reta, neutra, supinada…) is a DIFFERENT exercise; custom if the catalogue has no exact variant.
- P1 (variants) and P2 (supersets) are two separate findings; P2 is about the
  `superset_with_previous` flag only.

### P2. RETRACTED (18/10 afternoon) — the file DOES carry the supersets
- My morning listing read the key `superset_with_prev`; the file uses `superset_with_previous` and
  has 15 flags for the PDF's 13 "Exercícios combinados" groups. `tools/plan-check/check_plan.py`
  (written this afternoon) counts both correctly. P1 stands.

---

## Why it feels like Jenga, and what to change

Where the regressions came from (documented ones: Phase 28, Phase 35 ×4, this batch C1):
1. **One screen state, three sources of truth.** `SessionSet` is rebuilt from template ⊕ draft ⊕
   log with a precedence rule, and any plan edit rebuilds everything (`startOrResume`). W1, G1, W2b
   are all this one design. Every batch that touched the session added another branch to it.
2. **Display rules that borrow a neighbour's data** (A6 → C1; the 30/09 marker rule → S1). Each was a
   fix for one complaint with no statement of what it must NOT change.
3. **Tests cover pure helpers, not the flow.** 187 green unit tests, and none of them opens a session,
   edits a goal and reads the row back. `SessionViewModel` (1235 lines) and `SessionScreen` (1753)
   are the two most-changed files in the repo and the least tested.
4. **Big batches.** Phase 34 shipped 17 items in one version; 4 came back as regressions.

What to do, in order of payoff:
- **Collapse the three sources.** A done set reads from its log, an undone set from its draft, the
  template only on first paint. Edits on a done row write the log. Rebuilds never recompute a value
  that is already on screen. One rule, one place, written as a comment and as tests.
- **A ViewModel-level regression suite on in-memory Room** (Robolectric): one test per past
  feedback item that is a flow, e.g. "goal change keeps the typed reps", "edit after log changes
  the log", "prefill after session N equals session N's logs", "partner cadence never shown as own".
  Add the test BEFORE the fix, keep it forever. Phase 30 started this for helpers; extend to flows.
- **Blast radius line per item** in the feedback doc before coding: "touches X; must not change Y;
  guarded by test Z". If Y has no test, write it first.
- **One scripted emulator scenario per release** (`tools/emu_ui.py` + DB asserts, ~5 min): start
  CORE, log A1/B1, edit a done row, change a goal, set a cadence, end → assert `set_log`,
  `set_template`, screen texts. Run it on every version Allan installs; no eyeballing.
- **Small releases.** ≤3 behaviour changes per version Allan installs; a feedback batch becomes
  several versions, each tested by him in one gym session.
- **Plan-file validation script** against the PDF text (supersets count, names verbatim including
  variant words, ranges from the PDF only) before any import.
