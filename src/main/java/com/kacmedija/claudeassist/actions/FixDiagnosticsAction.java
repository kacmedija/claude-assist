package com.kacmedija.claudeassist.actions;

import com.kacmedija.claudeassist.services.ContextManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class FixDiagnosticsAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        Editor editor = getEditor(e);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null || file == null) return;

        ContextManager ctx = ContextManager.getInstance(project);
        String diagnostics = ctx.gatherDiagnostics(file);

        if (diagnostics.isEmpty()) {
            Messages.showInfoMessage(project, "No warnings or errors found in " + file.getName() + ".", "Claude Assist — Fix Diagnostics");
            return;
        }

        String fileContent = getFileContent(e);

        String prompt = "Fix the following diagnostics in " + file.getName() + ":\n\n" +
            diagnostics + "\n\nFile content:\n```\n" + (fileContent != null ? fileContent : "") + "\n```\n\n" +
            "Return ONLY the corrected code, no explanations or markdown fences.";

        String originalContent = fileContent != null ? fileContent : "";

        StreamingDiffHandler diff = StreamingDiffHandler.showForFullFile(project, editor, originalContent, file.getName());
        runClaudeTask(project, "Claude: Fix Diagnostics", prompt, diff::appendText, diff::complete);
    }
}
