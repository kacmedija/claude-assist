package com.kacmedija.claudeassist.agents;

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
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main panel for the Agents tab. Assembles the toolbar, list/editor splitter,
 * and status bar into a cohesive UI for managing agent prompt files.
 */
public final class AgentsPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentsPanel.class);

    private final Project project;
    private final AgentListPanel listPanel;
    private final AgentEditorPanel editorPanel;
    private final JBLabel statusBar;
    private final Runnable changeListener;

    public AgentsPanel(@NotNull Project project, @NotNull Disposable parentDisposable) {
        super(new BorderLayout());
        this.project = project;
        Disposer.register(parentDisposable, this);

        // Initialize components
        listPanel = new AgentListPanel();
        editorPanel = new AgentEditorPanel(project);
        statusBar = new JBLabel("Ready");
        statusBar.setBorder(JBUI.Borders.empty(4, 8));

        // Build layout
        add(createToolbar(), BorderLayout.NORTH);
        add(createSplitter(), BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Wire up listeners
        listPanel.addSelectionListener(this::onAgentSelected);

        editorPanel.setOnModifiedChanged(this::updateStatusBar);
        editorPanel.setOnRestoreVersion(this::restoreVersion);
        editorPanel.setOnCompareVersion(this::compareVersion);

        // Listen for file changes
        changeListener = this::refreshAgentList;
        AgentService.getInstance(project).addChangeListener(changeListener);

        // Initial load
        refreshAgentList();
    }

    // ── Toolbar ─────────────────────────────────────────────────

    private JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        // New Agent
        group.add(new AnAction("New", "Create a new agent", AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                createNewAgent();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Save
        group.add(new AnAction("Save", "Save the current agent", AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                saveCurrentAgent();
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
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(editorPanel.getCurrentAgent() != null);
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // AI Enhance
        group.add(new AnAction("AI Enhance", "Improve agent prompt with AI", AllIcons.Actions.Lightning) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                aiEnhance();
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(editorPanel.getCurrentAgent() != null);
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        group.addSeparator();

        // Delete
        group.add(new AnAction("Delete", "Delete the selected agent", AllIcons.General.Remove) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                deleteCurrentAgent();
            }
            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(editorPanel.getCurrentAgent() != null);
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Refresh
        group.add(new AnAction("Refresh", "Reload agents from disk", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refreshAgentList();
            }
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("AgentsToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    // ── Splitter ────────────────────────────────────────────────

    private JComponent createSplitter() {
        JBSplitter splitter = new JBSplitter(false, 0.25f);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(editorPanel);
        return splitter;
    }

    // ── Actions ─────────────────────────────────────────────────

    private void createNewAgent() {
        AgentCreateDialog dialog = new AgentCreateDialog(project);
        if (!dialog.showAndGet()) return;

        String name = dialog.getAgentName();
        String content = dialog.getAgentContent();

        AgentService service = AgentService.getInstance(project);
        AgentFile existing = service.readAgent(name);
        if (existing != null) {
            Messages.showWarningDialog(project,
                    "An agent named '" + name + "' already exists.",
                    "Agent Already Exists");
            return;
        }

        AgentFile agent = service.createAgent(name, content);
        if (agent != null) {
            refreshAgentList();
            listPanel.selectAgent(name);
            statusBar.setText("Created agent: " + name);
        } else {
            Messages.showErrorDialog(project, "Failed to create agent.", "Error");
        }
    }

    private void saveCurrentAgent() {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        agent.setContent(editorPanel.getContent());
        AgentService service = AgentService.getInstance(project);
        if (service.saveAgent(agent)) {
            editorPanel.markSaved();
            statusBar.setText("Saved: " + agent.getFileName());
        } else {
            Messages.showErrorDialog(project, "Failed to save agent.", "Error");
        }
    }

    private void saveVersion() {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        // If modified, save first
        if (editorPanel.isModified()) {
            agent.setContent(editorPanel.getContent());
            AgentService.getInstance(project).saveAgent(agent);
            editorPanel.markSaved();
        }

        String label = Messages.showInputDialog(project,
                "Version label (optional):", "Save Version", null);

        AgentService service = AgentService.getInstance(project);
        AgentVersion version = service.saveVersion(agent, label);
        if (version != null) {
            refreshVersions(agent.getName());
            statusBar.setText("Version saved: " + version.getDisplayLabel());
        }
    }

    private void aiEnhance() {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        AgentEnhanceDialog dialog = new AgentEnhanceDialog(project);
        if (!dialog.showAndGet()) return;

        String instruction = dialog.getInstruction();
        String currentContent = editorPanel.getContent();

        // Auto-save version before enhancement
        agent.setContent(currentContent);
        AgentService service = AgentService.getInstance(project);
        service.saveAgent(agent);
        editorPanel.markSaved();
        service.saveVersion(agent, "Before AI enhance");

        String prompt = "You are improving an agent prompt file. The agent prompt is a markdown file "
                + "that defines an AI agent's behavior and instructions.\n\n"
                + "Current agent prompt content:\n```markdown\n" + currentContent + "\n```\n\n"
                + "Improvement instructions: " + instruction + "\n\n"
                + "Return ONLY the improved agent prompt content (the full markdown), "
                + "without any explanation or code fences wrapping the result.";

        statusBar.setText("AI enhancing...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Claude Assist: AI Enhance Agent", true) {
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

                // Strip code fences if present
                enhanced = stripCodeFences(enhanced);
                String finalEnhanced = enhanced;

                ApplicationManager.getApplication().invokeLater(() ->
                        showEnhanceDiff(agent, currentContent, finalEnhanced));
            }
        });
    }

    private void showEnhanceDiff(@NotNull AgentFile agent, @NotNull String original, @NotNull String enhanced) {
        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, enhanced);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "AI Enhance: " + agent.getFileName(),
                left, right,
                "Original", "Enhanced"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(project,
                "Apply AI-enhanced content to the agent?",
                "AI Enhance — Apply Changes",
                "Apply", "Discard",
                Messages.getQuestionIcon());

        if (result == Messages.YES) {
            agent.setContent(enhanced);
            AgentService.getInstance(project).saveAgent(agent);
            editorPanel.setContent(enhanced);
            editorPanel.markSaved();
            refreshVersions(agent.getName());
            statusBar.setText("AI enhance applied: " + agent.getFileName());
        } else {
            statusBar.setText("AI enhance discarded");
        }
    }

    private void deleteCurrentAgent() {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        int confirm = Messages.showYesNoDialog(project,
                "Delete agent '" + agent.getName() + "' and all its versions?",
                "Delete Agent",
                Messages.getWarningIcon());

        if (confirm != Messages.YES) return;

        AgentService service = AgentService.getInstance(project);
        if (service.deleteAgent(agent)) {
            editorPanel.loadAgent(null);
            refreshAgentList();
            statusBar.setText("Deleted: " + agent.getFileName());
        } else {
            Messages.showErrorDialog(project, "Failed to delete agent.", "Error");
        }
    }

    // ── Version Actions ─────────────────────────────────────────

    private void restoreVersion(@NotNull AgentVersion version) {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        int confirm = Messages.showYesNoDialog(project,
                "Restore agent to version: " + version.getDisplayLabel() + "?\n"
                        + "A version of the current content will be saved automatically.",
                "Restore Version",
                Messages.getQuestionIcon());

        if (confirm != Messages.YES) return;

        // Ensure current edits are saved to the agent before restoring
        agent.setContent(editorPanel.getContent());

        AgentService service = AgentService.getInstance(project);
        if (service.restoreVersion(agent, version)) {
            editorPanel.setContent(agent.getContent());
            editorPanel.markSaved();
            refreshVersions(agent.getName());
            statusBar.setText("Restored: " + version.getDisplayLabel());
        }
    }

    private void compareVersion(@NotNull AgentVersion version) {
        AgentFile agent = editorPanel.getCurrentAgent();
        if (agent == null) return;

        AgentService service = AgentService.getInstance(project);
        String versionContent = service.readVersionContent(version);
        String currentContent = editorPanel.getContent();

        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, versionContent);
        DiffContent right = dcf.create(project, currentContent);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "Compare: " + agent.getFileName(),
                left, right,
                "Version: " + version.getDisplayLabel(), "Current"
        );

        DiffManager.getInstance().showDiff(project, request);
    }

    // ── Selection & Refresh ─────────────────────────────────────

    private void onAgentSelected(@org.jetbrains.annotations.Nullable AgentFile agent) {
        editorPanel.loadAgent(agent);
        if (agent != null) {
            refreshVersions(agent.getName());
        }
        updateStatusBar();
    }

    private void refreshAgentList() {
        List<AgentFile> agents = AgentService.getInstance(project).listAgents();
        listPanel.setAgents(agents);
        updateStatusBar();
    }

    private void refreshVersions(@NotNull String agentName) {
        List<AgentVersion> versions = AgentService.getInstance(project).listVersions(agentName);
        editorPanel.refreshVersions(versions);
    }

    private void updateStatusBar() {
        int count = listPanel.getAgentCount();
        String status = count + " agent" + (count != 1 ? "s" : "");
        if (editorPanel.isModified()) {
            status += "  ·  Modified";
        }
        statusBar.setText(status);
    }

    // ── Helpers ──────────────────────────────────────────────────

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
        AgentService.getInstance(project).removeChangeListener(changeListener);
    }
}
