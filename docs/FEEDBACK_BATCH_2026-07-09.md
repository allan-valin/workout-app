# Feedback batch — 2026-07-09 (Allan, session QA)

Six items. Grounded in code. Status: **2,3,4,5,6 DONE** (compiles). **1 deferred** (needs device look). Not yet on-device tested.

## 1. End-training "..." menu misaligned (LOW confidence — needs device look)
- `SessionScreen.kt:400-409` — `MoreVert` IconButton + `DropdownMenu` with single `end_workout` item.
- Complaint: collapsed end option not aligned with "..." button, too far away.
- Likely fix: anchor/offset the DropdownMenu to the MoreVert, or the item renders offset. Verify on device before touching.

## 2. pt-BR keep-plan-changes wording (QUICK — string only)
- Dialog `SessionScreen.kt:346-363`: confirm=`keep_changes`, dismiss=`one_time_only`.
- pt-BR `one_time_only` = "Só desta vez" reads as *keep* just this time → ambiguous. Dismiss = DON'T keep changes.
- Fix: pt-BR `one_time_only` → "Descartar" (discard). German "Nur diesmal" is OK, leave. Consider en too.

## 3. Timer default shown + remove top-clock toggle (QUICK)
- Bottom rest/active TimerPanel default hidden: `SessionViewModel.kt:75 timerPanelVisible=false` → set `true`.
- WRONG feat added: top-clock hide button `SessionScreen.kt:386-394` (setShowClock/HideSource). Remove it. Top clock stays always shown (title already `showClock` default true).
- Net: bottom timer shown by default; no top hide button.

## 4. Set-type labels: language + naming + color (QUICK)
- Dropdown items show enum `ty.name` (English) in all locales: `SessionScreen.kt:634` → use `stringResource` per type. Verify editor dropdown too.
- Letter collision pt-BR: Aquecimento→A, "Até a falha"→A. Fix pt-BR `set_failure` "Até a falha" → "Falha" (→F).
- German: dropdown English-label bug same; failure "Bis Muskelversagen"→B (no collision but verbose) — confirm if shorten to "Muskelversagen"→M.
- Dropdown item font colored by type via `setTypeColor` (`:817`); strengthen colors (warmup/drop/etc a bit subtle).

## 5. Cadence (tempo) not saved / not shown + info popup (MEDIUM)
- Save bug: `WorkoutEditorScreen.kt:1102` saves tempo only `onFocusChanged` (field blur). Type + Save/back without blurring = lost. Fix: also commit on save/confirm.
- Shown during training reads from saved tempo (`SessionScreen.kt:552`) — works once save fixed.
- New: info button in the tempo row → popup explaining each number (eccentric–pause–concentric–pause). Separate from normal `i` button.

## 6. kg/reps field width + delete "x" (MEDIUM)
- Narrow weight/value OutlinedButtons: reps max 2 digits (3 if secs), kg max 3 digits (`SessionScreen.kt:656-681`).
- Freed space → rightmost "x" delete IconButton per set row (currently long-press only, `:610`). Mirror editor delete. Header spacer at `:594`.
