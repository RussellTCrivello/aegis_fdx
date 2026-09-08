package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.model.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * F-28 case container. In production this is the on-disk case folder
 * ({@code /data /index /text /db /logs /exports case.json}); the prototype keeps
 * the same API surface backed by in-memory collections so the UI is written
 * against the final contract.
 */
public final class CaseStore {

    private final String name;
    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();
    private final List<Tag> tags = new CopyOnWriteArrayList<>(Tag.FIXED);
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();
    private final List<String> savedSearches = new CopyOnWriteArrayList<>();
    private final CaseSettings settings = new CaseSettings();

    public CaseStore(String name) { this.name = name; }

    public String name() { return name; }
    public CaseSettings settings() { return settings; }
    public List<Tag> tags() { return tags; }
    public List<AuditEntry> audit() { return audit; }
    public List<String> savedSearches() { return savedSearches; }

    public void add(Item item) {
        if (items.putIfAbsent(item.id(), item) == null) order.add(item.id());
    }

    public Item get(String id) { return items.get(id); }
    public int size() { return items.size(); }

    public List<Item> all() {
        List<Item> out = new ArrayList<>(order.size());
        for (String id : order) {
            Item it = items.get(id);
            if (it != null) out.add(it);
        }
        return out;
    }

    /** F-15 + F-16 + F-20: query, then structured filters, then hidden-exclusion. */
    public List<SearchHit> search(String query, Filters filters, boolean includeHidden) {
        Predicate<Item> pred = QueryParser.parse(query);
        String needle = firstKeyword(query);
        List<SearchHit> hits = new ArrayList<>();
        for (Item it : all()) {
            if (!includeHidden && it.isHidden()) continue;
            if (filters != null && !filters.accept(it)) continue;
            if (!pred.test(it)) continue;
            int count = needle.isEmpty() ? 1 : countOccurrences(QueryParser.haystack(it), needle);
            hits.add(new SearchHit(it, score(it, count), Math.max(count, 1),
                    fragments(it, needle)));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits;
    }

    /** F-23: thread grouping by normalised subject. */
    public Map<String, List<Item>> emailThreads() {
        Map<String, List<Item>> threads = new LinkedHashMap<>();
        for (Item it : all()) {
            if (!it.isEmail() || it.subject() == null) continue;
            String key = it.subject().replaceAll("(?i)^(re|fw|fwd)\\s*:\\s*", "").trim();
            threads.computeIfAbsent(key, k -> new ArrayList<>()).add(it);
        }
        return threads;
    }

    /** F-05 / F-23: SHA-256 groups with more than one member. */
    public Map<String, List<Item>> duplicates() {
        return all().stream()
                .filter(i -> i.sha256() != null)
                .collect(Collectors.groupingBy(Item::sha256, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public Map<ItemStatus, Long> statusCounts() {
        Map<ItemStatus, Long> m = new LinkedHashMap<>();
        for (ItemStatus s : ItemStatus.values()) m.put(s, 0L);
        for (Item it : all()) m.merge(it.status(), 1L, Long::sum);
        return m;
    }

    public void log(String action, String detail, String user) {
        audit.add(new AuditEntry(java.time.Instant.now(), action, detail, user));
    }

    private static float score(Item it, int count) {
        float s = count;
        if (it.isEmail()) s += 0.5f;
        if (!it.tags().isEmpty()) s += 0.25f;
        return s;
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static List<String> fragments(Item it, String needle) {
        String text = it.extractedText();
        if (needle.isEmpty() || text.isEmpty()) {
            return List.of(text.length() > 160 ? text.substring(0, 160) + "…" : text);
        }
        List<String> out = new ArrayList<>();
        String lower = text.toLowerCase();
        int i = 0;
        while ((i = lower.indexOf(needle, i)) >= 0 && out.size() < 5) {
            int from = Math.max(0, i - 60);
            int to = Math.min(text.length(), i + needle.length() + 60);
            out.add((from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : ""));
            i += needle.length();
        }
        return out.isEmpty()
                ? List.of(text.length() > 160 ? text.substring(0, 160) + "…" : text)
                : out;
    }

    public static String firstKeyword(String q) {
        if (q == null) return "";
        String cleaned = q.replaceAll("/[^/]*/", " ")
                          .replaceAll("\\b\\w+:\\S*", " ")
                          .replaceAll("[()~*?\"]", " ")
                          .replaceAll("\\b(AND|OR|NOT)\\b", " ");
        for (String t : cleaned.trim().split("\\s+")) {
            if (t.length() > 2) return t.toLowerCase();
        }
        return "";
    }

    public record AuditEntry(java.time.Instant when, String action, String detail, String user) {}
}
