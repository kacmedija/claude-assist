package com.kacmedija.claudeassist.projectsettings;

import com.kacmedija.claudeassist.agents.AgentVersion;
import com.kacmedija.claudeassist.services.ContextManager;
import com.kacmedija.claudeassist.services.StreamJsonService;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main panel for the Settings tab. Provides a CLAUDE.md editor with versioning,
 * AI enhancement, and a permissions manager.
 */
public final class SettingsPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(SettingsPanel.class);

    private final Project project;
    private final ClaudeMdEditorPanel editorPanel;
    private final JBLabel statusBar;
    private final Runnable changeListener;

    public SettingsPanel(@NotNull Project project, @NotNull Disposable parentDisposable) {
        super(new BorderLayout());
        this.project = project;
        Disposer.register(parentDisposable, this);

        // Initialize components
        editorPanel = new ClaudeMdEditorPanel(project);
        statusBar = new JBLabel("Ready");
        statusBar.setBorder(JBUI.Borders.empty(4, 8));

        // Build layout
        add(createToolbar(), BorderLayout.NORTH);
        add(editorPanel, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Wire up listeners
        editorPanel.setOnModifiedChanged(this::updateStatusBar);
        editorPanel.setOnRestoreVersion(this::restoreVersion);
        editorPanel.setOnCompareVersion(this::compareVersion);

        // Listen for external file changes
        changeListener = this::refreshFromDisk;
        ClaudeMdService.getInstance(project).addChangeListener(changeListener);

        // Initial load
        refreshFromDisk();
    }

    // ── Toolbar ─────────────────────────────────────────────────

    private JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        // Save
        group.add(new AnAction("Save", "Save CLAUDE.md to disk", AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                saveClaudeMd();
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(editorPanel.isModified());
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Save Version
        group.add(new AnAction("Save Version", "Save a named version snapshot", AllIcons.Vcs.History) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                saveVersion();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // AI Enhance
        group.add(new AnAction("AI Enhance", "Improve CLAUDE.md with AI", AllIcons.Actions.Lightning) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                aiEnhance();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        group.addSeparator();

        // Permissions
        group.add(new AnAction("Permissions", "Manage permissions (settings.local.json)", AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                openPermissions();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        group.addSeparator();

        // Refresh
        group.add(new AnAction("Refresh", "Reload from disk", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refreshFromDisk();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("SettingsToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    // ── Actions ─────────────────────────────────────────────────

    private void saveClaudeMd() {
        ClaudeMdService service = ClaudeMdService.getInstance(project);
        if (service.saveClaudeMd(editorPanel.getContent())) {
            editorPanel.markSaved();
            statusBar.setText("Saved CLAUDE.md");
        } else {
            Messages.showErrorDialog(project, "Failed to save CLAUDE.md.", "Error");
        }
    }

    private void saveVersion() {
        ClaudeMdService service = ClaudeMdService.getInstance(project);

        // If modified, save first
        if (editorPanel.isModified()) {
            service.saveClaudeMd(editorPanel.getContent());
            editorPanel.markSaved();
        }

        String label = Messages.showInputDialog(project,
                "Version label (optional):", "Save Version", null);

        String content = service.readClaudeMd();
        AgentVersion version = service.saveVersion(content, label);
        if (version != null) {
            refreshVersions();
            statusBar.setText("Version saved: " + version.getDisplayLabel());
        }
    }

    private void aiEnhance() {
        ClaudeMdService service = ClaudeMdService.getInstance(project);
        String currentContent = editorPanel.getContent();

        if (currentContent.trim().isEmpty()) {
            Messages.showWarningDialog(project,
                    "CLAUDE.md is empty. Write some content first before enhancing.",
                    "AI Enhance");
            return;
        }

        // Ask for enhancement instructions
        String instruction = Messages.showInputDialog(project,
                "Enhancement instructions:", "AI Enhance CLAUDE.md", null);
        if (instruction == null || instruction.trim().isEmpty()) return;

        // Auto-save + version before enhancement
        service.saveClaudeMd(currentContent);
        editorPanel.markSaved();
        service.saveVersion(currentContent, "Before AI enhance");

        String prompt = "You are improving a CLAUDE.md file. The CLAUDE.md file provides instructions "
                + "and context to Claude CLI (an AI coding assistant) about the project.\n\n"
                + "Current CLAUDE.md content:\n```markdown\n" + currentContent + "\n```\n\n"
                + "Improvement instructions: " + instruction + "\n\n"
                + "Return ONLY the improved CLAUDE.md content (the full markdown), "
                + "without any explanation or code fences wrapping the result.";

        statusBar.setText("AI enhancing...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Claude Assist: AI Enhance CLAUDE.md", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Waiting for Claude response...");

                String workDir = ContextManager.getInstance(project).getWorkDir();
                StreamJsonService.StreamResult result =
                        StreamJsonService.getInstance().sendStreamJson(prompt, workDir, indicator).join();

                if (result.aborted()) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            statusBar.setText("AI enhance cancelled"));
                    return;
                }

                String enhanced = result.fullText().trim();
                if (enhanced.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        statusBar.setText("AI enhance failed: empty response");
                        Messages.showErrorDialog(project,
                                "Claude returned an empty response.", "AI Enhance Failed");
                    });
                    return;
                }

                enhanced = stripCodeFences(enhanced);
                String finalEnhanced = enhanced;

                ApplicationManager.getApplication().invokeLater(() ->
                        showEnhanceDiff(currentContent, finalEnhanced));
            }
        });
    }

    private void showEnhanceDiff(@NotNull String original, @NotNull String enhanced) {
        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, enhanced);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "AI Enhance: CLAUDE.md",
                left, right,
                "Original", "Enhanced"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(project,
                "Apply AI-enhanced content to CLAUDE.md?",
                "AI Enhance \u2014 Apply Changes",
                "Apply", "Discard",
                Messages.getQuestionIcon());

        if (result == Messages.YES) {
            ClaudeMdService service = ClaudeMdService.getInstance(project);
            service.saveClaudeMd(enhanced);
            editorPanel.setContent(enhanced);
            editorPanel.markSaved();
            refreshVersions();
            statusBar.setText("AI enhance applied");
        } else {
            statusBar.setText("AI enhance discarded");
        }
    }

    private void openPermissions() {
        new PermissionsDialog(project).show();
    }

    // ── Version Actions ─────────────────────────────────────────

    private void restoreVersion(@NotNull AgentVersion version) {
        int confirm = Messages.showYesNoDialog(project,
                "Restore CLAUDE.md to version: " + version.getDisplayLabel() + "?\n"
                        + "A version of the current content will be saved automatically.",
                "Restore Version",
                Messages.getQuestionIcon());

        if (confirm != Messages.YES) return;

        ClaudeMdService service = ClaudeMdService.getInstance(project);
        if (service.restoreVersion(version)) {
            String restored = service.readClaudeMd();
            editorPanel.setContent(restored);
            editorPanel.markSaved();
            refreshVersions();
            statusBar.setText("Restored: " + version.getDisplayLabel());
        }
    }

    private void compareVersion(@NotNull AgentVersion version) {
        ClaudeMdService service = ClaudeMdService.getInstance(project);
        String versionContent = service.readVersionContent(version);
        String currentContent = editorPanel.getContent();

        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, versionContent);
        DiffContent right = dcf.create(project, currentContent);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "Compare: CLAUDE.md",
                left, right,
                "Version: " + version.getDisplayLabel(), "Current"
        );

        DiffManager.getInstance().showDiff(project, request);
    }

    // ── Refresh ─────────────────────────────────────────────────

    private void refreshFromDisk() {
        ClaudeMdService service = ClaudeMdService.getInstance(project);
        boolean exists = service.claudeMdExists();
        String content = service.readClaudeMd();
        editorPanel.loadContent(content, exists);
        refreshVersions();
        updateStatusBar();
    }

    private void refreshVersions() {
        List<AgentVersion> versions = ClaudeMdService.getInstance(project).listVersions();
        editorPanel.refreshVersions(versions);
    }

    private void updateStatusBar() {
        ClaudeMdService service = ClaudeMdService.getInstance(project);
        String status = service.claudeMdExists() ? "CLAUDE.md" : "CLAUDE.md (not created yet)";
        if (editorPanel.isModified()) {
            status += "  \u00b7  Modified";
        }
        statusBar.setText(status);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static @NotNull String stripCodeFences(@NotNull String text) {
        String trimmed = text.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                contentStart++;
                int fenceEnd = trimmed.lastIndexOf("```");
                if (fenceEnd > contentStart) {
                    return trimmed.substring(contentStart, fenceEnd).trim();
                }
            }
        }
        return trimmed;
    }

    // ── Dispose ─────────────────────────────────────────────────

    @Override
    public void dispose() {
        ClaudeMdService.getInstance(project).removeChangeListener(changeListener);
    }
}
