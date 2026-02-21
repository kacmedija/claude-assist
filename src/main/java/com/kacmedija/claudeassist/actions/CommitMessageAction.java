package com.kacmedija.claudeassist.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.datatransfer.StringSelection;

public class CommitMessageAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        if (project == null) return;

        String prompt = "Generate a concise and descriptive git commit message for the currently staged changes. " +
            "Follow conventional commits format. Return ONLY the commit message, nothing else.";

        runClaudeTask(project, "Claude: Commit Message", prompt, response -> {
            if (response.aborted() || response.fullText().isEmpty()) return;

            String commitMessage = response.fullText().trim();

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;

                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(commitMessage), null);

                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Claude Assist Notifications")
                    .createNotification(
                        "Commit message copied to clipboard",
                        commitMessage,
                        NotificationType.INFORMATION
                    )
                    .notify(project);
            });
        });
    }
}
