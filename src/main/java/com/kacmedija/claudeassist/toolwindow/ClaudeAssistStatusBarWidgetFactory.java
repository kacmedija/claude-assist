package com.kacmedija.claudeassist.toolwindow;

import com.kacmedija.claudeassist.services.ClaudeAssistService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

/**
 * Status bar widget showing Claude Assist availability.
 */
public class ClaudeAssistStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NonNls @NotNull String getId() {
        return "ClaudeAssistStatus";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Claude Assist Status";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new ClaudeStatusWidget(project);
    }

    private static class ClaudeStatusWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
        private final Project project;
        private StatusBar statusBar;
        private String text = "Claude: ...";

        ClaudeStatusWidget(@NotNull Project project) {
            this.project = project;
            ClaudeAssistService.getInstance().checkHealth().thenAccept(status -> {
                text = status.available() ? "Claude: " + status.version() : "Claude: N/A";
                if (statusBar != null) {
                    statusBar.updateWidget(ID());
                }
            });
        }

        @Override
        public @NonNls @NotNull String ID() {
            return "ClaudeAssistStatus";
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            this.statusBar = statusBar;
        }

        @Override
        public void dispose() {}

        @Override
        public @NotNull WidgetPresentation getPresentation() {
            return this;
        }

        @Override
        public @NotNull String getText() {
            return text;
        }

        @Override
        public float getAlignment() {
            return 0;
        }

        @Override
        public @Nullable String getTooltipText() {
            return "Claude CLI Status";
        }

        @Override
        public @Nullable Consumer<MouseEvent> getClickConsumer() {
            return null;
        }
    }
}
