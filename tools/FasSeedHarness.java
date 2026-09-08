import com.aegis.fdx.engine.CaseSettings;
import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.facade.AegisFacades;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seeds the demo workspace with real data by driving the ACTUAL ingest pipeline.
 *
 * <p>This exists so the documentation screenshots show the UI populated with genuine
 * engine output — extracted text, real SHA-256 hashes, real statuses — rather than
 * hand-written placeholder rows.
 */
public class FasSeedHarness {

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("user.home"), ".file-analysis", "workspace");
        if (Files.exists(home)) {
            try (var walk = Files.walk(home)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(home);

        Path ev = Path.of("/tmp/fas-evidence");
        if (Files.exists(ev)) {
            try (var walk = Files.walk(ev)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(ev);

        Files.writeString(ev.resolve("invoice-2024-0417.txt"),
                "INVOICE 2024-0417\nAcme Consulting BV, Amsterdam\n"
                        + "Consulting services rendered Q1 2024\nTotal due: EUR 14,500.00\n"
                        + "Payment terms: net 30 days\nContact: finance@acme.example\n",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("contract-northwind.txt"),
                "MASTER SERVICES AGREEMENT\nBetween Northwind Trading and Acme Consulting BV\n"
                        + "This agreement governs consulting services for the 2024 period.\n"
                        + "Confidentiality clause applies to all deliverables.\n",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("meeting-notes.txt"),
                "Meeting notes 14 March 2024\nAttendees: R. Crivello, J. de Vries\n"
                        + "Discussed the consulting engagement scope and invoice schedule.\n"
                        + "Action: send revised statement of work.\n",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("statement-q1.csv"),
                "date,description,amount\n2024-01-15,Consulting retainer,5000\n"
                        + "2024-02-15,Consulting retainer,5000\n"
                        + "2024-03-15,Consulting overage,4500\n",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("policy.html"),
                "<html><body><h1>Records Retention Policy</h1>"
                        + "<p>All consulting records are retained for seven years.</p>"
                        + "</body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(ev.resolve("message.eml"),
                "From: finance@acme.example\r\nTo: ap@northwind.example\r\n"
                        + "Subject: Invoice 2024-0417 attached\r\n"
                        + "Date: Mon, 18 Mar 2024 09:14:00 +0100\r\n\r\n"
                        + "Please find the consulting invoice for Q1 attached.\r\n",
                StandardCharsets.UTF_8);
        // A real ZIP so the pipeline expands it and the container tree has depth.
        Path zip = ev.resolve("case-bundle.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("bundled-invoice.txt"));
            zos.write(("INVOICE 2024-0902\nAcme Consulting BV\n"
                    + "Consulting retainer, payment due 30 days\n")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("bundled-memo.txt"));
            zos.write("Memo: consulting agreement renewal terms\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("nested/deep-note.txt"));
            zos.write("Deeply nested note about the consulting engagement\n"
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Files.createDirectories(ev.resolve("archive"));
        Files.writeString(ev.resolve("archive").resolve("old-invoice.txt"),
                "INVOICE 2023-0088\nAcme Consulting BV\nTotal due: EUR 9,200.00\n",
                StandardCharsets.UTF_8);

        CaseSettings s = new CaseSettings();
        s.ocrEnabled(false);

        try (LiveCase c = new LiveCase(home, "workspace", s)) {
            AegisFacades f = AegisFacades.open(c);

            int src1 = f.sources().createSource(
                    new com.aegis.fdx.facade.SourceDraft(
                            "Acme Consulting BV", "NL", "custodian", 0.85)
                            .city("Amsterdam")
                            .description("Primary vendor under review")
                            .accounts("@acmeconsulting")
                            .note("Contract and invoices")
                            .attachments("3 attachments")
                            .ownership("Corporate")
                            .accessStatus("Full access")
                            .entryDate(java.time.LocalDate.of(2024, 3, 18)));
            f.sources().createSource(
                    new com.aegis.fdx.facade.SourceDraft(
                            "Northwind Trading", "DE", "counterparty", 0.60)
                            .city("Berlin")
                            .description("Opposing party")
                            .ownership("Corporate")
                            .accessStatus("Limited")
                            .entryDate(java.time.LocalDate.of(2024, 4, 2)));

            int aspect1 = f.aspects().createAspect("Plaintiff", 0.90);
            f.aspects().createAspect("Defendant", 0.55);

            f.categories().createCategory("finance");
            f.categories().createCategory("legal");
            f.categories().createCategory("correspondence");

            for (String w : new String[]{"invoice", "consulting", "agreement", "retainer",
                    "confidentiality", "payment", "statement", "policy"}) {
                f.words().createWord(w);
            }
            f.categories().linkWordToCategory("invoice", "finance");
            f.categories().linkWordToCategory("payment", "finance");
            f.categories().linkWordToCategory("retainer", "finance");
            f.categories().linkWordToCategory("agreement", "legal");
            f.categories().linkWordToCategory("confidentiality", "legal");

            f.keywords().createKeyword("unpaid invoice notice", "finance");
            f.keywords().createKeyword("net 30 days terms", "finance");
            f.keywords().createKeyword("mutual confidentiality clause", "legal");
            f.keywords().createKeyword("statement of work", "legal");
            f.keywords().createKeyword("please find attached", "correspondence");

            // ---- real ingest through the real pipeline ----
            var proc = f.processing("Acme Consulting BV", "Plaintiff");
            var results = proc.processFolder(ev.toString());
            System.out.println("ingested elements: " + results.size());

            int registered = f.contents().registerIngestedItems(src1, aspect1);
            System.out.println("registered paths: " + registered);

            // Attribute registered files to categories/keywords so the relationship
            // views show real links rather than empty panels.
            var dao = new com.aegis.fdx.store.CorpusDatabase(c.db());
            var cats = f.categories().listCategories(50, 0).results();
            var kws = f.keywords().listKeywords(50, 0).results();
            for (var path : f.contents().getPaths(null, null, null, null, 100, 0).results()) {
                String text = f.contents().getContentAsText(path.id()).toLowerCase();
                for (var cat : cats) {
                    if (text.contains(cat.word())) dao.linkPathToCategory(path.id(), cat.id());
                }
                for (var kw : kws) {
                    int hits = countOf(text, kw.keyword().toLowerCase());
                    if (hits > 0) dao.linkPathToKeyword(path.id(), kw.id(), hits);
                }
            }

            f.history().addSearch("consulting", 6);
            f.history().addSearch("invoice", 3);
            f.history().addSearch("confidentiality clause", 1);
            f.history().saveSearch("Unpaid invoices", "invoice AND (unpaid OR overdue)");
            f.history().saveSearch("All consulting docs", "consulting");

            f.notifications().createNotification("processing", "normal",
                    "Ingest completed",
                    results.size() + " elements processed from /tmp/fas-evidence", null, null);
            f.notifications().createSimilarFilesNotification("E-000001",
                    java.util.List.of("E-000007"), "high");
            f.notifications().createFutureEventNotification("E-000002",
                    java.time.Instant.now().plus(12, java.time.temporal.ChronoUnit.DAYS),
                    "Retention review due", "Records retention policy review");

            var stats = proc.getStatistics();
            System.out.println("total=" + stats.total() + " completed=" + stats.completed()
                    + " failed=" + stats.failed());
            System.out.println("paths registered = "
                    + f.contents().getPaths(null, null, null, null, 1, 0).totalCount());
            System.out.println("search 'consulting' = "
                    + f.search().search("consulting").totalCount());
            System.out.println("SEED OK");
        }
    }
}
