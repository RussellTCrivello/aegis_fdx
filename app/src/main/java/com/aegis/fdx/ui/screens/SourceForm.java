package com.aegis.fdx.ui.screens;

import com.aegis.fdx.facade.SourceDraft;
import com.aegis.fdx.facade.dto.SourceDto;
import com.aegis.fdx.ui.Fas;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * The source create/edit form.
 *
 * <p>One definition used by both the create action on the Sources list and the edit
 * action on the Source detail destination, so the two can never drift apart.
 */
final class SourceForm {

    private SourceForm() {
    }

    /** Blank form. Calls back with the draft when the operator confirms. */
    static void showCreate(Consumer<SourceDraft> onAccept) {
        show(null, onAccept);
    }

    /** Form pre-filled from an existing record. */
    static void showEdit(SourceDto existing, Consumer<SourceDraft> onAccept) {
        show(existing, onAccept);
    }

    private static void show(SourceDto existing, Consumer<SourceDraft> onAccept) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(existing == null ? "Add Source" : "Edit Source");
        dlg.setHeaderText(existing == null
                ? "Record where material came from"
                : "Update " + existing.name());
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        TextField name = Fas.field("Source name");
        TextField job = Fas.field("Job / type");
        TextField importance = Fas.field("0.0 - 1.0");
        TextField country = Fas.field("Country");
        TextField city = Fas.field("City");
        TextArea description = new TextArea();
        description.setPrefRowCount(2);
        TextField accounts = Fas.field("Social media accounts");
        TextField attachments = Fas.field("Attachments");
        TextArea note = new TextArea();
        note.setPrefRowCount(2);
        TextField ownership = Fas.field("Ownership");
        TextField accessStatus = Fas.field("Access status");
        DatePicker entryDate = new DatePicker(LocalDate.now());

        if (existing == null) {
            importance.setText("0.50");
        } else {
            name.setText(nz(existing.name()));
            job.setText(nz(existing.job()));
            importance.setText(String.format("%.2f", existing.importance()));
            country.setText(nz(existing.country()));
            city.setText(nz(existing.city()));
            description.setText(nz(existing.description()));
            accounts.setText(nz(existing.accounts()));
            attachments.setText(nz(existing.attachments()));
            note.setText(nz(existing.note()));
            ownership.setText(nz(existing.ownership()));
            accessStatus.setText(nz(existing.accessStatus()));
            if (existing.entryDate() != null) {
                entryDate.setValue(existing.entryDate());
            }
        }

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        g.setPadding(new Insets(16));
        int r = 0;
        g.add(Fas.formField("Source Name *", name), 0, r);
        g.add(Fas.formField("Job/Type *", job), 1, r++);
        g.add(Fas.formField("Importance *", importance), 0, r);
        g.add(Fas.formField("Country *", country), 1, r++);
        g.add(Fas.formField("City", city), 0, r);
        g.add(Fas.formField("Social Media Accounts", accounts), 1, r++);
        g.add(Fas.formField("Attachments", attachments), 0, r);
        g.add(Fas.formField("Ownership", ownership), 1, r++);
        g.add(Fas.formField("Access Status", accessStatus), 0, r);
        g.add(Fas.formField("Date of Discovery", entryDate), 1, r++);
        g.add(Fas.formField("Description", description), 0, r, 2, 1);
        r++;
        g.add(Fas.formField("Notes", note), 0, r, 2, 1);

        dlg.getDialogPane().setContent(g);
        dlg.getDialogPane().setPrefWidth(680);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) {
                return;
            }
            double imp;
            try {
                imp = Double.parseDouble(importance.getText().trim());
            } catch (NumberFormatException ex) {
                Alert a = new Alert(Alert.AlertType.ERROR,
                        "Importance must be a number between 0.0 and 1.0", ButtonType.OK);
                a.setHeaderText("Invalid input");
                a.showAndWait();
                return;
            }
            onAccept.accept(new SourceDraft(name.getText(), country.getText(),
                    job.getText(), imp)
                    .city(city.getText())
                    .description(description.getText())
                    .accounts(accounts.getText())
                    .note(note.getText())
                    .attachments(attachments.getText())
                    .ownership(ownership.getText())
                    .accessStatus(accessStatus.getText())
                    .entryDate(entryDate.getValue()));
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
