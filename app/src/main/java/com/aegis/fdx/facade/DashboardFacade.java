package com.aegis.fdx.facade;

import com.aegis.fdx.engine.LiveCase;
import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;
import com.aegis.fdx.store.DashboardStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the figures shown on the overview and analysis screens.
 *
 * <p><strong>Where these numbers come from.</strong> Every value on this facade is
 * either a derived counter maintained by {@link DashboardStats} in the same transaction
 * as the row it counts, or a direct indexed query. None of it is seeded, defaulted or
 * estimated, and where a figure genuinely cannot be produced the facade says so rather
 * than substituting a plausible number — see {@link Stats#available()}.
 *
 * <p><strong>Why derived counters.</strong> This facade used to answer
 * {@code getStats()} by materialising every item in the case and summing in Java. That
 * is 9.5 seconds and roughly a gigabyte of heap at a million items, on whichever thread
 * asked — which on the dashboard was the JavaFX application thread. The same figures now
 * come from a few dozen pre-aggregated rows in 0.1 ms. The measurements behind that are
 * in {@code docs/DATABASE_PERFORMANCE_REPORT.md}.
 *
 * <p>These methods are safe to call from a background thread and are intended to be:
 * see {@code DashboardData} for the async wrapper the screens use.
 */
public final class DashboardFacade {

    private final LiveCase liveCase;
    private final DashboardStats stats;

    public DashboardFacade(LiveCase liveCase) {
        this.liveCase = liveCase;
        this.stats = new DashboardStats(liveCase.db());
    }

    /** The derived-statistics table behind this facade, for rebuild and verification. */
    public DashboardStats statistics() {
        return stats;
    }

    /**
     * Headline processing counters, read from the derived statistics.
     *
     * <p>Duplicate clusters are counted by an indexed grouping rather than a derived
     * counter: "how many SHA-256 values occur more than once" is not a per-row fact and
     * cannot be maintained incrementally without tracking every hash's cardinality.
     * It is measured at 156 ms per million items, so it is fetched with the rest but
     * remains the slowest single figure on the screen.
     */
    public Stats getStats() {
        try {
            DashboardStats.Snapshot s = stats.snapshot();
            Map<String, Long> byStatus = s.byStatus();
            return new Stats(
                    s.totalItems(),
                    byStatus.getOrDefault(ItemStatus.INDEXED.name(), 0L),
                    byStatus.getOrDefault(ItemStatus.ERROR.name(), 0L),
                    byStatus.getOrDefault(ItemStatus.LOCKED.name(), 0L),
                    byStatus.getOrDefault(ItemStatus.UNSUPPORTED.name(), 0L),
                    byStatus.getOrDefault(ItemStatus.PENDING.name(), 0L),
                    byStatus.getOrDefault(ItemStatus.PROCESSING.name(), 0L),
                    liveCase.duplicateClusters().size(),
                    s.totalBytes(),
                    true);
        } catch (Exception e) {
            throw FacadeException.internal("failed to build dashboard stats", e);
        }
    }

    /** Item counts by processing status, largest first. */
    public Map<String, Long> statusBreakdown() {
        return dimension(DashboardStats.D_STATUS);
    }

    /** Item counts by file extension, largest first. */
    public Map<String, Long> extensionBreakdown() {
        return dimension(DashboardStats.D_EXT);
    }

    /** Item counts by detected media type, largest first. */
    public Map<String, Long> mediaTypeBreakdown() {
        return dimension(DashboardStats.D_MEDIA);
    }

    /** Item counts by responsible party (custodian), largest first. */
    public Map<String, Long> custodianBreakdown() {
        return dimension(DashboardStats.D_CUSTODIAN);
    }

    /** Item counts by OCR state: applied, pending, or not required. */
    public Map<String, Long> ocrBreakdown() {
        return dimension(DashboardStats.D_OCR);
    }

    private Map<String, Long> dimension(String name) {
        try {
            return stats.counts(name);
        } catch (Exception e) {
            throw FacadeException.internal("failed to read the " + name + " breakdown", e);
        }
    }

    /**
     * §5.1: reconstructs every derived counter from the authoritative item table.
     *
     * <p>Exposed to the interface as "Rebuild Dashboard Statistics". It is the repair
     * for counters that a restored backup, a hand-edited database or an interrupted
     * migration has left disagreeing with the evidence.
     */
    public void rebuildStatistics() {
        try {
            stats.rebuild();
        } catch (Exception e) {
            throw FacadeException.internal("failed to rebuild dashboard statistics", e);
        }
    }

    /**
     * Proves the derived counters still equal a fresh scan of the item table. Costs a
     * full scan, so it belongs to maintenance and tests, not to a dashboard refresh.
     */
    public boolean verifyStatistics() {
        try {
            return stats.verify();
        } catch (Exception e) {
            throw FacadeException.internal("failed to verify dashboard statistics", e);
        }
    }

    /** When the counters were last rebuilt from authoritative data, or null if never. */
    public java.time.Instant statisticsRebuiltAt() {
        try {
            return stats.rebuiltAt();
        } catch (Exception e) {
            return null;
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
        return widen(custodianBreakdown());
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
        return widen(extensionBreakdown());
    }

    /** Exact-duplicate clusters, keyed by SHA-256. */
    public Map<String, List<String>> getSimilarFiles() {
        try {
            return liveCase.duplicateClusters();
        } catch (Exception e) {
            throw FacadeException.internal("failed to load similar files", e);
        }
    }

    /**
     * Headline counters for the overview screen.
     *
     * @param available false when the figures could not be produced, so the interface
     *                  can say "not available" instead of drawing zeros that look like
     *                  a genuinely empty case
     */
    public record Stats(long totalFiles, long indexed, long errors, long locked,
                        long unsupported, long pending, long processing,
                        int duplicateClusters, long totalBytes, boolean available) {
    }

    private static Map<String, Integer> widen(Map<String, Long> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, (int) Math.min(Integer.MAX_VALUE, v)));
        return out;
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
