#!/usr/bin/env python3
"""Validate a workout plan JSON against the trainer's PDF (and, optionally, the phone backup)
BEFORE importing it. Written after the fase 3 import (06/10/2026) shipped with 0 of 13 supersets,
a simultaneous press matched for an alternating one, and weights from a stale CSV
(docs/FEEDBACK_BATCH_2026-10-17.md P1, P2, G5).

  check_plan.py plan.json "Rotina.pdf" [--backup workout_backup.json] [--snapshot wger_snapshot.json]

Checks (ERROR blocks the import, WARN is for the eye):
  supersets   PDF "Exercícios combinados" groups vs superset_with_previous flags (flags >= groups)
  names       every PDF exercise name appears verbatim in the file (match.names / custom name)
  variants    a variant word in the PDF name (alternado, corda, unilateral, …) must be in the matched
              wger exercise's English name too — otherwise the base exercise was matched (custom instead)
  sets        set count, reps / range, "cada lado" → unilateral, per PDF "Séries:" line
  weights     (with --backup) set weight_kg == his last FINISHED session for that exercise + set index;
              the backup's newest session must be recent (a stale export caused G5)
"""
import json, re, subprocess, sys, unicodedata, datetime as dt
from collections import Counter

VARIANTS = {  # pt word in the PDF name -> regex that must appear in the wger English name
    'alternad': r'alternat', 'unilateral': r'single|one[- ]?(arm|leg)|unilateral|alternat',
    'corda': r'rope', 'barra reta': r'straight|bar', 'neutra': r'neutral|hammer', 'supinad': r'supinat|underhand|reverse',
    'pronad': r'pronat|overhand', 'inclinad': r'incline', 'declinad': r'decline', 'halter': r'dumbbell|db\b',
    'máquina': r'machine', 'polia': r'cable|pulley', 'elástico': r'band', 'martelo': r'hammer', 'sumô': r'sumo',
}

def norm(s):
    s = unicodedata.normalize('NFKD', s).encode('ascii', 'ignore').decode().lower()
    return re.sub(r'\s+', ' ', s).strip()

EQUIPMENT_WORDS = {'halter', 'máquina', 'polia', 'elástico'}  # WARN only: often implied by the wger name

def pdf_entries(pdf):
    """One entry per exercise. A "Série de aquecimento / preparação" block under an exercise is a
    sub-block (its sets add to the exercise); so is any Séries line separated from the previous
    one by fewer than 3 blank lines. A name may wrap over several lines."""
    txt = subprocess.run(['pdftotext', '-layout', pdf, '-'], capture_output=True, text=True).stdout
    lines = [l.strip() for l in txt.splitlines()]
    LABEL = r'(Séries|Carga|Intervalo|Instruções|Tempo|Alterne|Exercícios)'
    entries, groups, prev_series_line = [], 0, -100
    for i, l in enumerate(lines):
        if l.startswith('Exercícios combinados'):
            groups += 1; continue
        m = re.match(r'Séries:\s*(.*)', l)
        if not m: continue
        spec = m.group(1)
        sm = re.match(r'(\d+)\s*x\s*(\d+)?(?:\s*a\s*(\d+))?', spec)
        block = dict(spec=spec, sets=int(sm.group(1)) if sm else None,
                     reps=int(sm.group(2)) if sm and sm.group(2) else None,
                     reps_max=int(sm.group(3)) if sm and sm.group(3) else None,
                     unilateral='cada lado' in spec)
        # name = the contiguous non-empty, non-label lines right above (wrapped names join)
        j = i - 1
        while j >= 0 and not lines[j]: j -= 1
        name_lines = []
        while j >= 0 and lines[j] and not re.match(LABEL, lines[j]):
            name_lines.insert(0, lines[j]); j -= 1
        name = ' '.join(name_lines)
        between = lines[prev_series_line:i]
        gap = max((len(list(g)) for k, g in __import__('itertools').groupby(between, key=bool) if not k), default=0)
        sub = entries and (name.startswith('Série') or gap < 3)
        if sub:
            e = entries[-1]
            e['blocks'].append(block); e['sets'] = (e['sets'] or 0) + (block['sets'] or 0)
            e['unilateral'] = e['unilateral'] or block['unilateral']
        else:
            entries.append(dict(name=name, blocks=[block], sets=block['sets'], reps=block['reps'],
                                reps_max=block['reps_max'], unilateral=block['unilateral'], group=groups,
                                spec=spec))
        prev_series_line = i
    return entries, groups

def plan_exercises(plan):
    for w in plan['plan']['workouts']:
        for e in w['exercises']:
            m = e.get('match', {})
            names = list(m.get('names') or [])
            if e.get('custom_fallback', {}).get('name'): names.append(e['custom_fallback']['name'])
            yield w['name'], e, names

def main():
    args = sys.argv[1:]
    backup = snapshot = None
    if '--backup' in args: backup = args[args.index('--backup') + 1]
    if '--snapshot' in args: snapshot = args[args.index('--snapshot') + 1]
    plan = json.load(open(args[0])); pdf = args[1]
    errors, warns = [], []
    entries, groups = pdf_entries(pdf)
    ex = list(plan_exercises(plan))
    flags = sum(1 for _, e, _ in ex if e.get('superset_with_previous'))
    if flags < groups:
        errors.append(f'supersets: PDF has {groups} "Exercícios combinados" groups, file has {flags} superset_with_previous flags')
    # names
    file_names = {norm(n) for _, _, names in ex for n in names}
    by_name = {}
    for wname, e, names in ex:
        for n in names: by_name.setdefault(norm(n), (wname, e))
    for en in entries:
        if en['name'] and norm(en['name']) not in file_names:
            errors.append(f'names: PDF "{en["name"]}" ({en["spec"]}) not in the file verbatim')
    # variants + sets
    wger = {}
    if snapshot:
        for x in json.load(open(snapshot))['exercises']:
            wger[x['id']] = (x.get('translations', {}).get('en') or {}).get('name', '')
    for en in entries:
        hit = by_name.get(norm(en['name'] or ''))
        if not hit: continue
        wname, e = hit
        pdfn = norm(en['name'])
        wid = e.get('match', {}).get('wger_id')
        for word, rx in VARIANTS.items():
            if norm(word) in pdfn and wid is not None:
                enname = wger.get(wid, '')
                if snapshot and not re.search(rx, enname, re.I):
                    msg = f'variants: "{en["name"]}" matched to wger {wid} "{enname}" — no "{word}" counterpart'
                    (warns if word in EQUIPMENT_WORDS else errors).append(msg + ('' if word in EQUIPMENT_WORDS else '; make it custom'))
                elif not snapshot:
                    warns.append(f'variants: "{en["name"]}" has a variant word and wger_id {wid}; check the wger name (pass --snapshot)')
        sets = e.get('sets', [])
        if en['sets'] is not None and len(sets) != en['sets']:
            warns.append(f'sets: "{en["name"]}" PDF {en["spec"]} = {en["sets"]} sets, file has {len(sets)}')
        work = en['blocks'][-1]
        en['reps'], en['reps_max'] = work['reps'], work['reps_max']
        if en['reps'] is not None:
            for s in sets:
                if s.get('unit', 'REPS') != 'REPS' or s.get('type') == 'WARMUP': continue
                if s.get('value') != en['reps'] or (s.get('value_max') or None) != en['reps_max']:
                    rng = f'{en["reps"]}' + (f'–{en["reps_max"]}' if en['reps_max'] else '')
                    warns.append(f'range: "{en["name"]}" PDF {rng}, file set {sets.index(s)+1} = {s.get("value")}' + (f'–{s.get("value_max")}' if s.get('value_max') else '')); break
        if en['unilateral'] and not e.get('unilateral'):
            errors.append(f'unilateral: "{en["name"]}" is "cada lado" in the PDF, file has no unilateral flag')
    # weights vs backup
    if backup:
        b = json.load(open(backup))
        S = {s['id']: s for s in b['sessions']}
        newest = max(s['startedAt'] for s in b['sessions'])
        age = (dt.datetime.now() - dt.datetime.fromtimestamp(newest / 1000)).days
        print(f'backup: newest session {dt.datetime.fromtimestamp(newest/1000):%Y-%m-%d}, {age} days old')
        if age > 3: errors.append(f'backup: newest session is {age} days old — export a fresh one before building the file (G5)')
        custom = {}
        for t in b['customTranslations']: custom.setdefault(norm(t['name']), t['exerciseId'])
        last = {}  # exerciseId -> (startedAt, {setIndex: (weight, value)})
        for l in b['setLogs']:
            s = S[l['sessionId']]
            if s['status'] not in ('FINISHED', 'AUTO_ENDED'): continue
            cur = last.get(l['exerciseId'])
            if cur is None or s['startedAt'] > cur[0]: last[l['exerciseId']] = (s['startedAt'], {})
            if last[l['exerciseId']][0] == s['startedAt']: last[l['exerciseId']][1][l['setIndex']] = (l['weightKg'], l['value'])
        for wname, e, names in ex:
            wid = e.get('match', {}).get('wger_id')
            eid = f'wger:{wid}' if wid is not None else next((custom[norm(n)] for n in names if norm(n) in custom), None)
            if eid is None or eid not in last: continue
            when, rows = last[eid]
            for i, s in enumerate(e.get('sets', [])):
                if i in rows and abs(rows[i][0] - s.get('weight_kg', 0)) > 1e-6:
                    warns.append(f'weight: {wname} / "{names[0]}" set {i+1}: file {s.get("weight_kg")} kg, last log ({dt.datetime.fromtimestamp(when/1000):%d/%m}) {rows[i][0]} kg')
    for w in warns: print('WARN ', w)
    for e in errors: print('ERROR', e)
    print(f'\n{len(errors)} errors, {len(warns)} warnings; {len(entries)} PDF entries, {len(ex)} file exercises')
    sys.exit(1 if errors else 0)

if __name__ == '__main__':
    main()
