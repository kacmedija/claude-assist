package com.kacmedija.claudeassist.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public class RefactorAction extends BaseClaudeAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        Editor editor = getEditor(e);
        String selection = getSelection(e);
        if (project == null || editor == null || selection == null || selection.isEmpty()) {
            Messages.showInfoMessage("Select some code to refactor.", "Claude Assist");
            return;
        }

        String instruction = Messages.showInputDialog(
            project, "Describe the refactoring:", "Claude Assist — Refactor", Messages.getQuestionIcon()
        );
        if (instruction == null || instruction.isEmpty()) return;

        String language = getLanguage(e);
        String fileName = getFileName(e);

        String prompt = "Refactor the following " + language + " code.\nInstruction: " + instruction +
            "\n\n```" + language.toLowerCase() + "\n" + selection + "\n```\n\n" +
            "IMPORTANT: Return ONLY the raw refactored code. " +
            "Do NOT include any explanations, comments about changes, markdown fences, or surrounding text. " +
            "Output nothing except the refactored code itself.";

        StreamingDiffHandler diff = StreamingDiffHandler.showForSelection(project, editor, selection, fileName);
        runClaudeTask(project, "Claude: Refactor", prompt, diff::appendText, diff::complete);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = getEditor(e);
        e.getPresentation().setEnabledAndVisible(
            editor != null && editor.getSelectionModel().hasSelection()
        );
    }
}
