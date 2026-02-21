package com.kacmedija.claudeassist.services;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.lang.Language;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service that manages the set of files explicitly added to Claude's context,
 * and builds rich context strings for prompts.
 */
@Service(Service.Level.PROJECT)
public final class ContextManager {

    private final Project project;
    private final List<VirtualFile> contextFiles = new CopyOnWriteArrayList<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public ContextManager(@NotNull Project project) {
        this.project = project;
    }

    public static ContextManager getInstance(@NotNull Project project) {
        return project.getService(ContextManager.class);
    }

    // ── Context File Management ───────────────────────────────────

    public void addFile(@NotNull VirtualFile file) {
        if (!contextFiles.contains(file)) {
            ClaudeAssistSettings settings = ClaudeAssistSettings.getInstance();
            if (contextFiles.size() >= settings.getMaxContextFiles()) {
                contextFiles.remove(0); // FIFO eviction
            }
            contextFiles.add(file);
            notifyChanged();
        }
    }

    public void removeFile(@NotNull VirtualFile file) {
        if (contextFiles.remove(file)) {
            notifyChanged();
        }
    }

    public void clearFiles() {
        contextFiles.clear();
        notifyChanged();
    }

    public List<VirtualFile> getContextFiles() {
        return Collections.unmodifiableList(contextFiles);
    }

    public boolean hasFile(@NotNull VirtualFile file) {
        return contextFiles.contains(file);
    }

    // ── Change Listeners ──────────────────────────────────────────

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    // ── Context Building ──────────────────────────────────────────

    /**
     * Builds a context block containing all tracked files, formatted for Claude.
     */
    public String buildContextBlock() {
        StringBuilder sb = new StringBuilder();

        // Add explicit context files
        for (VirtualFile file : contextFiles) {
            appendFileContext(sb, file);
        }

        // Optionally add open editor tabs
        if (ClaudeAssistSettings.getInstance().isIncludeOpenTabs()) {
            VirtualFile[] openFiles = FileEditorManager.getInstance(project).getOpenFiles();
            for (VirtualFile file : openFiles) {
                if (!contextFiles.contains(file)) {
                    appendFileContext(sb, file);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Builds a prompt that includes context about the current editor state.
     */
    public String buildEditorContext(@NotNull Editor editor, @Nullable String selection) {
        StringBuilder sb = new StringBuilder();

        VirtualFile file = FileEditorManager.getInstance(project).getSelectedFiles().length > 0
            ? FileEditorManager.getInstance(project).getSelectedFiles()[0]
            : null;

        if (file != null) {
            sb.append("Current file: ").append(file.getPath()).append('\n');
            sb.append("Language: ").append(file.getFileType().getName()).append('\n');

            int line = editor.getCaretModel().getLogicalPosition().line + 1;
            int col = editor.getCaretModel().getLogicalPosition().column + 1;
            sb.append("Cursor position: line ").append(line).append(", column ").append(col).append('\n');
        }

        if (selection != null && !selection.isEmpty()) {
            int selStart = editor.getSelectionModel().getSelectionStart();
            int selEnd = editor.getSelectionModel().getSelectionEnd();
            int startLine = editor.getDocument().getLineNumber(selStart) + 1;
            int endLine = editor.getDocument().getLineNumber(selEnd) + 1;

            sb.append("\nSelected code (lines ").append(startLine).append("-").append(endLine).append("):\n");
            sb.append("```\n").append(selection).append("\n```\n");
        }

        // Add full file content for smaller files
        if (file != null) {
            String content = editor.getDocument().getText();
            if (content.length() < 50_000) {
                sb.append("\nFull file content:\n");
                sb.append("```").append(getLanguageId(file)).append('\n');
                sb.append(content).append('\n');
                sb.append("```\n");
            }
        }

        return sb.toString();
    }

    /**
     * Gathers diagnostics (errors/warnings) for the given file from the IDE's code analysis.
     * Collects WARNING-level and above diagnostics from the document markup model.
     */
    public String gatherDiagnostics(@NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) return "";

        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) return "";

        MarkupModel markupModel;
        try {
            markupModel = DocumentMarkupModel.forDocument(document, project, false);
        } catch (Exception e) {
            return "";
        }
        if (markupModel == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Diagnostics for: ").append(file.getName()).append('\n');

        int count = 0;
        for (RangeHighlighter highlighter : markupModel.getAllHighlighters()) {
            Object tooltip = highlighter.getErrorStripeTooltip();
            if (!(tooltip instanceof HighlightInfo info)) continue;

            // Only WARNING and above
            if (info.getSeverity().compareTo(HighlightSeverity.WARNING) < 0) continue;

            int line = document.getLineNumber(highlighter.getStartOffset()) + 1;
            String severity = info.getSeverity().getName().toUpperCase();
            String description = info.getDescription();
            if (description == null || description.isBlank()) continue;

            // Extract nearby text for context
            int startOffset = highlighter.getStartOffset();
            int endOffset = Math.min(highlighter.getEndOffset(), document.getTextLength());
            String nearText = "";
            if (startOffset < endOffset && (endOffset - startOffset) < 200) {
                nearText = document.getText(new com.intellij.openapi.util.TextRange(startOffset, endOffset));
            }

            sb.append("Line ").append(line).append(" [").append(severity).append("]: ").append(description);
            if (!nearText.isEmpty()) {
                sb.append(" (near: \"").append(nearText.replace("\n", "\\n")).append("\")");
            }
            sb.append('\n');
            count++;
        }

        if (count == 0) {
            return "";
        }

        return sb.toString();
    }

    /**
     * Gets the project's working directory path.
     */
    public @Nullable String getWorkDir() {
        String basePath = project.getBasePath();
        return basePath;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void appendFileContext(StringBuilder sb, VirtualFile file) {
        try {
            byte[] bytes = file.contentsToByteArray();
            if (bytes.length > 100_000) {
                sb.append("\n--- ").append(file.getPath())
                  .append(" (truncated, ").append(bytes.length).append(" bytes) ---\n");
                sb.append(new String(bytes, 0, 50_000, StandardCharsets.UTF_8));
                sb.append("\n... [truncated] ...\n");
            } else {
                String content = new String(bytes, StandardCharsets.UTF_8);
                sb.append("\n--- ").append(file.getPath()).append(" ---\n");
                sb.append("```").append(getLanguageId(file)).append('\n');
                sb.append(content).append('\n');
                sb.append("```\n");
            }
        } catch (IOException e) {
            sb.append("\n--- ").append(file.getPath()).append(" (unreadable) ---\n");
        }
    }

    /**
     * Returns the markdown code fence language ID for a file.
     * Uses JetBrains PsiFile/LanguageFileType API first, with extension fallback.
     */
    String getLanguageId(VirtualFile file) {
        // Try PsiFile.getLanguage() first
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile != null) {
            String langId = mapLanguageIdToFenceId(psiFile.getLanguage().getID());
            if (langId != null) return langId;
        }

        // Try FileType if it's a LanguageFileType
        if (file.getFileType() instanceof LanguageFileType langFileType) {
            String langId = mapLanguageIdToFenceId(langFileType.getLanguage().getID());
            if (langId != null) return langId;
        }

        // Final fallback: extension
        String ext = file.getExtension();
        return ext != null ? ext.toLowerCase() : "";
    }

    /**
     * Maps JetBrains internal language IDs to markdown code fence IDs.
     */
    @Nullable
    static String mapLanguageIdToFenceId(@Nullable String jetbrainsId) {
        if (jetbrainsId == null) return null;
        return switch (jetbrainsId) {
            case "JAVA" -> "java";
            case "kotlin" -> "kotlin";
            case "PHP" -> "php";
            case "JavaScript", "ECMAScript 6" -> "javascript";
            case "TypeScript" -> "typescript";
            case "JSX Harmony" -> "jsx";
            case "TypeScript JSX" -> "tsx";
            case "Python" -> "python";
            case "Ruby" -> "ruby";
            case "Go" -> "go";
            case "Rust" -> "rust";
            case "C#" -> "csharp";
            case "CSS" -> "css";
            case "SCSS" -> "scss";
            case "LESS" -> "less";
            case "HTML" -> "html";
            case "XML" -> "xml";
            case "JSON" -> "json";
            case "yaml" -> "yaml";
            case "SQL" -> "sql";
            case "Shell Script" -> "bash";
            case "Markdown" -> "markdown";
            case "Dart" -> "dart";
            case "Swift" -> "swift";
            case "ObjectiveC" -> "objc";
            default -> jetbrainsId.toLowerCase();
        };
    }
}
