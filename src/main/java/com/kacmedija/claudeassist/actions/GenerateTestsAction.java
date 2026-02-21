package com.kacmedija.claudeassist.actions;

import com.kacmedija.claudeassist.context.ProjectContext;
import com.kacmedija.claudeassist.context.ProjectContextService;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class GenerateTestsAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        String content = getFileContent(e);
        if (project == null || content == null) return;

        String language = getLanguage(e);
        String fileName = getFileName(e);
        String testInstruction = buildTestInstruction(project, language);

        String prompt = "Generate comprehensive unit tests for the following " + language + " file (" + fileName + "):\n\n" +
            "```" + language.toLowerCase() + "\n" + content + "\n```\n\n" +
            testInstruction;

        ClaudeResponseDialog dialog = new ClaudeResponseDialog(project, "Claude Assist — Generated Tests");
        dialog.setLoading();
        dialog.show();

        runClaudeTask(project, "Claude: Generate Tests", prompt, dialog::appendText, response -> {
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

    private static String buildTestInstruction(@NotNull Project project, @NotNull String language) {
        try {
            ProjectContext.DetectedContext ctx = ProjectContextService.getInstance(project).getContext();
            if (ctx != null) {
                String testFw = ctx.primaryTestFramework();
                String framework = ctx.primaryFramework();

                StringBuilder sb = new StringBuilder();
                if (testFw != null) {
                    sb.append("Use ").append(testFw).append(" as the testing framework.");
                } else {
                    sb.append("Use the standard testing framework for ").append(language).append(".");
                }
                if (framework != null) {
                    sb.append(" The project uses ").append(framework).append(". Follow its testing conventions.");
                }
                return sb.toString();
            }
        } catch (Exception ignored) {}

        return "Use the standard testing framework for " + language + ".";
    }
}
