package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the figures shown on the overview and analysis screens.
 *
 * <p>Everything here is derived from data the engine already holds; this facade adds
 * no storage of its own.
 */
public final class DashboardFacade {

    private final LiveCase liveCase;

    public DashboardFacade(LiveCase liveCase) {
        this.liveCase = liveCase;
    }

    /** Headline processing counters. */
    public Stats getStats() {
        try {
            Map<ItemStatus, Integer> counts = liveCase.statusCounts();
            long total = 0;
            long bytes = 0;
            for (int v : counts.values()) {
                total += v;
            }
            for (Item it : liveCase.allItems()) {
                bytes += it.size();
            }
            return new Stats(
                    total,
                    counts.getOrDefault(ItemStatus.INDEXED, 0),
                    counts.getOrDefault(ItemStatus.ERROR, 0),
                    counts.getOrDefault(ItemStatus.LOCKED, 0),
                    counts.getOrDefault(ItemStatus.UNSUPPORTED, 0),
                    liveCase.duplicateClusters().size(),
                    bytes);
        } catch (Exception e) {
            throw FacadeException.internal("failed to build dashboard stats", e);
        }
    }

    /** Elements matching a type and/or custodian. */
    public List<Item> getFilesFiltered(String fileType, String custodian, int limit) {
        int lim = Validate.limit(limit);
        try {
            List<Item> out = new ArrayList<>();
            for (Item it : liveCase.allItems()) {
                if (fileType != null && !fileType.isBlank()
                        && !fileType.equalsIgnoreCase(it.extension())) {
                    continue;
                }
                if (custodian != null && !custodian.isBlank()
                        && !custodian.equals(it.custodian())) {
                    continue;
                }
                out.add(it);
                if (out.size() >= lim) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            throw FacadeException.internal("failed to filter files", e);
        }
    }

    /** Element counts grouped by custodian. */
    public Map<String, Integer> getSourcesFiltered() {
        return countBy(it -> it.custodian() == null ? "(unassigned)" : it.custodian());
    }

    /** Element counts grouped by container path. */
    public Map<String, Integer> getSidesFiltered() {
        return countBy(it -> {
            String c = it.containerPath();
            return (c == null || c.isBlank()) ? "(top level)" : c;
        });
    }

    /** Element counts grouped by tag. */
    public Map<String, Integer> getCategoriesFiltered() {
        try {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Item it : liveCase.allItems()) {
                for (String tag : it.tags()) {
                    out.merge(tag, 1, Integer::sum);
                }
            }
            return sortDescending(out);
        } catch (Exception e) {
            throw FacadeException.internal("failed to group categories", e);
        }
    }

    /** Element counts grouped by file extension. */
    public Map<String, Integer> getFileTypeBreakdown() {
        return countBy(it -> {
            String e = it.extension();
            return (e == null || e.isBlank()) ? "(none)" : e.toLowerCase();
        });
    }

    /** Exact-duplicate clusters, keyed by SHA-256. */
    public Map<String, List<String>> getSimilarFiles() {
        try {
            return liveCase.duplicateClusters();
        } catch (Exception e) {
            throw FacadeException.internal("failed to load similar files", e);
        }
    }

    /** Headline counters for the overview screen. */
    public record Stats(long totalFiles, int indexed, int errors, int locked,
                        int unsupported, int duplicateClusters, long totalBytes) {
    }

    private Map<String, Integer> countBy(java.util.function.Function<Item, String> key) {
        try {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Item it : liveCase.allItems()) {
                out.merge(key.apply(it), 1, Integer::sum);
            }
            return sortDescending(out);
        } catch (Exception e) {
            throw FacadeException.internal("failed to group items", e);
        }
    }

    private static Map<String, Integer> sortDescending(Map<String, Integer> in) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(in.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
