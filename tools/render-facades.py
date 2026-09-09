#!/usr/bin/env python3
"""Renders docs/facades.tsv into docs/FACADE_INVENTORY.md (generated; do not edit)."""
import csv

rows = list(csv.DictReader(open('docs/facades.tsv', encoding='utf-8'), delimiter='\t'))
need = ['id', 'ref_group', 'ref_shots', 'java_screen', 'variant', 'gate', 'status']
assert list(rows[0].keys()) == need, list(rows[0].keys())

vocab = {'NOT RUN', 'EXECUTED', 'STATICALLY VERIFIED'}
for r in rows:
    assert r['status'] in vocab, (r['id'], r['status'])
    assert r['id'] and r['java_screen'] and r['gate'] and r['ref_shots'], r['id']
assert len({r['id'] for r in rows}) == len(rows), 'duplicate ids'

counts = {}
for r in rows:
    counts[r['status']] = counts.get(r['status'], 0) + 1

L = []
L.append('# Facade Inventory (generated — do not edit)')
L.append('')
L.append('Source: `docs/facades.tsv`. Regenerate with `python3 tools/render-facades.py`.')
L.append('')
L.append('Each row maps reference screenshots (the 99-image corpus) to one reusable Java')
L.append('screen and the gate that proves it. Status vocabulary is fixed by the release gate:')
L.append('`NOT RUN`, `EXECUTED`, `STATICALLY VERIFIED`. A static check is never `EXECUTED`.')
L.append('')
L.append('Gate legend: `GUI-GATE` = the screenshot harness navigates, renders and')
L.append('screenshots the destination on CI; `JOURNEY` = the harness additionally fires')
L.append('controls and checks persistent effects; `AUDIT` = a documented control-by-control')
L.append('trace against the running application.')
L.append('')
L.append('## Status')
L.append('')
for s in ('EXECUTED', 'STATICALLY VERIFIED', 'NOT RUN'):
    L.append('- %s: %d' % (s, counts.get(s, 0)))
L.append('')
L.append('## Rows')
L.append('')
L.append('| ID | Ref group | Ref shots | Java screen | Variant / adaptation | Gate | Status |')
L.append('|---|---|---|---|---|---|---|')
for r in rows:
    L.append('| %s | %s | %s | %s | %s | %s | %s |' % (
        r['id'], r['ref_group'], r['ref_shots'], r['java_screen'],
        r['variant'], r['gate'], r['status']))
L.append('')
open('docs/FACADE_INVENTORY.md', 'w', encoding='utf-8').write('\n'.join(L))
print('wrote docs/FACADE_INVENTORY.md (%d rows)' % len(rows))
