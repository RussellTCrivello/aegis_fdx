# Screens

## File Analysis System interface (`docs/screens-fas/`)

Captured by `tools/FasShotHarness`, which launches the real application and walks every
destination including the detail views. A figure exists only if the destination
actually rendered; the harness also fires a real search and a real batch-analysis run,
so those figures show executed work rather than idle forms.

The workspace is populated beforehand by `tools/FasSeedHarness`, which runs the genuine
ingest pipeline over a sample corpus containing a nested ZIP.

| File | Destination |
|---|---|
| `01-dashboard.png` | Dashboard |
| `02-analysis.png` | Analysis |
| `03-charts.png` | Charts Dashboard |
| `04-comprehensive.png` | Comprehensive Dashboard |
| `05-path-analysis.png` | Path Analysis |
| `06-batch-analysis.png` | Batch Analysis (real run executed) |
| `07-search.png` | Search (live query) |
| `08-advanced-search.png` | Advanced Search builder |
| `09-sources.png` | Sources |
| `10-aspects.png` | Aspects |
| `11-email-words.png` | Email Words |
| `12-keywords.png` | Keywords |
| `13-words.png` | Words |
| `14-categories.png` | Categories |
| `15-upload-files.png` | Upload Files |
| `16-file-library.png` | File Library |
| `17-archives.png` | Archives (container nesting) |
| `18-import-export.png` | Import / Export |
| `19-assistant.png` | Assistant |
| `20-saved-searches.png` | Saved Searches |
| `21-notifications.png` | Notifications |
| `22-processing.png` | Processing Monitor |
| `23-errors.png` | Error Dashboard |
| `24-performance.png` | Performance |
| `25-setup.png` | Setup |
| `26-settings.png` | Settings |
| `27-source-detail.png` | Source Detail |
| `28-aspect-detail.png` | Aspect Detail |
| `29-file-detail.png` | File Detail |
| `30-full-content.png` | Full Content |
| `31-keyword-detail.png` | Keyword Detail |
| `32-word-detail.png` | Word Detail |
| `33-source-relationships.png` | Source Relationships |

33 figures, one per audited reference destination.

Walkthrough: **[UI_GUIDE.md](UI_GUIDE.md)** ·
audit: **[REFERENCE_AUDIT.md](REFERENCE_AUDIT.md)** ·
functions: **[FUNCTION_INVENTORY.md](FUNCTION_INVENTORY.md)**
