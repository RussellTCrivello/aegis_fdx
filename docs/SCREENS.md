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
| `15-titles.png` | Titles |
| `16-relations.png` | Relations |
| `17-geolocation.png` | Geolocation |
| `18-upload-files.png` | Upload Files |
| `19-file-library.png` | File Library |
| `20-archives.png` | Archives (container nesting) |
| `21-import-export.png` | Import / Export |
| `22-assistant.png` | Assistant |
| `23-saved-searches.png` | Saved Searches |
| `24-notifications.png` | Notifications |
| `25-processing.png` | Processing Monitor |
| `26-errors.png` | Error Dashboard |
| `27-performance.png` | Performance |
| `28-setup.png` | Setup |
| `29-settings.png` | Settings |
| `30-source-detail.png` | Source Detail |
| `31-aspect-detail.png` | Aspect Detail |
| `32-file-detail.png` | File Detail |
| `33-full-content.png` | Full Content |
| `34-keyword-detail.png` | Keyword Detail |
| `35-word-detail.png` | Word Detail |
| `36-source-relationships.png` | Source Relationships |
| `37-category-detail.png` | Category Detail |
| `38-aspect-relationships.png` | Aspect Relationships |

38 figures, one per audited reference destination.

Walkthrough: **[UI_GUIDE.md](UI_GUIDE.md)** ·
audit: **[REFERENCE_AUDIT.md](REFERENCE_AUDIT.md)** ·
functions: **[FUNCTION_INVENTORY.md](FUNCTION_INVENTORY.md)**
