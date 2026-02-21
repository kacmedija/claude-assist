package com.kacmedija.claudeassist.actions;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Opens a diff window immediately and streams Claude's response into the right side
 * as chunks arrive. When complete, offers Apply/Discard.
 */
public class StreamingDiffHandler {

    private final Project project;
    private final Editor editor;
    private final String originalText;
    private final boolean fullFile;
    private final Document modifiedDoc;
    private final StringBuilder pending = new StringBuilder();
    private final Timer flushTimer;
    private volatile boolean dirty = false;
    private volatile boolean completed = false;

    private StreamingDiffHandler(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull String originalText,
            boolean fullFile,
            @NotNull String fileName
    ) {
        this.project = project;
        this.editor = editor;
        this.originalText = originalText;
        this.fullFile = fullFile;

        // Create a standalone Document for the diff right side
        this.modifiedDoc = EditorFactory.getInstance().createDocument("");

        // Periodic flush timer (80ms, runs on EDT)
        this.flushTimer = new Timer(80, e -> {
            if (dirty) {
                flush();
            }
        });

        // Open diff window on EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;

            DiffContentFactory dcf = DiffContentFactory.getInstance();
            DocumentContent left = dcf.create(project, originalText);
            DocumentContent right = dcf.create(project, modifiedDoc);

            SimpleDiffRequest request = new SimpleDiffRequest(
                    "Claude Assist — Edit: " + fileName,
                    left, right,
                    "Original", "Claude's Edit (streaming...)"
            );

            DiffManager.getInstance().showDiff(project, request);
            flushTimer.start();
        });
    }

    /**
     * Factory for selection-based actions (Refactor, Inline Edit).
     */
    public static StreamingDiffHandler showForSelection(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull String selection,
            @NotNull String fileName
    ) {
        return new StreamingDiffHandler(project, editor, selection, false, fileName);
    }

    /**
     * Factory for full-file actions (Generate Docs, Fix Diagnostics).
     */
    public static StreamingDiffHandler showForFullFile(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull String originalContent,
            @NotNull String fileName
    ) {
        return new StreamingDiffHandler(project, editor, originalContent, true, fileName);
    }

    /**
     * Append a text chunk during streaming. Thread-safe — can be called from any thread.
     */
    public void appendText(@NotNull String chunk) {
        if (completed) return;
        synchronized (pending) {
            pending.append(chunk);
            dirty = true;
        }
    }

    /**
     * Mark streaming as complete. Cleans code fences, updates the document,
     * and shows Apply/Discard dialog.
     */
    public void complete(@NotNull com.kacmedija.claudeassist.services.StreamJsonService.StreamResult result) {
        completed = true;

        ApplicationManager.getApplication().invokeLater(() -> {
            flushTimer.stop();

            if (project.isDisposed() || editor.isDisposed()) return;

            if (result.aborted() || result.fullText().isEmpty()) return;

            // Final authoritative text with code fences stripped
            String cleanedCode = BaseClaudeAction.cleanCodeResponse(result.fullText());

            // Replace the document content with the final cleaned version
            WriteCommandAction.runWriteCommandAction(project, () -> {
                modifiedDoc.setText(cleanedCode);
            });

            // Ask user to apply
            int answer = Messages.showYesNoDialog(
                    project,
                    "Apply Claude's edit to your code?",
                    "Claude Assist — Apply Edit",
                    "Apply", "Discard",
                    Messages.getQuestionIcon()
            );

            if (answer == Messages.YES) {
                applyToEditor(cleanedCode);
            }
        });
    }

    // ── Internal ──────────────────────────────────────────────────

    private void flush() {
        String text;
        synchronized (pending) {
            if (!dirty) return;
            text = pending.toString();
            dirty = false;
        }
        // Must write on EDT inside a write action
        String snapshot = text;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                modifiedDoc.setText(snapshot);
            });
        });
    }

    private void applyToEditor(@NotNull String code) {
        WriteCommandAction.runWriteCommandAction(project, "Claude Assist: Apply Edit", null, () -> {
            if (fullFile) {
                editor.getDocument().setText(code);
            } else {
                int start = editor.getSelectionModel().getSelectionStart();
                int end = editor.getSelectionModel().getSelectionEnd();
                editor.getDocument().replaceString(start, end, code);
            }
        });
    }
}
