# Feedback batch — 09/10–17/10 2026 (Allan, fase 3 on 0.8.2/0.8.3)

**Status 2026-10-18: DIAGNOSED, NOTHING FIXED.** Allan asked for root causes first, fixes later.
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
- (b) PLAUSIBLE revert at session end: a cadence edit mid-session marks the plan "changed"; the end
  dialog says "Você mudou séries durante o treino. Manter no plano ou aplicar só desta vez?" with
  buttons **Descartar / Manter**. Tapping Descartar restores the entry snapshot — cadence included —
  though the text only talks about séries. Verified on the AVD: Plank tempo 3-0-1-0 and the goal
  15 both reverted after Descartar. Needs Allan: which button does he press?

### W3. "second exercise of the superset set to 10 kg, next time 16 on the first and 8 on the rest" (16/10)
- NOT reproducible without his data. The prefill is `draft → newest finished log for that setIndex →
  template`; nothing in it halves or doubles. Candidates, in order: W1 (he corrected a logged row —
  the log kept the old value); the template weights from the fase 3 JSON (my extrapolation put
  different numbers on set 1 and the rest for several exercises) showing through because the
  previous session was discarded or the exercise was re-imported/swapped (new `workoutExerciseId`
  → no history); a weight-mode change (per side ↔ total) reinterpreting stored numbers.
- Evidence needed: `adb pull` the Redmi DB (or a backup JSON) and look at `set_log` for that
  workoutExerciseId. Do not guess a fix before that.

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
- Rule for the next file (added to memory): a PDF variant word (alternado, unilateral, corda, barra
  reta, neutra, supinada…) is a DIFFERENT exercise; custom if the catalogue has no exact variant.

### P2. Zero supersets in the file; the PDF has thirteen
- `pdftotext` of "Rotina: FORTALECIMENTO - fase 3.pdf": 13 × "Exercícios combinados". The JSON has
  no `superset_with_previous` at all. Whatever supersets exist on the phone, Allan set by hand.
- Rule for the next file: `count("Exercícios combinados") == count(superset_with_previous: true)`,
  checked by script before import.

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
