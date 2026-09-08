package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.ExportFacade;
import com.aegis.fdx.facade.FacadeException;
import com.aegis.fdx.facade.ImportFacade;
import com.aegis.fdx.facade.dto.ProcessingResultDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Python parity: {@code templates/ImportExport/import_export.html} and the
 * {@code /api/search/export}, {@code /api/keywords/export},
 * {@code /api/sources/<id>/export} routes.
 *
 * <p>Export formats mirror {@code ExportService}: CSV, Excel and JSON. Import mirrors
 * {@code ImportService}: database backup (validate-only, as Python documents),
 * settings JSON and a CSV file list.
 */
public final class ImportExportScreen implements Screen {

    private final AegisFacades facades;
    private TextArea log;
    private ComboBox<String> exportFormat;

    public ImportExportScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Import / Export";
    }

    @Override
    public String icon() {
        return Icons.DOWNLOAD;
    }

    @Override
    public Node build() {
        log = new TextArea();
        log.setEditable(false);
        log.setPrefRowCount(9);
        log.setPromptText("Import and export activity appears here");

        exportFormat = new ComboBox<>(javafx.collections.FXCollections
                .observableArrayList("CSV", "Excel (.xlsx)", "JSON"));
        exportFormat.setValue("CSV");

        Button exportFiles = Fas.primary("Export File Registry", Icons.DOWNLOAD);
        exportFiles.setOnAction(e -> exportRegistry());

        Button exportKeywords = Fas.outline("Export Keywords", Icons.KEY);
        exportKeywords.setOnAction(e -> exportKeywords());

        Button exportSettings = Fas.outline("Export Settings", Icons.GEAR);
        exportSettings.setOnAction(e -> {
            byte[] data = ExportFacade.exportSettings(Map.of(
                    "app_name", "File Analysis System",
                    "ocr_enabled", "false",
                    "theme", "default"));
            save(data, "settings.json", "JSON", "*.json");
        });

        Button backup = Fas.outline("Export Database Backup", Icons.DATABASE);
        backup.setOnAction(e -> exportBackup());

        Button importBackup = Fas.secondary("Validate Backup", Icons.SHIELD);
        importBackup.setOnAction(e -> importBackup());

        Button importSettings = Fas.outline("Import Settings", Icons.GEAR);
        importSettings.setOnAction(e -> importSettings());

        Button importCsv = Fas.outline("Import File List (CSV)", Icons.FILES);
        importCsv.setOnAction(e -> importCsvList());

        VBox exportCard = Fas.cardWithHeader("Export",
                "Download the current data set in an interchange format",
                new VBox(12,
                        Fas.row(10, Fas.formField("Format", exportFormat)),
                        Fas.row(10, exportFiles, exportKeywords),
                        Fas.row(10, exportSettings, backup)));

        VBox importCard = Fas.cardWithHeader("Import",
                "Backup import is validate-only, matching the reference contract",
                new VBox(12,
                        Fas.row(10, importBackup, importSettings),
                        Fas.row(10, importCsv)));

        HBox.setHgrow(exportCard, Priority.ALWAYS);
        HBox.setHgrow(importCard, Priority.ALWAYS);

        VBox content = new VBox(16,
                Fas.pageHeader("Import / Export", "Home / Import / Export"),
                new HBox(14, exportCard, importCard),
                Fas.cardWithHeader("Activity Log", null, log));
        content.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private void exportRegistry() {
        try {
            var paths = facades.contents().getPaths(null, null, null, null, 10_000, 0).results();
            if (paths.isEmpty()) {
                note("Nothing to export \u2014 no files registered yet.");
                return;
            }
            // Reuse the search-result exporter by projecting the registry rows.
            List<com.aegis.fdx.facade.dto.SearchResultDto> rows = paths.stream()
                    .map(p -> new com.aegis.fdx.facade.dto.SearchResultDto(
                            String.valueOf(p.id()), p.fileName(), p.filePath(), p.fileType(),
                            p.fileSize(), p.sourceName(), p.aspectName(), 0d, 0,
                            List.of(), p.fileStatus(), p.sourceName(), null, p.hashValue()))
                    .toList();
            String fmt = exportFormat.getValue();
            if (fmt.startsWith("Excel")) {
                save(ExportFacade.exportSearchResultsExcel(rows), "file-registry.xlsx",
                        "Excel", "*.xlsx");
            } else if ("JSON".equals(fmt)) {
                save(ExportFacade.exportSearchResultsJson(rows), "file-registry.json",
                        "JSON", "*.json");
            } else {
                save(ExportFacade.exportSearchResultsCsv(rows), "file-registry.csv",
                        "CSV", "*.csv");
            }
        } catch (FacadeException ex) {
            note("Export failed: " + ex.getMessage());
        }
    }

    private void exportKeywords() {
        var kws = facades.keywords().listKeywords(10_000, 0).results();
        StringBuilder sb = new StringBuilder("\ufeffid,keyword,category\r\n");
        for (var k : kws) {
            sb.append(k.id()).append(',').append(csv(k.keyword())).append(',')
                    .append(csv(k.categoryWord())).append("\r\n");
        }
        save(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "keywords.csv", "CSV", "*.csv");
    }

    private void exportBackup() {
        Map<String, byte[]> tables = ExportFacade.newBackupMap();
        StringBuilder src = new StringBuilder("id,name,country,job,importance\n");
        facades.sources().listSources().forEach(s -> src.append(s.id()).append(',')
                .append(csv(s.name())).append(',').append(csv(s.country())).append(',')
                .append(csv(s.job())).append(',').append(s.importance()).append('\n'));
        tables.put("sources", src.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder sides = new StringBuilder("id,name,importance\n");
        facades.aspects().listAspects().forEach(s -> sides.append(s.id()).append(',')
                .append(csv(s.name())).append(',').append(s.importance()).append('\n'));
        tables.put("sides", sides.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        save(ExportFacade.exportDatabaseBackup(tables), "backup.zip", "ZIP", "*.zip");
    }

    private void importBackup() {
        byte[] data = open("Select backup ZIP", "ZIP", "*.zip");
        if (data == null) return;
        try {
            ImportFacade.BackupValidation v = facades.imports().importDatabaseBackup(data);
            note(v.valid()
                    ? "Backup is valid \u2014 " + v.entries().size() + " entr(ies), "
                      + v.uncompressedBytes() + " bytes: " + String.join(", ", v.entries())
                    : "Backup invalid: " + v.error());
        } catch (FacadeException ex) {
            note("Validation failed: " + ex.getMessage());
        }
    }

    private void importSettings() {
        byte[] data = open("Select settings JSON", "JSON", "*.json");
        if (data == null) return;
        try {
            Map<String, String> s = facades.imports().importSettings(data);
            note("Imported " + s.size() + " setting(s): " + s);
        } catch (FacadeException ex) {
            note("Import failed: " + ex.getMessage());
        }
    }

    private void importCsvList() {
        var sources = facades.sources().listSources();
        var sides = facades.aspects().listAspects();
        if (sources.isEmpty() || sides.isEmpty()) {
            note("Create at least one Source and one Side first \u2014 both are mandatory.");
            return;
        }
        byte[] data = open("Select CSV file list", "CSV", "*.csv");
        if (data == null) return;
        try {
            List<ProcessingResultDto> r = facades
                    .imports(sources.get(0).name(), sides.get(0).name())
                    .importFileListFromCsv(data, sources.get(0).id(), sides.get(0).id());
            long ok = r.stream().filter(ProcessingResultDto::success).count();
            note("Imported " + r.size() + " path(s): " + ok + " processed, "
                    + (r.size() - ok) + " failed.");
        } catch (FacadeException ex) {
            note("Import failed: " + ex.getMessage());
        }
    }

    private void save(byte[] data, String name, String label, String ext) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save export");
        fc.setInitialFileName(name);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(label, ext));
        java.io.File f = fc.showSaveDialog(log.getScene().getWindow());
        if (f == null) return;
        try {
            Files.write(f.toPath(), data);
            note("Wrote " + data.length + " bytes to " + f.getAbsolutePath());
        } catch (Exception e) {
            note("Write failed: " + e.getMessage());
        }
    }

    private byte[] open(String title, String label, String ext) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(label, ext));
        java.io.File f = fc.showOpenDialog(log.getScene().getWindow());
        if (f == null) return null;
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            note("Read failed: " + e.getMessage());
            return null;
        }
    }

    private void note(String msg) {
        log.appendText(msg + "\n");
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @Override
    public void onShow() {
    }
}
