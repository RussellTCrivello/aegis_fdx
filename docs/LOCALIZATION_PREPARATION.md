# Localization Preparation

Status: **preparation only**. No user-facing string has been translated and no resource
bundle has been introduced. This document inventories what a later localisation phase
would touch, fixes the architecture it should use, and freezes the domain vocabulary so
that translation can never change meaning. The English interface remains the specification
until the functional phase is signed off (`FINAL_STATUS.md`).

## 1. Inventory of hard-coded user-facing text (measured 2026-09-08)

Counted with `grep` over `app/src/main/java/com/aegis/fdx`; these are literal strings, not
distinct messages (many repeat, e.g. "Delete", "Refresh", "Export CSV").

| Location | Kind | Literal strings | How they are produced |
|---|---|---|---|
| `ui/screens/*` (33 screens) | all capitalised literals | ≈ 1 200 | inline Java string literals |
| `ui/*` (shell: `FasApp`, `Router`, `Fas`, `Icons`, `ChartPane`, …) | all capitalised literals | ≈ 435 | inline |
| `facade/*` | error / validation messages | 49 | `IllegalArgumentException` / result messages surfaced by the UI |
| `ai/*` | user-visible text | ≈ 10 | the agent's fixed phrases ("I could not produce an answer", the no-evidence caveat, the system prompt) are English; provenance labels OBSERVED/DERIVED/INFERRED/USER-PROVIDED/UNKNOWN (`Provenance`) are a controlled vocabulary, see §4 |
| Buttons | `Fas.primary/secondary/outline/danger/ghost("…")` | 117 | one factory per style in `Fas` |
| Table headers | `new TableColumn<>("…")` / `column("…")` | 78 | inline |
| Prompts | `setPromptText("…")` | 15 | inline |
| Screen identity | `title()`, `breadcrumb()` per screen | 33 × 2 | interface methods on `Screen`/`Detail` |
| Helper labels | `Fas.muted`, `Fas.fieldLabel`, `Fas.sectionTitle`, `Fas.emptyState`, `Fas.badge` | ≈ 90 | factories in `Fas` |
| Formatting | `String.format`, `DecimalFormat`, `DateTimeFormatter` in `ui` | 93 call sites | locale-neutral today (`Locale.ROOT` only in analyzers/engine) |

Not user-facing and **out of scope** for translation: SQL, Lucene field names, log
messages, `case.db` column names, JSON keys of exports, hash-chain / audit strings,
file-format magic tables, and the AI tool names (`list_*`, `get_*`, `search_*`).

### Categories of text, by translation risk

1. **Static labels** (buttons, headers, prompts, titles) — direct keys, no risk.
2. **Composed sentences** (`n + " files in " + m + " sources"`) — must become parameterised
   messages (`MessageFormat`/ICU plural rules) before translation; ≈ 93 call sites.
3. **Enumerations shown to the user** — match types (`EXACT`, `PHRASE`, `WORD`, `FUZZY`…),
   review status, processing status, integrity finding rules, AI evidence labels. Stored
   values stay English constants; only the *display* is localised (see §4).
4. **Validation messages from facades** — 49; currently English sentences thrown as exceptions.
   Plan: facades throw typed exceptions with a message key and arguments; the UI renders.
5. **Domain vocabulary** — see §3; frozen.

## 2. Target architecture (not yet implemented)

* `app/src/main/resources/com/aegis/fdx/i18n/ui.properties` (English, the source),
  `ui_de.properties`, `ui_nl.properties`, `ui_fr.properties`, `ui_es.properties`, ….
  UTF-8 `PropertyResourceBundle` (Java 9+ reads UTF-8 by default).
* One accessor `com.aegis.fdx.ui.I18n` (`I18n.t("keywords.add")`,
  `I18n.n("files.count", n)`) wrapping `ResourceBundle` + `MessageFormat`; JavaFX
  `StringProperty`-bound labels so a language switch re-renders without restart.
* Keys are **stable identifiers**, never English text: `screen.keywords.title`,
  `action.export_csv`, `column.files`, `validation.keyword.min_words`.
* `Fas` factories gain overloads taking a key; existing string-literal overloads remain during
  migration so screens can be converted one at a time under `UiParityTest`.
* Language selection persists in `settings` (case.db) as a BCP-47 tag; default is the JVM
  locale if a bundle exists, else `en`. This is the Java-native successor to the reference's
  `/set_language/<lang>` (matrix row **ST2**, LIMITED — deliberately deferred).
* Locale-sensitive formatting goes through `I18n.number/date/bytes` with the selected
  locale; sorting of user-visible text uses `Collator` for the selected locale, while
  relationship matching and normalisation keep `Locale.ROOT` (they must not change with the UI language).
* Tests planned: every key in `ui.properties` used at least once; every language file has the
  same key set; no `Fas.*("…")` literal remains in `ui/screens` (an `ArchitectureInvariantsTest`-style check).

## 3. Domain vocabulary — semantically frozen

The following terms carry exact meaning in the data model and in the reference. Translations
must preserve the concept, not pick a near-synonym; each target language gets one fixed term.

| Term | Meaning (do not change) | Storage | Not to be confused with |
|---|---|---|---|
| **Source** | Where material came from (a party, system, custodian). Has name, importance, city, country, category. | `sources` | Aspect; a file's folder |
| **Aspect** | A viewpoint/side of the matter under which files are grouped (reference "Side"). | `sides` | Source; Category |
| **Category** | A single-word label. Reaches files through its Category Words, its Keywords, or reviewer attribution. **Exactly one word.** | `category` (+ `word_category`, `path_category`) | folder; MIME type; Aspect |
| **Keyword** | A phrase of **two or more words** that belongs to exactly one Category; found in file content. | `keyword`, `path_keyword` | Category Word; search term |
| **Category Word** | A **single word** in the vocabulary, linked to one or more Categories; found in file content. | `word`, `word_category`, `path_word` | Keyword; any word in a file |
| **Content** | The extracted text of a file (what matching and search read). | `content` / Lucene | metadata; the file bytes |
| **File** | One evidentiary item (path, hash, size, type, source, aspect, metadata, content). Never deleted. | `path` (+ engine item) | archive member's container |
| **Case** | The whole authoritative store (`case.db` + derived index). | folder | project; dataset |
| **Match type** | How a search hit was made (exact / phrase / word / fuzzy / metadata). | computed | relevance score |
| **Evidence label** | OBSERVED / DERIVED / INFERRED / USER-PROVIDED / UNKNOWN on agent statements. | agent output | confidence percentage |

Proposed stable glossary (for the later phase; **not applied anywhere yet**):

| EN | DE | NL | FR | ES |
|---|---|---|---|---|
| Source | Quelle | Bron | Source | Fuente |
| Aspect | Aspekt | Aspect | Aspect | Aspecto |
| Category | Kategorie | Categorie | Catégorie | Categoría |
| Keyword | Schlüsselphrase | Sleutelzin | Expression clé | Frase clave |
| Category Word | Kategoriewort | Categoriewoord | Mot de catégorie | Palabra de categoría |
| Content | Inhalt | Inhoud | Contenu | Contenido |
| File | Datei | Bestand | Fichier | Archivo |

"Keyword" is deliberately rendered as *phrase* in the target languages because the invariant
is ≥ 2 words; a literal "Schlüsselwort"/"trefwoord" would suggest a single word and collide
with Category Word.

## 4. Things that must never be localised

* Stored values: term text as typed by the user, match-type and status constants, evidence
  labels, finding rule names (`orphan-edge`, …), settings keys, export column names in CSV
  (they are a machine contract; a localised header can be offered as an option later).
* Normalisation used for relationship matching (`Terms.normalize`, `Locale.ROOT`).
* Anything the hash chain covers.

## 5. Sequence for the later phase

1. Introduce `I18n` and `ui.properties`; convert `Fas` factories and `title()/breadcrumb()`.
2. Convert screens one at a time; `UiParityTest` and `InterfaceFunctionMatrixTest`
   (button-label inventory) keep the control set unchanged.
3. Parameterise the 93 composed messages; convert the 49 facade messages to keyed exceptions.
4. Add languages; add the key-set-equality test.
5. Only then flip matrix row ST2 from LIMITED to VERIFIED.
