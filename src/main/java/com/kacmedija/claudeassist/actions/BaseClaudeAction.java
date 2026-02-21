package com.kacmedija.claudeassist.actions;

import com.kacmedija.claudeassist.context.ProjectContextService;
import com.kacmedija.claudeassist.context.ProjectContext;
import com.kacmedija.claudeassist.services.CodeStyleExtractor;
import com.kacmedija.claudeassist.services.ContextManager;
import com.kacmedija.claudeassist.services.StreamJsonService;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Base class for all Claude Assist actions, providing common helpers.
 */
public abstract class BaseClaudeAction extends AnAction {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(BaseClaudeAction.class);

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    protected @Nullable Project getProject(@NotNull AnActionEvent e) {
        return e.getProject();
    }

    protected @Nullable Editor getEditor(@NotNull AnActionEvent e) {
        return e.getData(CommonDataKeys.EDITOR);
    }

    protected @Nullable String getSelection(@NotNull AnActionEvent e) {
        Editor editor = getEditor(e);
        if (editor == null) return null;
        return editor.getSelectionModel().getSelectedText();
    }

    protected @NotNull String getLanguage(@NotNull AnActionEvent e) {
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (psiFile != null) {
            return psiFile.getLanguage().getDisplayName();
        }
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null && vf.getExtension() != null) {
            return vf.getExtension();
        }
        return "text";
    }

    protected @NotNull String getFileName(@NotNull AnActionEvent e) {
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        return vf != null ? vf.getName() : "unknown";
    }

    protected @Nullable String getFileContent(@NotNull AnActionEvent e) {
        Editor editor = getEditor(e);
        if (editor != null) {
            return editor.getDocument().getText();
        }
        return null;
    }

    /**
     * Runs a Claude prompt in a background task with progress indicator.
     * Uses StreamJsonService for structured stream-json output.
     * Automatically injects project context and IDE code style settings.
     */
    protected void runClaudeTask(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String prompt,
            @NotNull Consumer<StreamJsonService.StreamResult> onComplete
    ) {
        runClaudeTask(project, title, prompt, null, onComplete);
    }

    /**
     * Runs a Claude prompt with streaming text delta callbacks.
     *
     * @param onTextDelta Called for each text chunk as it arrives (from background thread)
     * @param onComplete  Called when the full response is ready
     */
    protected void runClaudeTask(
            @NotNull Project project,
            @NotNull String title,
            @NotNull String prompt,
            @Nullable Consumer<String> onTextDelta,
            @NotNull Consumer<StreamJsonService.StreamResult> onComplete
    ) {
        ContextManager ctx = ContextManager.getInstance(project);
        String contextBlock = ctx.buildContextBlock();

        StringBuilder fullPrompt = new StringBuilder();

        // Context files
        if (!contextBlock.isEmpty()) {
            fullPrompt.append("Context files:\n").append(contextBlock).append("\n\n");
        }

        // Project context (detected languages, frameworks, test frameworks)
        try {
            ProjectContext.DetectedContext projectContext =
                    ProjectContextService.getInstance(project).getContext();
            if (projectContext != null) {
                String contextPrompt = projectContext.toPromptBlock();
                if (contextPrompt != null && !contextPrompt.isBlank()) {
                    fullPrompt.append(contextPrompt).append("\n");
                }
            }
        } catch (Exception ignored) {
            // ProjectContextService might not be available yet
        }

        // IDE code style settings
        Language currentLanguage = getCurrentLanguage(project);
        if (currentLanguage != null) {
            String codeStyle = CodeStyleExtractor.extractForLanguage(project, currentLanguage);
            if (codeStyle != null) {
                fullPrompt.append(codeStyle);
                fullPrompt.append("Follow these code style rules in your output.\n\n");
            }
        }

        fullPrompt.append(prompt);

        String finalPrompt = fullPrompt.toString();
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Waiting for Claude response...");
                try {
                    StreamJsonService.getInstance()
                        .sendStreamJson(finalPrompt, ctx.getWorkDir(), indicator, onTextDelta)
                        .thenAccept(onComplete)
                        .join();
                } catch (CompletionException e) {
                    LOG.error("Claude task failed: " + title, e);
                }
            }
        });
    }

    /**
     * Resolves the Language of the currently active editor file.
     */
    @Nullable
    private Language getCurrentLanguage(@NotNull Project project) {
        VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
        if (files.length == 0) return null;
        VirtualFile file = files[0];
        if (file.getFileType() instanceof LanguageFileType langFileType) {
            return langFileType.getLanguage();
        }
        return null;
    }

    // ── Shared Utilities ─────────────────────────────────────────

    /**
     * Strips markdown code fences from Claude's response if present.
     * If the response contains explanation text around a code block,
     * extracts only the code from within the fences.
     */
    protected static String cleanCodeResponse(String response) {
        String trimmed = response.trim();

        // If response contains a fenced code block (possibly with surrounding text),
        // extract only the content inside the first code fence.
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                contentStart++; // skip the newline
                int fenceEnd = trimmed.indexOf("\n```", contentStart);
                if (fenceEnd >= 0) {
                    return trimmed.substring(contentStart, fenceEnd).trim();
                }
                // Closing fence might be at the very end without preceding newline
                if (trimmed.endsWith("```")) {
                    return trimmed.substring(contentStart, trimmed.length() - 3).trim();
                }
            }
        }

        return trimmed;
    }

    /**
     * Shows a diff preview between original selection and Claude's modified code,
     * then asks the user to apply or discard. Replaces the current selection.
     * Must be called on the EDT.
     */
    protected static void showDiffAndApply(
            @NotNull Project project, @NotNull Editor editor,
            @NotNull String original, @NotNull String modified, @NotNull String fileName
    ) {
        if (project.isDisposed() || editor.isDisposed()) return;

        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, modified);

        SimpleDiffRequest request = new SimpleDiffRequest(
            "Claude Assist — Edit: " + fileName,
            left, right,
            "Original", "Claude's Edit"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(
            project,
            "Apply Claude's edit to your code?",
            "Claude Assist — Apply Edit",
            "Apply", "Discard",
            Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Apply Edit", null, () -> {
                int start = editor.getSelectionModel().getSelectionStart();
                int end = editor.getSelectionModel().getSelectionEnd();
                editor.getDocument().replaceString(start, end, modified);
            });
        }
    }

    /**
     * Shows a diff preview between the full document and Claude's modified version,
     * then asks the user to apply or discard. Replaces the entire document.
     * Must be called on the EDT.
     */
    protected static void showDiffAndApplyFullFile(
            @NotNull Project project, @NotNull Editor editor,
            @NotNull String original, @NotNull String modified, @NotNull String fileName
    ) {
        if (project.isDisposed() || editor.isDisposed()) return;

        DiffContentFactory dcf = DiffContentFactory.getInstance();
        DiffContent left = dcf.create(project, original);
        DiffContent right = dcf.create(project, modified);

        SimpleDiffRequest request = new SimpleDiffRequest(
            "Claude Assist — Edit: " + fileName,
            left, right,
            "Original", "Claude's Edit"
        );

        DiffManager.getInstance().showDiff(project, request);

        int result = Messages.showYesNoDialog(
            project,
            "Apply Claude's changes to the entire file?",
            "Claude Assist — Apply Edit",
            "Apply", "Discard",
            Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
            WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Apply Edit", null, () -> {
                editor.getDocument().setText(modified);
            });
        }
    }
}
