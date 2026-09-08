package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.NotificationDto;
import com.aegis.fdx.facade.dto.Page;
import com.aegis.fdx.store.CorpusDatabase;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages notifications: alerts raised by processing, similarity detection and
 * scheduled events, with read and dismissed state per alert.
 */
public final class NotificationFacade {

    private final CorpusDatabase db;

    public NotificationFacade(CorpusDatabase db) {
        this.db = db;
    }

    /** Most recent notifications, unfiltered. */
    public Page<NotificationDto> getNotifications() {
        return getNotifications(false, false, null, 50, 0);
    }

    /** Notifications matching the given filters. */
    public Page<NotificationDto> getNotifications(boolean unreadOnly, boolean activeOnly,
                                                  String alertType, int limit, int offset) {
        int lim = Validate.limit(limit);
        int off = Validate.offset(offset);
        try {
            List<CorpusDatabase.Row> rows = db.selectAlerts(unreadOnly, activeOnly, alertType, lim, off);
            List<NotificationDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toDto(r));
            }
            int total = unreadOnly ? db.countUnreadAlerts() : db.countActiveAlerts();
            return new Page<>(out, total);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to list notifications", e);
        }
    }

    /** @throws FacadeException NOT_FOUND if no such notification exists */
    public NotificationDto getNotification(int notificationId) {
        Validate.positiveId(notificationId, "notificationId");
        try {
            CorpusDatabase.Row r = db.selectAlertById(notificationId);
            if (r == null) {
                throw FacadeException.notFound("notification", notificationId);
            }
            return toDto(r);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load notification", e);
        }
    }

    /** Raises a notification. */
    public int createNotification(String alertType, String priority, String title,
                                  String message, String fileId, Instant eventDate) {
        String t = Validate.required(alertType, "alertType");
        String ti = Validate.required(title, "title");
        String p = (priority == null || priority.isBlank()) ? "normal" : priority.trim();
        try {
            return db.insertAlert(t, p, ti, Validate.optional(message), fileId,
                    Instant.now(), eventDate);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to create notification", e);
        }
    }

    /** Raises a "similar material detected" notification. */
    public int createSimilarFilesNotification(String fileId, List<String> similarFileIds,
                                              String priority) {
        Validate.required(fileId, "elementId");
        int n = similarFileIds == null ? 0 : similarFileIds.size();
        return createNotification("similar_files", priority,
                "Similar files detected",
                n + " similar file(s) found for " + fileId,
                fileId, null);
    }

    /** Raises a notification tied to a future date. */
    public int createFutureEventNotification(String fileId, Instant eventDate,
                                             String title, String message) {
        Validate.required(fileId, "elementId");
        if (eventDate == null) {
            throw FacadeException.validation("event_date is required");
        }
        return createNotification("future_event", "normal",
                title == null ? "Upcoming event" : title, message, fileId, eventDate);
    }

    /** @return true if the notification was marked read */
    public boolean markAsRead(int notificationId) {
        Validate.positiveId(notificationId, "notificationId");
        try {
            return db.markAlertRead(notificationId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to mark notification read", e);
        }
    }

    /** @return true if the notification was dismissed */
    public boolean dismissNotification(int notificationId) {
        Validate.positiveId(notificationId, "notificationId");
        try {
            return db.dismissAlert(notificationId);
        } catch (SQLException e) {
            throw FacadeException.internal("failed to dismiss notification", e);
        }
    }

    /** Number of unread notifications. */
    public int getPendingCount() {
        try {
            return db.countUnreadAlerts();
        } catch (SQLException e) {
            throw FacadeException.internal("failed to count notifications", e);
        }
    }

    /** Unread and active counts. */
    public Stats getStats() {
        try {
            return new Stats(db.countUnreadAlerts(), db.countActiveAlerts());
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load notification stats", e);
        }
    }

    /** Scheduled events falling in the next 30 days. */
    public List<NotificationDto> getUpcomingEvents() {
        return getUpcomingEvents(30);
    }

    public List<NotificationDto> getUpcomingEvents(int daysAhead) {
        if (daysAhead <= 0) {
            throw FacadeException.validation("days_ahead must be greater than 0");
        }
        Instant now = Instant.now();
        Instant until = now.plus(daysAhead, ChronoUnit.DAYS);
        try {
            List<CorpusDatabase.Row> rows = db.selectUpcomingAlerts(now, until, 500);
            List<NotificationDto> out = new ArrayList<>(rows.size());
            for (CorpusDatabase.Row r : rows) {
                out.add(toDto(r));
            }
            return out;
        } catch (SQLException e) {
            throw FacadeException.internal("failed to load upcoming events", e);
        }
    }

    /** Notification counts. */
    public record Stats(int unread, int active) {
    }

    private static NotificationDto toDto(CorpusDatabase.Row r) {
        return new NotificationDto(
                r.i("id"), r.str("alert_type"), r.str("priority"), r.str("title"),
                r.str("message"), r.str("element_id"), r.bool("is_read"), r.bool("dismissed"),
                r.instant("created_at"), r.instant("event_date"));
    }
}
