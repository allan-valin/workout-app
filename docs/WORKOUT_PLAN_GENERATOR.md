# Workout Plan Generator — Reference for Claude (browser)

You are generating a **workout plan JSON file** for Allan's personal Android workout app.
The app imports this exact format. Follow the schema strictly — the importer is strict-but-forgiving:
unknown fields are ignored, but wrong types or missing required fields reject that item and it
gets reported to the user.

## How to deliver

Output a single JSON code block (or downloadable `.json` file) named like
`plan_<short-name>.json`. Allan saves it to his phone and imports it via the app's
"Import plan" button. No other packaging needed.

## Top-level schema

```json
{
  "schema_version": 1,
  "plan": {
    "name": "Push Pull Legs",
    "active": true,
    "workouts": [
      {
        "name": "Push Day",
        "days_of_week": ["MON", "THU"],
        "exercises": [
          {
            "match": {
              "wger_id": 192,
              "names": ["Bench Press", "Supino reto", "Bankdrücken"]
            },
            "weight_mode": "PER_SIDE",
            "bar_weight_kg": 20,
            "note": "Pause 1s at chest",
            "sets": [
              { "type": "WARMUP", "weight_kg": 20, "value": 12, "unit": "REPS", "rest_secs": 60 },
              { "type": "NORMAL", "weight_kg": 40, "value": 10, "value_max": 12, "unit": "REPS", "rest_secs": 120 },
              { "type": "NORMAL", "weight_kg": 40, "value": 10, "value_max": 12, "unit": "REPS", "rest_secs": 120 },
              { "type": "FAILURE", "weight_kg": 40, "value": 8,  "unit": "REPS", "rest_secs": 180 }
            ]
          },
          {
            "match": { "wger_id": 195, "names": ["Push Up", "Flexão", "Liegestütz"] },
            "superset_with_previous": true,
            "sets": [
              { "type": "NORMAL", "weight_kg": 0, "value": 12, "value_max": 15, "unit": "REPS", "rest_secs": 120 }
            ]
          },
          {
            "match": { "names": ["Plank", "Prancha", "Unterarmstütz"] },
            "custom_fallback": {
              "primary_muscle": "abs",
              "secondary_muscles": ["shoulders"],
              "is_cardio": false,
              "description": "Forearms on floor, straight line head to heels, brace core."
            },
            "weight_mode": "TOTAL",
            "sets": [
              { "type": "NORMAL", "weight_kg": 0, "value": 60, "unit": "SECS", "rest_secs": 60 }
            ]
          }
        ]
      }
    ]
  }
}
```

## Field rules

| Field | Rules |
|---|---|
| `schema_version` | Always `1` (required). |
| `plan.name` | Required, non-empty. |
| `plan.active` | Optional, default `true`. |
| `workouts[].name` | Required. |
| `workouts[].days_of_week` | Optional array of `MON TUE WED THU FRI SAT SUN`. Empty/omitted = unassigned. |
| `exercises[].match.wger_id` | Preferred when you know it. Integer wger exercise-base ID. |
| `exercises[].match.names` | Always provide. Names in any of en / pt-BR / de; app matches case-insensitively against names AND aliases. First entry = display preference. |
| `exercises[].custom_fallback` | Optional. If no match is found, app offers to create this custom exercise. Without it, unmatched exercises are skipped and reported. Include it for anything unusual. |
| `weight_mode` | `TOTAL` (default) \| `PER_DUMBBELL` \| `PER_SIDE`. |
| `bar_weight_kg` | Only meaningful with `PER_SIDE`. Default 20. |
| `superset_with_previous` | Optional bool (default false). This exercise alternates with the one right before it: set 1 of the previous, set 1 of this (no rest between), rest, set 2 of the previous, … Never set it on the first exercise of a workout. **Only the SECOND (and later) members of a group carry the flag — never the first.** See the pitfall below. |
| `sets[].type` | `WARMUP` \| `NORMAL` \| `FAILURE` \| `DROP`. (Legacy `SUPERSET` still accepted; prefer `superset_with_previous` on the exercise.) |
| `sets[].weight_kg` | Number ≥ 0. Use 0 for bodyweight/cardio. Increments of 1.25 preferred. |
| `sets[].value` + `unit` | `REPS` (count) or `SECS` (timed set → in-app countdown). |
| `sets[].value_max` | Optional top of the rep range (`REPS` only), e.g. value 10 + value_max 12 = "10–12 reps". Drives the app's progression suggestions — include it for NORMAL sets. |
| `sets[].rest_secs` | Rest AFTER this set. Sensible defaults: 60 warmup, 90–120 hypertrophy, 180 strength. |

`primary_muscle` / `secondary_muscles` values (custom_fallback):
`chest, lats, upper_back, lower_back, traps, front_delts, side_delts, rear_delts, biceps, triceps, forearms, abs, obliques, quads, hamstrings, glutes, calves, adductors, abductors, neck, full_body, cardio`

### Superset pitfall (this broke a real import)

A superset pair `A + B` is encoded as: `A` WITHOUT the flag, `B` WITH `"superset_with_previous": true`.
A circuit `A + B + C` is: `A` without, `B` and `C` with.

**WRONG** (both members flagged — `A` fuses with whatever exercise came before it, and
consecutive pairs chain into one giant superset covering the rest of the workout):

```json
{ "match": { "names": ["A"] }, "superset_with_previous": true, ... },
{ "match": { "names": ["B"] }, "superset_with_previous": true, ... }
```

**RIGHT**:

```json
{ "match": { "names": ["A"] }, ... },
{ "match": { "names": ["B"] }, "superset_with_previous": true, ... }
```

Sanity check before delivering: read the flags as a sequence — every `true` attaches to
the exercise directly above it. A run of `true`s is one big alternating circuit; if you
didn't mean a circuit, you flagged a first member.

## How the app matches exercises (read this before writing any `match`)

The app matches OFFLINE against its own bundled databases, in this order:

1. `wger_id` (if present and known locally),
2. exact name match in the **wger** database (multi-language, has aliases),
3. exact name match in **free-exercise-db** ("fed" — a SECOND database bundled with the
   app; English-only names like "Elliptical", "Standing Calf Raise"; most entries have
   images),
4. Allan's **existing custom exercises** (from earlier imports/manual creation),
5. only then does `custom_fallback` create a NEW custom exercise.

Consequences for you:

- **Never browse wger.de or any website to match** — it's pointless (the app matches
  locally) and wger blocks crawlers anyway. Just emit good `names`.
- **Do NOT default to custom_fallback for everything.** Custom exercises have no images
  and no translations. A plan where most exercises are custom is a conversion failure.
  Reach for well-known English exercise names (they hit wger or fed) plus the pt-BR/de
  variants.
- Include the plain-English gym name in `names` even when the source plan is
  Portuguese — "Elíptico" alone misses fed's "Elliptical"; `["Elíptico", "Elliptical"]`
  hits it.
- Custom exercises Allan already has are matched by exact name — if he tells you he has
  a custom exercise for something, put its exact name in `names`.

### Approximate matches need a note

When the source plan names a VARIANT the databases don't have (e.g. "panturrilha em pé
apoiado na parede", "SMR on foam roller"), match the closest base exercise and put the
missing detail in `note` — never silently substitute:

```json
{
  "match": { "names": ["Panturrilha em pé", "Standing Calf Raise"] },
  "note": "Na parede: apoie as mãos na parede, tronco inclinado (variação do plano)."
}
```

If no base exercise is even close, use `custom_fallback` with a full `description`.

### Cardio blocks

- The machine is the exercise; keep its NAME short (`["Elíptico", "Elliptical"]`) — a
  paragraph in the name breaks the UI. The full prescription text goes in `note`.
- Structure blocks as sets: warmup = one `WARMUP` `SECS` set (`rest_secs: 0`); intervals
  = alternating `SECS` sets where the "off" interval is either its own set or the
  `rest_secs` of the "on" set; cooldown = final `WARMUP`/`NORMAL` `SECS` set.
- `is_cardio: true` only inside `custom_fallback` (it describes a NEW exercise).

## Conversion workflow (PDF/photo → JSON)

1. Extract the raw exercise list per workout day first.
2. Show Allan a **match table** before emitting any JSON: source name → proposed
   `names` array → expected source (wger / fed / his custom / NEW custom) → note (for
   variants). Ask him to confirm or correct the uncertain rows — especially anything
   you'd create as a new custom exercise.
3. Only after confirmation, emit the JSON. This mirrors the planned in-app per-exercise
   import wizard; until that exists, you are the wizard.

## Content guidelines when designing plans

- Ask Allan for: goal (strength/hypertrophy/endurance/rehab), days per week, session length, available equipment, exercises to avoid (injuries).
- Cardio-only workouts are fine (`is_cardio: true`, `unit: "SECS"` or reps for intervals).
- Prefer well-known wger exercises; always include the three-language `names` array so matching works regardless of Allan's app language.
- Keep one JSON file = one plan. Multiple workouts (days) inside it.
- Don't invent wger IDs. Omit `wger_id` if unsure — name matching + `custom_fallback` is safe; a wrong ID silently attaches the wrong exercise.
- Validate before delivering: valid JSON, every exercise has `match.names`, every set has `type/weight_kg/value/unit`, and the superset flags pass the sanity check above.

## History CSV (for analysis requests)

Allan may paste exported CSVs. Columns:

```
sets:     session_id, date, plan, workout, exercise_id, exercise_name, set_index,
          set_type, weight_kg, weight_mode, value, unit, active_secs, rest_secs
sessions: session_id, workout, started_at, ended_at, status, active_secs, rest_secs,
          idle_secs, total_volume_kg
body:     date, weight_kg
```

Volume convention: `weight_kg × value` per rep-set, attributed to the exercise's primary muscle.
`PER_DUMBBELL` weight is per hand (×2 for volume); `PER_SIDE` total = `bar_weight_kg + 2 × weight_kg`.
