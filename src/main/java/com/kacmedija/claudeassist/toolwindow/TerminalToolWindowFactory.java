package com.kacmedija.claudeassist.toolwindow;

import com.kacmedija.claudeassist.agents.AgentsPanel;
import com.kacmedija.claudeassist.projectsettings.SettingsPanel;
import com.kacmedija.claudeassist.review.CodeReviewPanel;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the "Claude Assist" tool window with an embedded terminal
 * running the Claude CLI interactively, and a Code Review tab.
 */
public class TerminalToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Terminal tab
        ClaudeTerminalPanel terminalPanel = new ClaudeTerminalPanel(project, toolWindow);
        Content terminalContent = ContentFactory.getInstance().createContent(terminalPanel, "Terminal", false);
        terminalContent.setCloseable(false);
        toolWindow.getContentManager().addContent(terminalContent);

        // Review tab
        CodeReviewPanel reviewPanel = new CodeReviewPanel(project, toolWindow.getDisposable());
        Content reviewContent = ContentFactory.getInstance().createContent(reviewPanel, "Review", false);
        reviewContent.setCloseable(false);
        toolWindow.getContentManager().addContent(reviewContent);

        // Agents tab
        AgentsPanel agentsPanel = new AgentsPanel(project, toolWindow.getDisposable());
        Content agentsContent = ContentFactory.getInstance().createContent(agentsPanel, "Agents", false);
        agentsContent.setCloseable(false);
        toolWindow.getContentManager().addContent(agentsContent);

        // Settings tab
        SettingsPanel settingsPanel = new SettingsPanel(project, toolWindow.getDisposable());
        Content settingsContent = ContentFactory.getInstance().createContent(settingsPanel, "Settings", false);
        settingsContent.setCloseable(false);
        toolWindow.getContentManager().addContent(settingsContent);
    }
}
