package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.FileRelationships;
import com.aegis.fdx.facade.dto.TermSummary;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walks the relationship graph from both ends and reports whether it agrees with itself.
 *
 * <p>For every keyword, category and category word the checker takes the set of files the
 * term reaches, then asks each of those files which terms it is related to, and requires
 * the term to be present. It does the same in reverse from every file. It also confirms
 * every list count equals the size of the corresponding detail set, that no derived edge
 * points at a missing row, and that no term violates its word-count invariant.
 *
 * <p>Nothing is repaired here. The report says what disagrees and where; the operator
 * decides. An empty finding list is the only acceptable state before a case is relied on.
 */
public final class RelationshipIntegrity {

    /** One thing that is wrong, with enough identity to find it. */
    public record Finding(String rule, String subject, String detail) {
        @Override
        public String toString() {
            return rule + ": " + subject + " — " + detail;
        }
    }

    /** What was checked and what was found. */
    public record Report(
            int files, int keywords, int categories, int categoryWords,
            int keywordEdges, int wordEdges, int categoryEdges,
            int traversalsChecked,
            List<Finding> findings) {

        public boolean consistent() {
            return findings.isEmpty();
        }

        public String summary() {
            return files + " files, " + keywords + " keywords, " + categories + " categories, "
                    + categoryWords + " category words; " + keywordEdges + " keyword edges, "
                    + wordEdges + " word edges, " + categoryEdges + " category edges; "
                    + traversalsChecked + " traversals checked; "
                    + (consistent() ? "consistent" : findings.size() + " finding(s)");
        }
    }

    private final CorpusDatabase db;
    private final RelationshipFacade rel;

    public RelationshipIntegrity(CorpusDatabase db, RelationshipFacade rel) {
        this.db = db;
        this.rel = rel;
    }

    public Report check() {
        List<Finding> out = new ArrayList<>();
        int traversals = 0;
        try {
            Map<String, Integer> totals = rel.totals();

            // ---- forward: term → files → term ------------------------------------
            List<TermSummary> keywords = rel.keywords(null, 100_000, 0).results();
            List<TermSummary> categories = rel.categories(null, 100_000, 0).results();
            List<TermSummary> words = rel.categoryWords(null, 100_000, 0).results();

            Map<Integer, FileRelationships> fileCache = new HashMap<>();

            for (TermSummary k : keywords) {
                if (Terms.wordCount(k.text()) < Terms.MIN_KEYWORD_WORDS) {
                    out.add(new Finding("keyword-invariant", "keyword#" + k.id(),
                            "\"" + k.text() + "\" has fewer than " + Terms.MIN_KEYWORD_WORDS + " words"));
                }
                TermSummary d = rel.keyword(k.id());
                if (d.fileCount() != k.fileCount()) {
                    out.add(new Finding("count-agreement", "keyword#" + k.id(),
                            "list says " + k.fileCount() + " files, detail says " + d.fileCount()));
                }
                if (d.files().size() != d.fileCount() && d.fileCount() <= RelationshipFacade.FILE_LIMIT) {
                    out.add(new Finding("count-agreement", "keyword#" + k.id(),
                            "detail count " + d.fileCount() + " but " + d.files().size() + " files listed"));
                }
                for (TermSummary.FileRef f : d.files()) {
                    traversals++;
                    FileRelationships fr = fileCache.computeIfAbsent(f.pathId(), rel::forFile);
                    if (fr.keywords().stream().noneMatch(t -> t.id() == k.id())) {
                        out.add(new Finding("bidirectional", "keyword#" + k.id() + " ↔ file#" + f.pathId(),
                                "keyword reaches the file but the file does not list the keyword"));
                    }
                }
                if (d.categories().size() != 1) {
                    out.add(new Finding("keyword-category", "keyword#" + k.id(),
                            "a keyword belongs to exactly one category; found " + d.categories().size()));
                }
            }

            for (TermSummary c : categories) {
                if (Terms.wordCount(c.text()) != Terms.CATEGORY_WORDS) {
                    out.add(new Finding("category-invariant", "category#" + c.id(),
                            "\"" + c.text() + "\" is not exactly one word"));
                }
                TermSummary d = rel.category(c.id());
                if (d.fileCount() != c.fileCount()) {
                    out.add(new Finding("count-agreement", "category#" + c.id(),
                            "list says " + c.fileCount() + " files, detail says " + d.fileCount()));
                }
                for (TermSummary.FileRef f : d.files()) {
                    traversals++;
                    FileRelationships fr = fileCache.computeIfAbsent(f.pathId(), rel::forFile);
                    if (fr.categories().stream().noneMatch(t -> t.id() == c.id())) {
                        out.add(new Finding("bidirectional", "category#" + c.id() + " ↔ file#" + f.pathId(),
                                "category reaches the file but the file does not list the category"));
                    }
                }
                // category ↔ word, category ↔ keyword
                for (TermSummary.RelatedTerm w : d.categoryWords()) {
                    traversals++;
                    TermSummary wd = rel.categoryWord(w.id());
                    if (wd.categories().stream().noneMatch(t -> t.id() == c.id())) {
                        out.add(new Finding("bidirectional", "category#" + c.id() + " ↔ word#" + w.id(),
                                "category lists the word but the word does not list the category"));
                    }
                }
                for (TermSummary.RelatedTerm k : d.keywords()) {
                    traversals++;
                    TermSummary kd = rel.keyword(k.id());
                    if (kd.categories().stream().noneMatch(t -> t.id() == c.id())) {
                        out.add(new Finding("bidirectional", "category#" + c.id() + " ↔ keyword#" + k.id(),
                                "category lists the keyword but the keyword does not list the category"));
                    }
                }
            }

            for (TermSummary w : words) {
                if (Terms.wordCount(w.text()) != 1) {
                    out.add(new Finding("word-invariant", "word#" + w.id(),
                            "\"" + w.text() + "\" is not exactly one word"));
                }
                TermSummary d = rel.categoryWord(w.id());
                if (d.fileCount() != w.fileCount()) {
                    out.add(new Finding("count-agreement", "word#" + w.id(),
                            "list says " + w.fileCount() + " files, detail says " + d.fileCount()));
                }
                for (TermSummary.FileRef f : d.files()) {
                    traversals++;
                    FileRelationships fr = fileCache.computeIfAbsent(f.pathId(), rel::forFile);
                    if (fr.categoryWords().stream().noneMatch(t -> t.id() == w.id())) {
                        out.add(new Finding("bidirectional", "word#" + w.id() + " ↔ file#" + f.pathId(),
                                "word reaches the file but the file does not list the word"));
                    }
                }
            }

            // ---- reverse: file → terms → file ------------------------------------
            Set<Integer> keywordIds = new HashSet<>();
            keywords.forEach(t -> keywordIds.add(t.id()));
            Set<Integer> categoryIds = new HashSet<>();
            categories.forEach(t -> categoryIds.add(t.id()));
            Set<Integer> wordIds = new HashSet<>();
            words.forEach(t -> wordIds.add(t.id()));

            for (CorpusDatabase.Row p : db.selectPaths(null, null, null, null, 1_000_000, 0)) {
                int pathId = p.i("id");
                FileRelationships fr = fileCache.computeIfAbsent(pathId, rel::forFile);
                for (TermSummary.RelatedTerm k : fr.keywords()) {
                    traversals++;
                    if (!keywordIds.contains(k.id())) {
                        out.add(new Finding("dangling-edge", "file#" + pathId + " → keyword#" + k.id(),
                                "file lists a keyword that does not exist"));
                    } else if (rel.filesForKeyword(k.id()).stream().noneMatch(f -> f.pathId() == pathId)) {
                        out.add(new Finding("bidirectional", "file#" + pathId + " ↔ keyword#" + k.id(),
                                "file lists the keyword but the keyword does not reach the file"));
                    }
                }
                for (TermSummary.RelatedTerm c : fr.categories()) {
                    traversals++;
                    if (!categoryIds.contains(c.id())) {
                        out.add(new Finding("dangling-edge", "file#" + pathId + " → category#" + c.id(),
                                "file lists a category that does not exist"));
                    } else if (rel.filesForCategory(c.id()).stream().noneMatch(f -> f.pathId() == pathId)) {
                        out.add(new Finding("bidirectional", "file#" + pathId + " ↔ category#" + c.id(),
                                "file lists the category but the category does not reach the file"));
                    }
                }
                for (TermSummary.RelatedTerm w : fr.categoryWords()) {
                    traversals++;
                    if (!wordIds.contains(w.id())) {
                        out.add(new Finding("dangling-edge", "file#" + pathId + " → word#" + w.id(),
                                "file lists a category word that does not exist"));
                    } else if (rel.filesForWord(w.id()).stream().noneMatch(f -> f.pathId() == pathId)) {
                        out.add(new Finding("bidirectional", "file#" + pathId + " ↔ word#" + w.id(),
                                "file lists the word but the word does not reach the file"));
                    }
                }
            }

            // ---- storage-level: orphans and duplicates -----------------------------
            for (CorpusDatabase.Row r : db.selectOrphanEdges()) {
                out.add(new Finding("orphan-edge", r.str("edge_table") + "(" + r.i("a") + "," + r.i("b") + ")",
                        r.str("problem")));
            }

            return new Report(
                    totals.getOrDefault("files", 0), totals.getOrDefault("keywords", 0),
                    totals.getOrDefault("categories", 0), totals.getOrDefault("category_words", 0),
                    totals.getOrDefault("keyword_edges", 0), totals.getOrDefault("word_edges", 0),
                    totals.getOrDefault("category_edges", 0),
                    traversals, List.copyOf(out));
        } catch (SQLException e) {
            throw FacadeException.internal("relationship integrity check failed", e);
        }
    }

    /** A plain-text rendering suitable for a report file or a dialog. */
    public static String render(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Relationship integrity\n");
        sb.append("  ").append(r.summary()).append('\n');
        Map<String, List<Finding>> byRule = new LinkedHashMap<>();
        for (Finding f : r.findings()) {
            byRule.computeIfAbsent(f.rule(), k -> new ArrayList<>()).add(f);
        }
        for (Map.Entry<String, List<Finding>> e : byRule.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" (").append(e.getValue().size()).append(")\n");
            Set<String> lines = new TreeSet<>();
            for (Finding f : e.getValue()) {
                lines.add("    " + f.subject() + ": " + f.detail());
            }
            lines.forEach(l -> sb.append(l).append('\n'));
        }
        return sb.toString();
    }
}
