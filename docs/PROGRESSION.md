# Progression suggestions — rationale & sources

`data/ProgressionEngine.kt` implements **double progression** with a 2-for-2-style
confirmation. Suggestions are hints only (speech-bubble chip in the session screen);
the user applies or dismisses them.

## The rules

Working sets = REPS sets of type NORMAL or FAILURE. Warmup, drop and timed sets are ignored.

1. **Add weight** — last two sessions used the same weight and every working set reached
   the top of its rep range (no explicit range → target + 2 reps, the "2-for-2" rule).
   Increment: 2.5 % (upper body) / 5 % (lower body) of the current weight, rounded to
   1.25 kg plate steps, minimum one step (2.5 kg for lower-body lifts).
2. **Add a rep** — last session hit at least the bottom of the range on every working
   set but not yet the top everywhere.
3. Otherwise — no suggestion.

## Why these numbers

- ACSM position stand on progression models: *"when training at a specific RM load, a
  2–10 % increase in load [should] be applied when the individual can perform the current
  workload for one to two repetitions over the desired number"* — the engine sits at the
  conservative end (2.5–5 %) because the app targets hypertrophy rep ranges and small
  (1.25 kg) plates are assumed available. (Kraemer et al. 2002; reaffirmed 2009/2011,
  Medicine & Science in Sports & Exercise.)
- The **2-for-2 rule** (Baechle & Earle, NSCA *Essentials of Strength Training and
  Conditioning*; reviewed in Suchomel et al. 2021, *Sports Medicine*): increase load when
  the trainee exceeds the rep target by two in the final set for two consecutive sessions.
  The engine's "two consecutive sessions at the ceiling" check is this rule applied to a
  rep-range scheme.
- **Load vs. rep progression are equally valid**: within-subject and randomized trials
  found no meaningful difference in strength/hypertrophy between progressing load and
  progressing repetitions (Chaves et al. 2024, *Int J Sports Med*; Plotkin et al. 2022,
  *PeerJ*). Hence the engine offers +reps inside the range and +load at the ceiling, and
  the user chooses.
- Rep-range targets themselves are flexible: adaptations occur across a wide loading
  spectrum (Schoenfeld et al. 2021, *Sports*; Lopez et al. 2020, *MSSE*), so ranges are
  user-defined per set template and the engine never edits them.

## Consensus.app paper links (retrieved 2026-07-07)

- Chaves et al. 2024 — https://consensus.app/papers/details/42c71acb7eab533b82745124fe669ce7/
- Plotkin et al. 2022 — https://consensus.app/papers/details/a809c49f2bf2547ea20d8c2b653d22b3/
- Schoenfeld et al. 2021 — https://consensus.app/papers/details/9f40f0c24644507bb53ee93b3b88d779/
- ACSM position stand (2011 print) — https://consensus.app/papers/details/0d91987368d95bfb9c45048b1a000be9/
- Kraemer et al. 2002 ACSM — https://consensus.app/papers/details/1a4b78f23f195c66bac3fabd3ff7cb33/
- Suchomel et al. 2021 — https://consensus.app/papers/details/1fcf207fa12c56baa217d3f57da1ae9d/
- Lopez et al. 2020 — https://consensus.app/papers/details/83f3bcdaa4a959c3ba2c048afab2b355/
