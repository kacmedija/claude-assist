package com.kacmedija.claudeassist.review;

import com.kacmedija.claudeassist.services.ClaudeAssistService;
import com.kacmedija.claudeassist.services.ContextManager;
import com.kacmedija.claudeassist.services.StreamJsonService;
import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.kacmedija.claudeassist.toolwindow.ClaudeTerminalPanel;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.util.SlowOperations;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Right-side detail panel showing the selected issue's details,
 * with navigation, fix buttons, re-test, and copy functionality.
 */
public final class ReviewDetailPanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(ReviewDetailPanel.class);

    private final Project project;
    private ReviewIssue.Issue currentIssue;

    // UI components
    private final JBLabel titleLabel = new JBLabel();
    private final JBLabel fixedBadge = new JBLabel("[FIXED]");
    private final JBLabel severityLabel = new JBLabel();
    private final JBLabel categoryLabel = new JBLabel();
    private final JBLabel fileLink = new JBLabel();
    private final JTextArea descriptionArea = new JTextArea();
    private final JTextArea suggestionArea = new JTextArea();
    private final JBLabel suggestionHeader = new JBLabel("Suggestion:");
    private final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JPanel contentPanel = new JPanel();
    private final JBLabel emptyLabel = new JBLabel("Select an issue to see details");
    private final JButton retestButton = new JButton("Re-test", AllIcons.Actions.Refresh);
    private JButton quickFixButton;
    private final JBLabel fixingStatusLabel = new JBLabel("Fixing...", new AnimatedIcon.Default(), SwingConstants.LEFT);
    private volatile CompletableFuture<?> currentFixFuture;

    public ReviewDetailPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        buildUI();
        showEmpty();
    }

    private void buildUI() {
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(JBUI.Borders.empty(12));

        // Title row with fixed badge
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setCopyable(true);
        titleRow.add(titleLabel);

        fixedBadge.setForeground(new JBColor(new Color(0x008800), new Color(0x6BCB77)));
        fixedBadge.setFont(fixedBadge.getFont().deriveFont(Font.BOLD));
        fixedBadge.setVisible(false);
        titleRow.add(fixedBadge);
        addRow(titleRow);

        // Severity + Category badges
        JPanel badgePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        badgePanel.setAlignmentX(LEFT_ALIGNMENT);
        badgePanel.add(severityLabel);
        badgePanel.add(categoryLabel);
        addRow(badgePanel);

        // File link
        fileLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fileLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                navigateToIssue();
            }
        });
        addRow(fileLink);

        // Separator
        addRow(new JSeparator());

        // Description
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setOpaque(false);
        descriptionArea.setBorder(JBUI.Borders.empty(4, 0));
        descriptionArea.setFont(UIManager.getFont("Label.font"));
        addRow(descriptionArea);

        // Suggestion header + area
        suggestionHeader.setFont(suggestionHeader.getFont().deriveFont(Font.BOLD));
        addRow(suggestionHeader);

        suggestionArea.setEditable(false);
        suggestionArea.setLineWrap(true);
        suggestionArea.setWrapStyleWord(true);
        suggestionArea.setBackground(JBColor.namedColor("Editor.background", new JBColor(0xF5F5F5, 0x2B2B2B)));
        suggestionArea.setBorder(JBUI.Borders.empty(8));
        suggestionArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        addRow(suggestionArea);

        // Buttons
        buttonPanel.setAlignmentX(LEFT_ALIGNMENT);
        buttonPanel.setBorder(JBUI.Borders.emptyTop(8));

        JButton fixInTerminal = new JButton("Fix in Terminal", AllIcons.Actions.Execute);
        fixInTerminal.addActionListener(e -> fixInTerminal());

        quickFixButton = new JButton("Quick Fix", AllIcons.Actions.QuickfixBulb);
        quickFixButton.addActionListener(e -> quickFix());

        retestButton.addActionListener(e -> retestIssue());

        JButton copy = new JButton("Copy", AllIcons.Actions.Copy);
        copy.addActionListener(e -> copyToClipboard());

        buttonPanel.add(fixInTerminal);
        buttonPanel.add(quickFixButton);
        buttonPanel.add(retestButton);
        buttonPanel.add(copy);
        addRow(buttonPanel);

        // Fixing status label (hidden by default)
        fixingStatusLabel.setVisible(false);
        fixingStatusLabel.setForeground(JBColor.GRAY);
        addRow(fixingStatusLabel);

        // Wrap in scroll pane — vertical only, no horizontal scroll
        JBScrollPane scrollPane = new JBScrollPane(contentPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        // Make content panel track the viewport width so text areas wrap correctly
        scrollPane.getViewport().addChangeListener(e -> {
            int viewportWidth = scrollPane.getViewport().getWidth();
            if (viewportWidth > 0) {
                contentPanel.setPreferredSize(new Dimension(viewportWidth, contentPanel.getPreferredSize().height));
                contentPanel.revalidate();
            }
        });

        // Empty state
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.setForeground(JBColor.GRAY);

        add(scrollPane, BorderLayout.CENTER);
    }

    private void addRow(@NotNull JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        if (component instanceof JTextArea) {
            // Let text areas grow vertically but constrain width to parent
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        } else {
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        }
        contentPanel.add(component);
        contentPanel.add(Box.createVerticalStrut(4));
    }

    // ── Public API ──────────────────────────────────────────────────

    public void showIssue(@NotNull ReviewIssue.Issue issue) {
        currentIssue = issue;

        titleLabel.setText("<html><body style='width:100%'>" + escapeHtml(issue.title()) + "</body></html>");

        // Fixed badge
        fixedBadge.setVisible(issue.isFixed());

        severityLabel.setIcon(issue.severity().getIcon());
        severityLabel.setText(issue.severity().getDisplayName());
        severityLabel.setForeground(issue.severity().getColor());

        categoryLabel.setText(issue.category().getDisplayName());

        fileLink.setText("<html><u>" + issue.file() + ":" + issue.line() + "</u></html>");
        fileLink.setIcon(AllIcons.FileTypes.Any_type);
        fileLink.setForeground(JBColor.namedColor("Link.activeForeground", JBColor.BLUE));

        descriptionArea.setText(issue.description());

        boolean hasSuggestion = issue.suggestion() != null && !issue.suggestion().isBlank();
        suggestionHeader.setVisible(hasSuggestion);
        suggestionArea.setVisible(hasSuggestion);
        if (hasSuggestion) {
            suggestionArea.setText(issue.suggestion());
        }

        // Quick Fix / Re-test button state
        if (issue.isFixing()) {
            quickFixButton.setEnabled(false);
            quickFixButton.setText("Fixing...");
            fixingStatusLabel.setVisible(true);
            retestButton.setEnabled(false);
        } else if (issue.isFixed()) {
            quickFixButton.setEnabled(false);
            quickFixButton.setText("Fixed");
            fixingStatusLabel.setVisible(false);
            retestButton.setEnabled(true);
            retestButton.setText("Re-test");
        } else {
            quickFixButton.setEnabled(true);
            quickFixButton.setText("Quick Fix");
            quickFixButton.setIcon(AllIcons.Actions.QuickfixBulb);
            fixingStatusLabel.setVisible(false);
            retestButton.setEnabled(true);
            retestButton.setText("Re-test");
        }

        buttonPanel.setVisible(true);
        contentPanel.setVisible(true);
        emptyLabel.setVisible(false);

        revalidate();
        repaint();
    }

    public void showEmpty() {
        currentIssue = null;
        contentPanel.setVisible(false);
        emptyLabel.setVisible(true);

        removeAll();
        add(emptyLabel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void showContent() {
        removeAll();
        JBScrollPane scrollPane = new JBScrollPane(contentPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getViewport().addChangeListener(e -> {
            int viewportWidth = scrollPane.getViewport().getWidth();
            if (viewportWidth > 0) {
                contentPanel.setPreferredSize(new Dimension(viewportWidth, contentPanel.getPreferredSize().height));
                contentPanel.revalidate();
            }
        });
        add(scrollPane, BorderLayout.CENTER);
        contentPanel.setVisible(true);
        emptyLabel.setVisible(false);
        revalidate();
        repaint();
    }

    // ── Navigation ──────────────────────────────────────────────────

    public void navigateToIssue() {
        if (currentIssue == null) return;

        VirtualFile vf = findFile(currentIssue.file());
        if (vf == null) {
            Messages.showWarningDialog(project, "File not found: " + currentIssue.file(), "Navigation Error");
            return;
        }

        int line = Math.max(0, currentIssue.line() - 1);
        new OpenFileDescriptor(project, vf, line, 0).navigate(true);
    }

    @Nullable
    private VirtualFile findFile(@NotNull String fileName) {
        // VFS lookup can be slow — wrap to avoid SlowOperations assertion on EDT
        return SlowOperations.allowSlowOperations(() -> {
            // Try absolute path first
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fileName);
            if (vf != null) return vf;

            // Try relative to project
            String basePath = project.getBasePath();
            if (basePath != null) {
                vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + fileName);
                if (vf != null) return vf;
            }

            // Try currently open files
            for (VirtualFile openFile : FileEditorManager.getInstance(project).getOpenFiles()) {
                if (openFile.getName().equals(fileName)) {
                    return openFile;
                }
            }

            return null;
        });
    }

    // ── Fix Actions ─────────────────────────────────────────────────

    private void fixInTerminal() {
        if (currentIssue == null) return;

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Assist");
        if (toolWindow == null) return;

        // Find terminal panel
        Content terminalContent = toolWindow.getContentManager().findContent("Terminal");
        if (terminalContent == null) return;

        Component component = terminalContent.getComponent();
        if (!(component instanceof ClaudeTerminalPanel terminalPanel)) return;

        if (!terminalPanel.isTerminalAlive()) {
            int result = Messages.showYesNoDialog(
                    project,
                    "Terminal session is not active. Start a new session?",
                    "Claude Assist",
                    Messages.getQuestionIcon()
            );
            if (result != Messages.YES) return;
            // User needs to manually restart — switch to terminal tab
            toolWindow.getContentManager().setSelectedContent(terminalContent);
            return;
        }

        // Build fix prompt
        String prompt = buildFixPrompt();

        // Send to terminal and switch tab
        terminalPanel.sendToTerminal(prompt);
        toolWindow.getContentManager().setSelectedContent(terminalContent);
    }

    private void quickFix() {
        if (currentIssue == null) return;
        ReviewIssue.Issue issue = currentIssue;

        // Guard: prevent double-click or fix on already-fixed issue
        if (issue.isFixing() || issue.isFixed()) return;

        VirtualFile vf = findFile(issue.file());
        if (vf == null) {
            Messages.showWarningDialog(project, "File not found: " + issue.file(), "Quick Fix Error");
            return;
        }

        // Read current file content
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return;

        String originalContent = document.getText();
        String fixPrompt = buildQuickFixPrompt(originalContent);
        String workDir = ContextManager.getInstance(project).getWorkDir();

        // Immediate UI feedback: set fixing state
        issue.setFixing(true);
        quickFixButton.setEnabled(false);
        quickFixButton.setText("Fixing...");
        fixingStatusLabel.setVisible(true);
        retestButton.setEnabled(false);
        CodeReviewService.getInstance(project).notifyIssueStateChanged();

        boolean showDiff = ClaudeAssistSettings.getInstance().isShowDiffPreview();

        // For streaming diff: create a mutable document and open diff immediately
        final Document[] modifiedDocHolder = {null};
        final StringBuilder pending = new StringBuilder();
        final Timer[] flushTimerHolder = {null};

        if (showDiff) {
            // Create a mutable document for the right side of the diff
            modifiedDocHolder[0] = EditorFactory.getInstance().createDocument("");

            DiffContentFactory dcf = DiffContentFactory.getInstance();
            DiffContent left = dcf.create(project, originalContent);
            DocumentContent right = dcf.create(project, modifiedDocHolder[0]);

            SimpleDiffRequest request = new SimpleDiffRequest(
                    "Claude Assist — Quick Fix: " + vf.getName(),
                    left, right,
                    "Original", "Claude's Fix (streaming...)"
            );

            DiffManager.getInstance().showDiff(project, request);

            // Periodic flush timer (80ms) to batch-update the diff document
            final Document modifiedDoc = modifiedDocHolder[0];
            Timer flushTimer = new Timer(80, e -> {
                String chunk;
                synchronized (pending) {
                    if (pending.isEmpty()) return;
                    chunk = pending.toString();
                    pending.setLength(0);
                }
                ApplicationManager.getApplication().invokeLater(() ->
                        WriteCommandAction.runWriteCommandAction(project, () ->
                                modifiedDoc.insertString(modifiedDoc.getTextLength(), chunk)
                        )
                );
            });
            flushTimer.setRepeats(true);
            flushTimer.start();
            flushTimerHolder[0] = flushTimer;
        }

        // Stream Claude's response with onTextDelta callback
        CompletableFuture<StreamJsonService.StreamResult> future =
                StreamJsonService.getInstance().sendStreamJson(fixPrompt, workDir, null, delta -> {
                    if (showDiff && delta != null) {
                        synchronized (pending) {
                            pending.append(delta);
                        }
                    }
                });

        currentFixFuture = future;

        future.thenAccept(result -> ApplicationManager.getApplication().invokeLater(() -> {
            // Stop flush timer
            if (flushTimerHolder[0] != null) {
                flushTimerHolder[0].stop();
            }

            // Flush any remaining pending content
            if (showDiff && modifiedDocHolder[0] != null) {
                String remaining;
                synchronized (pending) {
                    remaining = pending.toString();
                    pending.setLength(0);
                }
                if (!remaining.isEmpty()) {
                    Document modifiedDoc = modifiedDocHolder[0];
                    WriteCommandAction.runWriteCommandAction(project, () ->
                            modifiedDoc.insertString(modifiedDoc.getTextLength(), remaining)
                    );
                }
            }

            issue.setFixing(false);

            if (result.aborted() || result.fullText().isEmpty()) {
                // Abort or empty response: reset to normal
                resetFixUI(issue);
                CodeReviewService.getInstance(project).notifyIssueStateChanged();
                return;
            }

            String fixedContent = cleanCodeResponse(result.fullText());

            if (showDiff) {
                // Update the streaming document with the final clean content
                Document modifiedDoc = modifiedDocHolder[0];
                WriteCommandAction.runWriteCommandAction(project, () ->
                        modifiedDoc.setText(fixedContent)
                );

                int choice = Messages.showYesNoDialog(
                        project,
                        "Apply Claude's fix?",
                        "Claude Assist — Quick Fix",
                        "Apply",
                        "Discard",
                        Messages.getQuestionIcon()
                );

                if (choice == Messages.YES) {
                    applyFix(vf, fixedContent);
                    issue.setFixed(true);
                }
            } else {
                // No diff preview: apply directly
                applyFix(vf, fixedContent);
                issue.setFixed(true);
            }

            resetFixUI(issue);
            CodeReviewService.getInstance(project).notifyIssueStateChanged();
        })).exceptionally(ex -> {
            LOG.warn("Quick Fix failed", ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (flushTimerHolder[0] != null) {
                    flushTimerHolder[0].stop();
                }
                issue.setFixing(false);
                resetFixUI(issue);
                CodeReviewService.getInstance(project).notifyIssueStateChanged();
                Messages.showWarningDialog(project,
                        "Quick Fix failed: " + ex.getMessage(), "Quick Fix Error");
            });
            return null;
        });
    }

    // ── Re-test ────────────────────────────────────────────────────

    private void retestIssue() {
        if (currentIssue == null) return;
        ReviewIssue.Issue issue = currentIssue;

        // Read the file content
        VirtualFile vf = findFile(issue.file());
        if (vf == null) {
            Messages.showWarningDialog(project, "File not found: " + issue.file(), "Re-test Error");
            return;
        }

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return;

        String fileContent = document.getText();

        // Disable button while re-testing
        retestButton.setEnabled(false);
        retestButton.setText("Re-testing...");

        // Run on pooled thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                boolean stillPresent = runRetestClaude(issue, fileContent);

                ApplicationManager.getApplication().invokeLater(() -> {
                    boolean changed = issue.isFixed() != !stillPresent;
                    issue.setFixed(!stillPresent);
                    if (changed) {
                        CodeReviewService.getInstance(project).notifyIssueStateChanged();
                    }

                    // Refresh display if this issue is still selected
                    if (currentIssue == issue) {
                        showIssue(issue);
                    }

                    retestButton.setEnabled(true);
                    retestButton.setText("Re-test");
                });
            } catch (Exception e) {
                LOG.warn("Re-test failed", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    retestButton.setEnabled(true);
                    retestButton.setText("Re-test");
                    Messages.showWarningDialog(project,
                            "Re-test failed: " + e.getMessage(), "Re-test Error");
                });
            }
        });
    }

    /**
     * Calls Claude with a targeted prompt to check if the issue is still present.
     * Returns true if the issue is still present, false if fixed.
     */
    private boolean runRetestClaude(@NotNull ReviewIssue.Issue issue, @NotNull String fileContent) throws Exception {
        ClaudeAssistSettings.State settings = getSettings();

        String prompt = "Check if this code review issue is still present in the file.\n\n"
                + "Issue: " + issue.title() + "\n"
                + "Description: " + issue.description() + "\n"
                + "File: " + issue.file() + "\n"
                + "Line: " + issue.line() + "\n"
                + (issue.suggestion() != null ? "Suggestion: " + issue.suggestion() + "\n" : "")
                + "\nCurrent file content:\n" + truncateForRetest(fileContent) + "\n\n"
                + "Respond ONLY with a JSON object (no other text):\n"
                + "{\"still_present\": true/false, \"explanation\": \"brief reason\"}\n";

        Path tempFile = Files.createTempFile("claude-retest-", ".txt");
        try {
            Files.writeString(tempFile, prompt, StandardCharsets.UTF_8);

            String workDir = ContextManager.getInstance(project).getWorkDir();
            List<String> command = buildRetestCommand(settings, tempFile, workDir);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().remove("CLAUDECODE");
            pb.redirectErrorStream(false);

            Process process = pb.start();

            StringBuilder fullText = new StringBuilder();
            String resultText = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"type\":\"result\"") && line.contains("\"result\":")) {
                        try {
                            var obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                            var resultEl = obj.get("result");
                            if (resultEl != null && resultEl.isJsonPrimitive()) {
                                resultText = resultEl.getAsString();
                            }
                        } catch (Exception ignored) {}
                    } else if (line.contains("\"type\":\"assistant\"") && line.contains("\"content\"")) {
                        try {
                            var obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                            if (obj.has("message") && obj.get("message").isJsonObject()) {
                                var msg = obj.getAsJsonObject("message");
                                if (msg.has("content") && msg.get("content").isJsonArray()) {
                                    for (var el : msg.getAsJsonArray("content")) {
                                        if (el.isJsonObject()) {
                                            var block = el.getAsJsonObject();
                                            var typeEl = block.get("type");
                                            var textEl = block.get("text");
                                            if (typeEl != null && "text".equals(typeEl.getAsString())
                                                    && textEl != null) {
                                                fullText.append(textEl.getAsString());
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Drain stderr
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (errReader.readLine() != null) { /* drain */ }
            }

            process.waitFor();
            String text = resultText != null ? resultText : fullText.toString().trim();

            return parseRetestResponse(text);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Parses the re-test JSON response. Returns true if issue is still present (default: true = conservative).
     */
    private boolean parseRetestResponse(@NotNull String text) {
        String cleaned = text.trim();
        // Strip markdown fences
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            if (nl > 0) cleaned = cleaned.substring(nl + 1);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }

        try {
            var obj = com.google.gson.JsonParser.parseString(cleaned).getAsJsonObject();
            var sp = obj.get("still_present");
            if (sp != null && sp.isJsonPrimitive()) {
                return sp.getAsBoolean();
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse re-test response: " + text, e);
        }

        // Also try to find JSON in the text
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                var obj = com.google.gson.JsonParser.parseString(cleaned.substring(start, end + 1)).getAsJsonObject();
                var sp = obj.get("still_present");
                if (sp != null && sp.isJsonPrimitive()) {
                    return sp.getAsBoolean();
                }
            } catch (Exception ignored) {}
        }

        // Conservative default: still present
        return true;
    }

    @NotNull
    private List<String> buildRetestCommand(
            @NotNull ClaudeAssistSettings.State settings,
            @NotNull Path promptTempFile,
            @Nullable String workDir
    ) {
        List<String> command = new ArrayList<>();
        StringBuilder claudeArgs = new StringBuilder("--print --verbose --output-format stream-json");
        if (!settings.model.isEmpty()) {
            claudeArgs.append(" --model '").append(settings.model).append("'");
        }

        if (settings.useWsl) {
            command.add("wsl.exe");
            if (!settings.wslDistro.isEmpty()) {
                command.add("-d");
                command.add(settings.wslDistro);
            }
            command.add("--");
            command.add("bash");
            command.add("-lc");
            String wslTemp = ClaudeAssistService.toWslPath(promptTempFile.toString());
            String wslWork = workDir != null ? ClaudeAssistService.toWslPath(workDir) : null;
            String cdPart = wslWork != null ? "cd '" + wslWork + "' && " : "";
            command.add(cdPart + "cat '" + wslTemp + "' | " + settings.claudePath + " " + claudeArgs);
        } else {
            command.add("bash");
            command.add("-lc");
            String cdPart = workDir != null ? "cd '" + workDir + "' && " : "";
            command.add(cdPart + "cat '" + promptTempFile + "' | " + settings.claudePath + " " + claudeArgs);
        }

        return command;
    }

    @NotNull
    private static String truncateForRetest(@NotNull String content) {
        if (content.length() <= 50_000) return content;
        return content.substring(0, 50_000) + "\n... [truncated] ...";
    }

    // ── Fix UI Reset ──────────────────────────────────────────────

    private void resetFixUI(@NotNull ReviewIssue.Issue issue) {
        // Only update UI if this issue is still selected
        if (currentIssue != issue) return;

        if (issue.isFixed()) {
            quickFixButton.setEnabled(false);
            quickFixButton.setText("Fixed");
            fixedBadge.setVisible(true);
        } else {
            quickFixButton.setEnabled(true);
            quickFixButton.setText("Quick Fix");
            quickFixButton.setIcon(AllIcons.Actions.QuickfixBulb);
        }
        fixingStatusLabel.setVisible(false);
        retestButton.setEnabled(true);
        retestButton.setText("Re-test");
    }

    // ── Diff / Apply ───────────────────────────────────────────────

    private void showDiffAndApply(
            @NotNull VirtualFile file,
            @NotNull String original,
            @NotNull String modified
    ) {
        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, modified);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "Claude Assist — Quick Fix: " + file.getName(),
                left, right,
                "Original", "Fixed"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(
                project,
                "Apply Claude's fix?",
                "Claude Assist — Quick Fix",
                "Apply",
                "Discard",
                Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            applyFix(file, modified);
        }
    }

    private void applyFix(@NotNull VirtualFile file, @NotNull String fixedContent) {
        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) return;

        WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Quick Fix", null, () -> {
            document.setText(fixedContent);
        });
    }

    private void copyToClipboard() {
        if (currentIssue == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append(currentIssue.severity().getDisplayName()).append(" | ").append(currentIssue.category().getDisplayName()).append('\n');
        sb.append(currentIssue.file()).append(':').append(currentIssue.line()).append('\n');
        sb.append(currentIssue.title()).append('\n');
        sb.append('\n').append(currentIssue.description()).append('\n');
        if (currentIssue.suggestion() != null && !currentIssue.suggestion().isBlank()) {
            sb.append("\nSuggestion:\n").append(currentIssue.suggestion()).append('\n');
        }

        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sb.toString()), null);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @NotNull
    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @NotNull
    private String buildFixPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Fix the following issue in ").append(currentIssue.file());
        sb.append(" at line ").append(currentIssue.line()).append(":\n\n");
        sb.append("Issue: ").append(currentIssue.title()).append('\n');
        sb.append("Description: ").append(currentIssue.description()).append('\n');
        if (currentIssue.suggestion() != null) {
            sb.append("Suggestion: ").append(currentIssue.suggestion()).append('\n');
        }
        return sb.toString();
    }

    @NotNull
    private String buildQuickFixPrompt(@NotNull String fileContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fix the following issue and return the ENTIRE fixed file content.\n");
        sb.append("Return ONLY the code, no explanations or markdown fences.\n\n");
        sb.append("Issue: ").append(currentIssue.title()).append('\n');
        sb.append("Description: ").append(currentIssue.description()).append('\n');
        sb.append("File: ").append(currentIssue.file()).append('\n');
        sb.append("Line: ").append(currentIssue.line()).append('\n');
        if (currentIssue.suggestion() != null) {
            sb.append("Suggestion: ").append(currentIssue.suggestion()).append('\n');
        }
        sb.append("\nFile content:\n").append(fileContent).append('\n');
        return sb.toString();
    }

    @NotNull
    private String cleanCodeResponse(@NotNull String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private ClaudeAssistSettings.State getSettings() {
        ClaudeAssistSettings.State s = ClaudeAssistSettings.getInstance().getState();
        return s != null ? s : new ClaudeAssistSettings.State();
    }
}
