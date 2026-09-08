package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.facade.dto.NotificationDto;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Python parity: {@code templates/Notifications/notifications.html} and the
 * {@code /api/notifications} routes, including the unread/active filters, the
 * stats strip and the upcoming-events list.
 */
public final class NotificationsScreen implements Screen {

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AegisFacades facades;
    private final ObservableList<NotificationDto> rows = FXCollections.observableArrayList();
    private CheckBox unreadOnly;
    private CheckBox activeOnly;
    private VBox cUnread;
    private VBox cActive;
    private VBox cUpcoming;
    private TableView<NotificationDto> table;

    public NotificationsScreen(AegisFacades facades) {
        this.facades = facades;
    }

    @Override
    public String title() {
        return "Notifications";
    }

    @Override
    public String icon() {
        return Icons.BELL;
    }

    @Override
    public Node build() {
        unreadOnly = new CheckBox("Unread only");
        unreadOnly.setOnAction(e -> onShow());
        activeOnly = new CheckBox("Hide dismissed");
        activeOnly.setOnAction(e -> onShow());

        Button refresh = Fas.outline("Refresh", Icons.REFRESH);
        refresh.setOnAction(e -> onShow());

        cUnread = Fas.statCard(Icons.BELL, Fas.WARNING, "0", "Unread");
        cActive = Fas.statCard(Icons.CHECK_CIRCLE, Fas.SUCCESS, "0", "Active");
        cUpcoming = Fas.statCard(Icons.CLOCK, Fas.INFO, "0", "Upcoming Events");

        table = new TableView<>(rows);
        table.setPlaceholder(Fas.emptyState("No notifications."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<NotificationDto, String> cType = new TableColumn<>("Type");
        cType.setPrefWidth(130);
        cType.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().alertType()));

        TableColumn<NotificationDto, NotificationDto> cPri = new TableColumn<>("Priority");
        cPri.setPrefWidth(95);
        cPri.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cPri.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(NotificationDto n, boolean empty) {
                super.updateItem(n, empty);
                if (empty || n == null) { setGraphic(null); return; }
                String v = switch (String.valueOf(n.priority()).toLowerCase()) {
                    case "high", "critical" -> "danger";
                    case "low" -> "muted";
                    default -> "info";
                };
                setGraphic(Fas.badge(n.priority(), v));
            }
        });

        TableColumn<NotificationDto, String> cTitle = new TableColumn<>("Title");
        cTitle.setPrefWidth(220);
        cTitle.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().title()));

        TableColumn<NotificationDto, String> cMsg = new TableColumn<>("Message");
        cMsg.setPrefWidth(280);
        cMsg.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().message() == null ? "" : c.getValue().message()));

        TableColumn<NotificationDto, String> cWhen = new TableColumn<>("Created");
        cWhen.setPrefWidth(135);
        cWhen.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().createdAt() == null ? "" : DT.format(c.getValue().createdAt())));

        TableColumn<NotificationDto, NotificationDto> cAct = new TableColumn<>("Actions");
        cAct.setPrefWidth(150);
        cAct.setSortable(false);
        cAct.setCellValueFactory(c ->
                new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        cAct.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(NotificationDto n, boolean empty) {
                super.updateItem(n, empty);
                if (empty || n == null) { setGraphic(null); return; }
                Button read = Fas.ghost("", Icons.CHECK_CIRCLE);
                read.setDisable(n.read());
                read.setOnAction(e -> { facades.notifications().markAsRead(n.id()); onShow(); });
                Button dismiss = Fas.ghost("", Icons.CLOSE);
                dismiss.setDisable(n.dismissed());
                dismiss.setOnAction(e -> {
                    facades.notifications().dismissNotification(n.id());
                    onShow();
                });
                setGraphic(Fas.row(2, read, dismiss));
            }
        });

        table.getColumns().addAll(cType, cPri, cTitle, cMsg, cWhen, cAct);

        VBox content = new VBox(16,
                Fas.pageHeader("Notifications", "Home / Notifications", refresh),
                Fas.statsGrid(cUnread, cActive, cUpcoming),
                Fas.cardWithHeader("All Notifications", null,
                        new VBox(10, Fas.row(14, unreadOnly, activeOnly), table)));
        content.setPadding(new Insets(20));
        return content;
    }

    @Override
    public void onShow() {
        try {
            var page = facades.notifications().getNotifications(
                    unreadOnly.isSelected(), activeOnly.isSelected(), null, 200, 0);
            rows.setAll(page.results());
            var stats = facades.notifications().getStats();
            Fas.setStat(cUnread, String.valueOf(stats.unread()));
            Fas.setStat(cActive, String.valueOf(stats.active()));
            Fas.setStat(cUpcoming,
                    String.valueOf(facades.notifications().getUpcomingEvents().size()));
        } catch (RuntimeException e) {
            rows.clear();
        }
    }
}
