package com.kacmedija.claudeassist.editor;

import com.kacmedija.claudeassist.services.ContextManager;
import com.kacmedija.claudeassist.services.StreamJsonService;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionException;

/**
 * Intention action that appears on errors/warnings, offering to fix them with Claude.
 */
public class FixWithClaudeIntention implements IntentionAction {

    private static final Logger LOG = Logger.getInstance(FixWithClaudeIntention.class);

    @Override
    public @NotNull String getText() {
        return "Fix with Claude Assist";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Claude Assist";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (editor == null || file == null) return;

        String originalContent = editor.getDocument().getText();
        String fileName = file.getName();

        ContextManager ctx = ContextManager.getInstance(project);
        String diagnostics = ctx.gatherDiagnostics(file.getVirtualFile());

        if (diagnostics.isEmpty()) {
            Messages.showInfoMessage(project, "No warnings or errors found in " + fileName + ".", "Claude Assist — Fix with Claude");
            return;
        }

        String prompt = "Fix the errors in " + fileName + ":\n\n" + diagnostics +
            "\n\nFile content:\n```\n" + originalContent + "\n```\n\nReturn ONLY the corrected code, no explanations or markdown fences.";

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Claude: Fixing...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Waiting for Claude response...");
                try {
                    StreamJsonService.getInstance()
                        .sendStreamJson(prompt, ctx.getWorkDir(), indicator)
                        .thenAccept(response -> {
                            if (response.aborted() || response.fullText().isEmpty()) return;

                            String newContent = cleanCodeResponse(response.fullText());

                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (project.isDisposed() || editor.isDisposed()) return;
                                showDiffAndApplyFullFile(project, editor, originalContent, newContent, fileName);
                            });
                        })
                        .join();
                } catch (CompletionException e) {
                    LOG.error("Claude fix failed", e);
                }
            }
        });
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    private static String cleanCodeResponse(String response) {
        String trimmed = response.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        return trimmed;
    }

    private static void showDiffAndApplyFullFile(
            @NotNull Project project, @NotNull Editor editor,
            @NotNull String original, @NotNull String modified, @NotNull String fileName
    ) {
        if (project.isDisposed() || editor.isDisposed()) return;

        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, modified);

        SimpleDiffRequest request = new SimpleDiffRequest(
            "Claude Assist — Fix: " + fileName,
            left, right,
            "Original", "Claude's Fix"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(
            project,
            "Apply Claude's fix to the file?",
            "Claude Assist — Apply Fix",
            "Apply", "Discard",
            Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Apply Fix", null, () -> {
                editor.getDocument().setText(modified);
            });
        }
    }
}
