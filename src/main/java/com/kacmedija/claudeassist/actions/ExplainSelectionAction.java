package com.kacmedija.claudeassist.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public class ExplainSelectionAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        String selection = getSelection(e);
        if (project == null || selection == null || selection.isEmpty()) {
            Messages.showInfoMessage("Select some code first.", "Claude Assist");
            return;
        }

        String language = getLanguage(e);
        String prompt = "Explain the following " + language + " code in detail:\n\n```" + language.toLowerCase() + "\n" + selection + "\n```";

        ClaudeResponseDialog dialog = new ClaudeResponseDialog(project, "Claude Assist — Explain Selection");
        dialog.setLoading();
        dialog.show();

        runClaudeTask(project, "Claude: Explain Selection", prompt, dialog::appendText, response -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (response.aborted() || response.fullText().isEmpty()) {
                    dialog.setError("No response received.");
                    return;
                }
                dialog.setComplete(response.fullText());
            });
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = getEditor(e);
        e.getPresentation().setEnabledAndVisible(
            editor != null && editor.getSelectionModel().hasSelection()
        );
    }
}
