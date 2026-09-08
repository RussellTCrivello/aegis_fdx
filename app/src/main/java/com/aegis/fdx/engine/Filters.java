package com.aegis.fdx.engine;

import com.aegis.fdx.model.Item;
import com.aegis.fdx.model.ItemStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** F-16 facet filters applied on top of the parsed query. */
public final class Filters {

    public final Set<String> types = new HashSet<>();
    public final Set<String> custodians = new HashSet<>();
    public final Set<String> tags = new HashSet<>();
    public final Set<ItemStatus> statuses = new HashSet<>();
    public Instant dateFrom;
    public Instant dateTo;
    public Boolean hasAttachments;
    public String container;

    public boolean isEmpty() {
        return types.isEmpty() && custodians.isEmpty() && tags.isEmpty() && statuses.isEmpty()
                && dateFrom == null && dateTo == null && hasAttachments == null
                && (container == null || container.isBlank());
    }

    public boolean accept(Item it) {
        if (!types.isEmpty() && !types.contains(it.extension())) return false;
        if (!custodians.isEmpty() && !custodians.contains(it.custodian())) return false;
        if (!statuses.isEmpty() && !statuses.contains(it.status())) return false;
        if (!tags.isEmpty() && it.tags().stream().noneMatch(tags::contains)) return false;
        if (hasAttachments != null && it.hasAttachments() != hasAttachments) return false;
        if (container != null && !container.isBlank()) {
            String cp = it.containerPath() == null ? "" : it.containerPath();
            if (!cp.toLowerCase().contains(container.toLowerCase())) return false;
        }
        Instant when = it.modified() != null ? it.modified() : it.created();
        if (when != null) {
            if (dateFrom != null && when.isBefore(dateFrom)) return false;
            if (dateTo != null && when.isAfter(dateTo)) return false;
        }
        return true;
    }

    public void clear() {
        types.clear(); custodians.clear(); tags.clear(); statuses.clear();
        dateFrom = null; dateTo = null; hasAttachments = null; container = null;
    }
}
