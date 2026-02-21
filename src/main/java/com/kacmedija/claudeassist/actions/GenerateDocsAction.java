package com.kacmedija.claudeassist.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class GenerateDocsAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        Editor editor = getEditor(e);
        String content = getFileContent(e);
        if (project == null || editor == null || content == null) return;

        String language = getLanguage(e);
        String fileName = getFileName(e);
        String docFormat = getDocFormat(language);

        String prompt = "Add documentation comments to the following " + language + " file (" + fileName + "):\n\n" +
            "```" + language.toLowerCase() + "\n" + content + "\n```\n\n" +
            "Add proper " + docFormat + " to all public methods and classes.\n" +
            "Return ONLY the complete file with documentation added, no explanations or markdown fences.";

        StreamingDiffHandler diff = StreamingDiffHandler.showForFullFile(project, editor, content, fileName);
        runClaudeTask(project, "Claude: Generate Docs", prompt, diff::appendText, diff::complete);
    }

    private static String getDocFormat(String language) {
        return switch (language.toLowerCase()) {
            case "php" -> "PHPDoc";
            case "java", "kotlin" -> "Javadoc/KDoc";
            case "javascript", "typescript", "jsx", "tsx" -> "JSDoc";
            case "python" -> "docstring (Google style)";
            case "rust" -> "rustdoc";
            case "go" -> "Go doc comments";
            case "ruby" -> "YARD";
            case "c#" -> "XML doc comments";
            case "swift" -> "Swift doc comments";
            case "dart" -> "dartdoc";
            default -> "doc comments";
        };
    }
}
