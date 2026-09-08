package com.aegis.fdx.ui;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.ToolResult;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The contextual "Analyze this" action, attachable to any record.
 *
 * <p>Opens a dialog that runs the <em>same</em> local agent as the Assistant
 * destination, with the record already in context, so the operator never pastes an
 * identifier. There is no second AI implementation behind this: it calls
 * {@link AgentService} and the same tool gateway.
 *
 * <p>Read-only. These actions never enable the mutating tools.
 */
public final class AnalyzeAction {

    private AnalyzeAction() {
    }

    /**
     * Builds a button that analyses whatever {@code context} describes.
     *
     * @param label   button text, e.g. "Analyze Source"
     * @param prompt  the question put to the agent
     * @param context the record context, supplied fresh at click time
     */
    public static Button button(AgentService agent, String label, String prompt,
                                java.util.function.Supplier<AgentContext> context) {
        Button b = Fas.ghost(label, Icons.SHIELD);
        b.setOnAction(e -> run(agent, prompt, context.get()));
        return b;
    }

    /** Same, styled as a secondary action. */
    public static Button secondaryButton(AgentService agent, String label, String prompt,
                                         java.util.function.Supplier<AgentContext> context) {
        Button b = Fas.secondary(label, Icons.SHIELD);
        b.setOnAction(e -> run(agent, prompt, context.get()));
        return b;
    }

    /** Opens the analysis dialog and starts the run. */
    public static void run(AgentService agent, String prompt, AgentContext context) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Analysis");
        dlg.setHeaderText(prompt);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label status = Fas.muted("Working\u2026");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);

        TextArea answer = new TextArea();
        answer.setEditable(false);
        answer.setWrapText(true);
        answer.setPrefRowCount(10);

        FlowPane chips = new FlowPane(6, 6);

        TextArea trace = new TextArea();
        trace.setEditable(false);
        trace.setPrefRowCount(6);
        trace.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");

        VBox body = new VBox(12,
                Fas.row(8, spinner, status),
                Fas.fieldLabel("ANSWER"), answer,
                Fas.fieldLabel("RECORDS CONSULTED"), chips,
                Fas.fieldLabel("AGENT ACTIVITY"), trace);
        body.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(body);
        sp.setFitToWidth(true);
        sp.setPrefSize(720, 560);
        dlg.getDialogPane().setContent(sp);

        String unavailable = agent.unavailableReason();
        if (unavailable != null) {
            spinner.setVisible(false);
            status.setText("The local assistant is not available");
            answer.setText(unavailable + "\n\nThe rest of the application is unaffected.");
            dlg.showAndWait();
            return;
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        dlg.setOnCloseRequest(e -> cancelled.set(true));

        Thread worker = new Thread(() -> {
            AgentActivity a = agent.ask(prompt, context, cancelled);
            Platform.runLater(() -> {
                spinner.setVisible(false);
                if (a.failed()) {
                    status.setText("Failed after " + a.totalMillis() + " ms");
                    answer.setText(a.failure());
                } else {
                    status.setText(a.steps().size() + " tool call(s), "
                            + a.modelCalls() + " model turn(s), " + a.totalMillis() + " ms"
                            + (a.isGrounded() ? "" : "  \u2014 no supporting records"));
                    answer.setText(a.finalAnswer());
                }
                chips.getChildren().clear();
                for (ToolResult.Evidence ev : a.evidence()) {
                    chips.getChildren().add(Fas.badge(ev.kind() + " " + ev.id(),
                            switch (ev.kind()) {
                                case "item" -> "info";
                                case "source" -> "success";
                                case "aspect" -> "warning";
                                default -> "muted";
                            }));
                }
                if (chips.getChildren().isEmpty()) {
                    chips.getChildren().add(Fas.muted("none"));
                }
                trace.setText(a.toTrace());
            });
        }, "fas-analyze");
        worker.setDaemon(true);
        worker.start();

        dlg.showAndWait();
        cancelled.set(true);
    }
}
