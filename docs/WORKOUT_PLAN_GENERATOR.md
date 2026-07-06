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
              { "type": "NORMAL", "weight_kg": 40, "value": 10, "unit": "REPS", "rest_secs": 120 },
              { "type": "NORMAL", "weight_kg": 40, "value": 10, "unit": "REPS", "rest_secs": 120 },
              { "type": "FAILURE", "weight_kg": 40, "value": 8,  "unit": "REPS", "rest_secs": 180 }
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
| `sets[].type` | `WARMUP` \| `NORMAL` \| `FAILURE` \| `DROP` \| `SUPERSET`. Consecutive `SUPERSET` sets are grouped with no rest between them. |
| `sets[].weight_kg` | Number ≥ 0. Use 0 for bodyweight/cardio. Increments of 1.25 preferred. |
| `sets[].value` + `unit` | `REPS` (count) or `SECS` (timed set → in-app countdown). |
| `sets[].rest_secs` | Rest AFTER this set. Sensible defaults: 60 warmup, 90–120 hypertrophy, 180 strength. |

`primary_muscle` / `secondary_muscles` values (custom_fallback):
`chest, lats, upper_back, lower_back, traps, front_delts, side_delts, rear_delts, biceps, triceps, forearms, abs, obliques, quads, hamstrings, glutes, calves, adductors, abductors, neck, full_body, cardio`

## Content guidelines when designing plans

- Ask Allan for: goal (strength/hypertrophy/endurance/rehab), days per week, session length, available equipment, exercises to avoid (injuries).
- Cardio-only workouts are fine (`is_cardio: true`, `unit: "SECS"` or reps for intervals).
- Prefer well-known wger exercises; always include the three-language `names` array so matching works regardless of Allan's app language.
- Keep one JSON file = one plan. Multiple workouts (days) inside it.
- Don't invent wger IDs. Omit `wger_id` if unsure — name matching + `custom_fallback` is safe; a wrong ID silently attaches the wrong exercise.
- Validate before delivering: valid JSON, every exercise has `match.names`, every set has `type/weight_kg/value/unit`.

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
