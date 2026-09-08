package com.aegis.fdx.ui.screens;

import com.aegis.fdx.ai.agent.AgentActivity;
import com.aegis.fdx.ai.agent.AgentService;
import com.aegis.fdx.ai.tools.AgentContext;
import com.aegis.fdx.ai.tools.ToolResult;
import com.aegis.fdx.facade.AegisFacades;
import com.aegis.fdx.ui.Fas;
import com.aegis.fdx.ui.Icons;
import com.aegis.fdx.ui.Screen;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The analysis assistant: ask a question, watch which tools ran, read a grounded answer.
 *
 * <p>Runs the agent off the UI thread and publishes back with
 * {@link Platform#runLater}, so a slow local model never freezes the interface. Every
 * run leaves an activity trail showing which tools executed and which records were
 * consulted.
 */
public final class AgentScreen implements Screen {

    private final AegisFacades facades;
    private final AgentService agent;

    private TextField questionField;
    private TextArea answerArea;
    private TextArea traceArea;
    private Label statusLabel;
    private Label modelLabel;
    private ProgressIndicator spinner;
    private Button askButton;
    private Button cancelButton;
    private CheckBox allowChanges;
    private FlowPane suggestions;
    private VBox evidenceBox;
    private AtomicBoolean cancelled = new AtomicBoolean(false);

    public AgentScreen(AegisFacades facades, AgentService agent) {
        this.facades = facades;
        this.agent = agent;
    }

    @Override
    public String title() {
        return "Assistant";
    }

    @Override
    public String icon() {
        return Icons.SHIELD;
    }

    @Override
    public Node build() {
        questionField = Fas.field("Ask about this case\u2026");
        HBox.setHgrow(questionField, Priority.ALWAYS);
        questionField.setOnAction(e -> run());

        askButton = Fas.primary("Ask", Icons.SEARCH);
        askButton.setOnAction(e -> run());

        cancelButton = Fas.ghost("Cancel", Icons.CLOSE);
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> cancelled.set(true));

        spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);

        allowChanges = new CheckBox("Allow the assistant to change data");
        allowChanges.setTooltip(new javafx.scene.control.Tooltip(
                "Off by default. When off, the assistant can only read. When on, it may "
                        + "also classify files and set review state \u2014 it can never alter "
                        + "original material."));

        statusLabel = Fas.muted("");
        modelLabel = Fas.muted("");

        suggestions = new FlowPane(8, 8);

        answerArea = new TextArea();
        answerArea.setEditable(false);
        answerArea.setWrapText(true);
        answerArea.setPrefRowCount(10);
        answerArea.setPromptText("The assistant's answer appears here.");

        evidenceBox = new VBox(4);

        traceArea = new TextArea();
        traceArea.setEditable(false);
        traceArea.setWrapText(false);
        traceArea.setPrefRowCount(9);
        traceArea.setPromptText("Which tools ran, what they returned, and how long they took.");
        traceArea.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");

        VBox askCard = Fas.card(
                Fas.row(10, questionField, askButton, cancelButton, spinner),
                Fas.row(14, allowChanges, Fas.spacer(), modelLabel),
                suggestions);

        VBox answerCard = Fas.cardWithHeader("Answer",
                "Grounded in records retrieved from this case",
                new VBox(10, answerArea, evidenceBox));

        VBox traceCard = Fas.cardWithHeader("Agent activity",
                "Tools used and evidence consulted", traceArea);

        VBox content = new VBox(16,
                Fas.pageHeader("Assistant", "Home / Assistant"),
                askCard, answerCard, traceCard,
                statusLabel);
        content.setPadding(new Insets(20));

        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    @Override
    public void onShow() {
        refreshAvailability();
        refreshSuggestions(currentContext());
    }

    private void refreshAvailability() {
        String reason = agent.unavailableReason();
        if (reason == null) {
            modelLabel.setText("local model: " + agent.modelId());
            askButton.setDisable(false);
            statusLabel.setText("");
        } else {
            modelLabel.setText("local model unavailable");
            askButton.setDisable(true);
            statusLabel.setText(reason);
        }
    }

    private void refreshSuggestions(AgentContext ctx) {
        suggestions.getChildren().clear();
        for (String s : AgentService.suggestionsFor(ctx)) {
            Button b = Fas.ghost(s, null);
            b.setOnAction(e -> {
                questionField.setText(s);
                run();
            });
            suggestions.getChildren().add(b);
        }
    }

    /** Context for a question asked from this screen. */
    private AgentContext currentContext() {
        AgentContext ctx = AgentContext.ofScreen("Assistant");
        return allowChanges != null && allowChanges.isSelected() ? ctx.allowingMutations() : ctx;
    }

    private void run() {
        String q = questionField.getText();
        if (q == null || q.isBlank()) {
            return;
        }
        if (agent.unavailableReason() != null) {
            refreshAvailability();
            return;
        }

        cancelled = new AtomicBoolean(false);
        askButton.setDisable(true);
        cancelButton.setDisable(false);
        spinner.setVisible(true);
        statusLabel.setText("Working\u2026");
        answerArea.clear();
        traceArea.clear();
        evidenceBox.getChildren().clear();

        AgentContext ctx = currentContext();
        AtomicBoolean flag = cancelled;

        Thread worker = new Thread(() -> {
            AgentActivity activity = agent.ask(q, ctx, flag);
            Platform.runLater(() -> publish(activity));
        }, "fas-agent");
        worker.setDaemon(true);
        worker.start();
    }

    private void publish(AgentActivity a) {
        spinner.setVisible(false);
        askButton.setDisable(false);
        cancelButton.setDisable(true);

        if (a.failed()) {
            answerArea.setText("The assistant could not answer.\n\n" + a.failure());
            statusLabel.setText("Failed after " + a.totalMillis() + " ms");
        } else {
            answerArea.setText(a.finalAnswer());
            statusLabel.setText(a.steps().size() + " tool call(s), "
                    + a.modelCalls() + " model turn(s), " + a.totalMillis() + " ms"
                    + (a.isGrounded() ? "" : "  \u2014 no supporting records found"));
        }
        traceArea.setText(a.toTrace());

        evidenceBox.getChildren().clear();
        if (!a.evidence().isEmpty()) {
            evidenceBox.getChildren().add(Fas.fieldLabel("RECORDS CONSULTED"));
            FlowPane chips = new FlowPane(6, 6);
            for (ToolResult.Evidence e : a.evidence()) {
                Label chip = Fas.badge(e.kind() + " " + e.id(), variantFor(e.kind()));
                chip.setTooltip(new javafx.scene.control.Tooltip(
                        e.label() == null ? e.id() : e.label()));
                chips.getChildren().add(chip);
            }
            evidenceBox.getChildren().add(chips);
        }
    }

    private static String variantFor(String kind) {
        return switch (kind) {
            case "item" -> "info";
            case "source" -> "success";
            case "aspect" -> "warning";
            case "category", "keyword" -> "muted";
            default -> "muted";
        };
    }
}
