package com.kacmedija.claudeassist.services;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Runs after project open to check Claude CLI availability.
 */
public class ClaudeAssistStartupActivity implements StartupActivity.DumbAware {

    @Override
    public void runActivity(@NotNull Project project) {
        ClaudeAssistService.getInstance().checkHealth().thenAccept(status -> {
            if (!status.available()) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Claude Assist Notifications")
                    .createNotification(
                        "Claude CLI not found",
                        "Install it with: npm install -g @anthropic-ai/claude-code" +
                            (status.error() != null ? "\nError: " + status.error() : ""),
                        NotificationType.WARNING
                    )
                    .notify(project);
            }
        });
    }
}
