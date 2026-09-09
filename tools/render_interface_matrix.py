#!/usr/bin/env python3
"""Render docs/INTERFACE_FUNCTION_MATRIX.md from docs/interface-function-matrix.tsv.

The TSV is the source of truth (policed by InterfaceFunctionMatrixTest); this script
only formats it so the narrative document can never disagree with the data.
"""
import csv, collections, pathlib, datetime

root = pathlib.Path(__file__).resolve().parent.parent
rows = list(csv.DictReader(open(root / 'docs/interface-function-matrix.tsv', encoding='utf-8'), delimiter='\t'))
counts = collections.Counter(r['status'] for r in rows)

GROUPS = [
    ('K', 'Keywords list — `Keyword/keywords_list.html` · `keywords-list-page.js`'),
    ('KD', 'Keyword detail — `Keyword/keyword_detail.html`'),
    ('C', 'Categories list — `Category/categories_list.html` · `categories-list-page.js`'),
    ('CW', 'Category words — `Category/category_words.html`'),
    ('CD', 'Category detail (Java-only reverse view)'),
    ('W', 'Words list — `Word/Word_list.html` · `words-list-page.js`'),
    ('WD', 'Word detail — `Word/word_detail.html`'),
    ('F', 'File detail & content — `File/file_detail.html`, `File/full_content.html`'),
    ('S', 'Search — `Search/search_enhanced.html`, `advanced_search.html`, saved searches'),
    ('AR', 'Archives — `Archive/*.html`'),
    ('SR', 'Sources — `Sources/*.html`'),
    ('SD', 'Sides / Aspects — `Side/*.html`'),
    ('FL', 'File library — `File/files_list.html`'),
    ('UP', 'Upload — `Upload/*.html`'),
    ('BA', 'Batch analysis — `Batch/*.html`'),
    ('DB', 'Dashboards, charts, analysis, path analysis, email words, performance'),
    ('NT', 'Notifications'),
    ('IE', 'Import / export'),
    ('ST', 'Settings'),
    ('SU', 'Setup / maintenance'),
    ('AI', 'AI (optional, local, manual, read-only)'),
    ('G', 'Shell: navigation, errors, partials, toasts'),
    ('SN', 'Search result navigation (Unit 5 — element id → File Detail)'),
]

def prefix(rid):
    return ''.join(ch for ch in rid if ch.isalpha())

def esc(s):
    return s.replace('|', '\\|')

out = []
out.append('# Interface → Function Matrix\n')
out.append('Machine-readable source: **`docs/interface-function-matrix.tsv`** '
           '(%d rows, 16 audit columns + id). This page is rendered from it by '
           '`tools/render_interface_matrix.py`; `InterfaceFunctionMatrixTest` fails the build if the TSV '
           'names a Java handler, facade, database operation or test that does not exist, if a screen or '
           'button label in the JavaFX interface has no row, or if a non-VERIFIED row lacks an explanation.\n' % len(rows))
out.append('Every interactive element of the reference (`RussellTCrivello/file_analysis`: templates, '
           '`static/js/pages/*.js`, `Api/routes`, `Api/services`, `database/`) is traced forward to the Java '
           'control that reproduces its observable behaviour, and every Java control is traced back to the '
           'reference behaviour it exists for. The reference is a behavioural specification only — no Python, '
           'Flask route, template or JavaScript is executed, embedded or called by the Java application '
           '(`ArchitectureInvariantsTest#noWebApiOrPythonRuntimeDependency`).\n')
out.append('## Status vocabulary\n')
out.append('| Status | Meaning | Rows |\n|---|---|---|')
meaning = {
 'VERIFIED': 'The Java control performs the same observable operation on the same data (case.db / Lucene) and an executable test named in the row exercises it.',
 'ADAPTED': 'Same purpose and same stored result, different mechanism appropriate to a desktop (dialog instead of modal, table sort instead of `?sort=` reload, search on the Search destination instead of an inline filter). The note says what differs.',
 'LIMITED': 'Part of the reference behaviour is reproduced; the missing part and the reason are stated.',
 'UNSUPPORTED': 'Deliberately not reproduced, with the reason (evidence is never deleted; render-format toggles carry no information; a flag nothing reads).',
 'REFERENCE-INERT': 'The reference control does nothing observable (no route behind the handler, or a stored flag nothing evaluates). Reproducing it would be a fake feature.',
 'ENVIRONMENT-LIMITED': 'Implemented and covered, but the confirming step needs something this build environment lacks (a display for a GUI click-through). None at present: GUI click-through is recorded per suite in `VERIFICATION_REPORT.md` instead.',
 'NOT RUN': 'Implemented and statically wired, but the JavaFX layer has never been compiled or executed here, so no test proves the control itself. The note names the test behind the operation and what remains.',
}
for st in ['VERIFIED','ADAPTED','LIMITED','UNSUPPORTED','REFERENCE-INERT','ENVIRONMENT-LIMITED','NOT RUN']:
    out.append('| **%s** | %s | %d |' % (st, meaning[st], counts.get(st,0)))
out.append('')
out.append('Nothing is ABSENT: every reference element has a Java destination or an explained status.\n')

out.append('## What VERIFIED does and does not mean here\n')
out.append('**Read this before quoting a status.** Every status in this table is established by '
           '*static resolution plus a headless test*: the control exists in the JavaFX source, its handler '
           'and facade resolve to real symbols, and the named test exercises the underlying operation '
           'against a real `case.db` and a real Lucene index.\n')
out.append('What no row in this table establishes is that a human clicked the control in a running '
           'application. JavaFX could not be obtained in the build environment used for this pass, so the '
           'UI layer has never been compiled or executed. A row therefore means **"the operation behind '
           'this control is proven, and the control is wired to it in source"** — not "this button has been '
           'observed to work".\n')
out.append('The gap is narrow but real, and it is exactly the class of defect static resolution cannot see: '
           'a handler attached to the wrong control, a value formatted into the wrong column, a dialog that '
           'never opens, a listener that is registered twice. Those require §4 of the acceptance directive — '
           'launching the real application — and that step is **NOT RUN**.\n')

out.append('## Columns\n')
out.append('`reference_destination`, `reference_template`, `html_element`, `js_handler`, `js_api_operation`, '
           '`python_route`, `python_backend_operation` describe the reference. `java_destination`, `java_control`, '
           '`java_handler`, `java_facade`, `db_index_operation`, `expected_visible_result`, `test`, `status`, `note` '
           'describe the Java application. Java symbols are `Class#method` and are resolved against the source tree by the test.\n')

out.append('## Rows that are not VERIFIED\n')
for st in ['ADAPTED','LIMITED','UNSUPPORTED','REFERENCE-INERT','NOT RUN']:
    sub=[r for r in rows if r['status']==st]
    if not sub: continue
    out.append('### %s (%d)\n' % (st, len(sub)))
    out.append('| Id | Reference element | Java destination | Why |\n|---|---|---|---|')
    for r in sub:
        out.append('| %s | %s | %s | %s |' % (r['id'], esc(r['html_element']), esc(r['java_destination']), esc(r['note'])))
    out.append('')

out.append('## Full matrix\n')
for pre, title in GROUPS:
    sub=[r for r in rows if prefix(r['id'])==pre]
    if not sub: continue
    out.append('### %s\n' % title)
    out.append('| Id | Reference element | JS handler | Route → backend op | Java destination / control | Java handler → facade | DB / index op | Visible result | Test | Status |')
    out.append('|---|---|---|---|---|---|---|---|---|---|')
    for r in sub:
        out.append('| %s | %s | %s | %s → %s | %s / %s | %s → %s | %s | %s | %s | %s |' % (
            r['id'], esc(r['html_element']), esc(r['js_handler']),
            esc(r['python_route']), esc(r['python_backend_operation']),
            esc(r['java_destination']), esc(r['java_control']),
            esc(r['java_handler']), esc(r['java_facade']), esc(r['db_index_operation']),
            esc(r['expected_visible_result']), esc(r['test']), r['status']))
    out.append('')

out.append('## Relationship model behind the rows\n')
out.append('''```
FILE (path, content, hash, metadata)
  │  path_keyword(path_id, keyword_id, hits)      ← reference keywords_paths
  ├──── KEYWORD (phrase, ≥ 2 words) ──── category_id ──── CATEGORY (one word)
  │  path_word(path_id, word_id, hits)            ← reference words_paths
  ├──── CATEGORY WORD (one word) ──── word_category ────── CATEGORY
  │  path_category(path_id, category_id)          ← reviewer attribution
  └──── CATEGORY
```

* Edges are derived by `RelationshipAnalyzer` from stored content when material is registered and on demand ("Update associations"); re-running is idempotent (`RelationshipModelTest`).
* Every count is `COUNT(DISTINCT path_id)` over the whole case, never over a page.
* Invariants (keyword ≥ 2 words; category and category word exactly 1 word) are enforced in `Terms`, in the facades, in `CorpusDatabase` inserts/updates and in the UI, and tested at each layer (`RelationshipModelTest#storageInvariants`).
* `RelationshipIntegrity` walks File → Keyword → Category → Category Word → Files in both directions and reports count disagreements and orphan edges ("Check relationships" on Search; `docs/RELATIONSHIP_INTEGRITY_REPORT.md`).
''')
(root / 'docs/INTERFACE_FUNCTION_MATRIX.md').write_text('\n'.join(out), encoding='utf-8')
print('rendered', len(rows), 'rows', dict(counts))
