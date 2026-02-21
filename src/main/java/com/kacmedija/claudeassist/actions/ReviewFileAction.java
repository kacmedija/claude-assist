package com.kacmedija.claudeassist.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class ReviewFileAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        String content = getFileContent(e);
        if (project == null || content == null) return;

        String language = getLanguage(e);
        String fileName = getFileName(e);

        String prompt = "Review the following " + language + " file (" + fileName + ") for:\n" +
            "- Bugs and potential issues\n- Security vulnerabilities\n- Performance concerns\n" +
            "- Code style and best practices\n\n" +
            "```" + language.toLowerCase() + "\n" + content + "\n```";

        ClaudeResponseDialog dialog = new ClaudeResponseDialog(project, "Claude Assist — Code Review: " + fileName);
        dialog.setLoading();
        dialog.show();

        runClaudeTask(project, "Claude: Code Review", prompt, dialog::appendText, response -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                if (response.aborted() || response.fullText().isEmpty()) {
                    dialog.setError("No response received.");
                } else {
                    dialog.setComplete(response.fullText());
                }
            });
        });
    }
}
