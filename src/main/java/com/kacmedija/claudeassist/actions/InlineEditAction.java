package com.kacmedija.claudeassist.actions;

import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.kacmedija.claudeassist.services.StreamJsonService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Inline Edit: Select code -> describe what to change -> Claude generates new code ->
 * diff preview -> apply or discard.
 *
 * Keybinding: Ctrl+Shift+E
 */
public class InlineEditAction extends BaseClaudeAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = getProject(e);
        Editor editor = getEditor(e);
        String selection = getSelection(e);

        if (project == null || editor == null || selection == null || selection.isEmpty()) {
            Messages.showInfoMessage("Select some code first, then use Inline Edit.", "Claude Assist");
            return;
        }

        // Show instruction dialog
        InlineEditDialog dialog = new InlineEditDialog(project);
        if (!dialog.showAndGet()) return;

        String instruction = dialog.getInstruction();
        if (instruction.isEmpty()) return;

        String language = getLanguage(e);
        String fileName = getFileName(e);

        String prompt = String.format("""
            Edit the following %s code from file "%s" according to this instruction:

            Instruction: %s

            Original code:
            ```%s
            %s
            ```

            Return ONLY the modified code, no explanations or markdown fences. \
            The code should be a direct drop-in replacement for the selected code.
            """, language, fileName, instruction, language.toLowerCase(), selection);

        if (ClaudeAssistSettings.getInstance().isShowDiffPreview()) {
            StreamingDiffHandler diff = StreamingDiffHandler.showForSelection(project, editor, selection, fileName);
            runClaudeTask(project, "Claude: Inline Edit", prompt, diff::appendText, diff::complete);
        } else {
            runClaudeTask(project, "Claude: Inline Edit", prompt, result -> {
                if (result.aborted() || result.fullText().isEmpty()) return;
                String newCode = cleanCodeResponse(result.fullText());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || editor.isDisposed()) return;
                    applyEdit(project, editor, newCode);
                });
            });
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = getEditor(e);
        e.getPresentation().setEnabledAndVisible(
            editor != null && editor.getSelectionModel().hasSelection()
        );
    }

    private void applyEdit(Project project, Editor editor, String newCode) {
        WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Inline Edit", null, () -> {
            int start = editor.getSelectionModel().getSelectionStart();
            int end = editor.getSelectionModel().getSelectionEnd();
            editor.getDocument().replaceString(start, end, newCode);
        });
    }

    // ── Dialog ────────────────────────────────────────────────────

    static class InlineEditDialog extends DialogWrapper {
        private JBTextField instructionField;

        InlineEditDialog(@Nullable Project project) {
            super(project, false);
            setTitle("Claude Assist — Inline Edit");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            instructionField = new JBTextField(40);
            instructionField.setToolTipText("Describe what to change in the selected code");
            instructionField.getEmptyText().setText("e.g., Add null checks, Convert to async/await...");

            return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("What should Claude change?"), instructionField)
                .getPanel();
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return instructionField;
        }

        public String getInstruction() {
            return instructionField.getText().trim();
        }
    }
}
