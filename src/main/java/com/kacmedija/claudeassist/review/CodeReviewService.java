package com.kacmedija.claudeassist.review;

import com.kacmedija.claudeassist.services.ClaudeAssistService;
import com.kacmedija.claudeassist.services.ContextManager;
import com.kacmedija.claudeassist.services.StreamJsonService;
import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Project-level service that orchestrates code reviews:
 * gathers files, builds prompts, calls Claude, parses results, and notifies listeners.
 *
 * <p>{@link #runReview} is <b>synchronous</b> — it must be called from a background thread
 * (e.g. inside a {@code Task.Backgroundable}).  File content that requires EDT access
 * (selected editor, selection text) is gathered via {@code invokeAndWait} internally.</p>
 *
 * <p>For {@code BRANCH_CHANGES} scope, files are grouped into batches and reviewed
 * in parallel by separate Claude processes, then results are merged incrementally.</p>
 */
@Service(Service.Level.PROJECT)
public final class CodeReviewService {

    private static final Logger LOG = Logger.getInstance(CodeReviewService.class);
    private static final int MAX_PARALLEL_REVIEWS = 8;
    private static final int FILES_PER_BATCH = 8;

    private final Project project;
    private final AtomicBoolean reviewing = new AtomicBoolean(false);
    private final List<Consumer<ReviewIssue.ReviewResult>> resultListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ReviewProgress>> progressListeners = new CopyOnWriteArrayList<>();
    private volatile ReviewIssue.ReviewResult lastResult;

    // Incremental state for parallel branch reviews
    private final List<ReviewIssue.Issue> accumulatedIssues = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger completedBatches = new AtomicInteger(0);
    private volatile int totalBatches;

    public CodeReviewService(@NotNull Project project) {
        this.project = project;
        loadPersistedResult();
    }

    public static CodeReviewService getInstance(@NotNull Project project) {
        return project.getService(CodeReviewService.class);
    }

    // ── Progress Record ───────────────────────────────────────────

    public record ReviewProgress(int completedBatches, int totalBatches, int issueCount) {}

    // ── Review Execution (synchronous – call from background thread) ─

    /**
     * Runs a code review with the given configuration (backward-compatible, no standards).
     * <b>Blocks the calling thread</b> until the review is complete.
     */
    public void runReview(
            @NotNull ReviewIssue.Scope scope,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @NotNull ProgressIndicator indicator
    ) {
        runReview(scope, categories, depth, customInstructions, (String) null, indicator);
    }

    /**
     * Runs a code review with the given configuration and project standards.
     * <b>Blocks the calling thread</b> until the review is complete.
     *
     * @deprecated Use {@link #runReview(ReviewIssue.Scope, Set, ReviewIssue.Depth, String, String, ProgressIndicator)} instead.
     */
    @Deprecated
    public void runReview(
            @NotNull ReviewIssue.Scope scope,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @Nullable ReviewStandards.ProjectStandards standards,
            @NotNull ProgressIndicator indicator
    ) {
        // Resolve old-style standards to instructions
        String standardsInstructions = resolveStandardsInstructions(standards);
        runReview(scope, categories, depth, customInstructions, standardsInstructions, indicator);
    }

    /**
     * Runs a code review with the given configuration and pre-built standards instructions.
     * <b>Blocks the calling thread</b> until the review is complete.
     */
    public void runReview(
            @NotNull ReviewIssue.Scope scope,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @Nullable String standardsInstructions,
            @NotNull ProgressIndicator indicator
    ) {
        if (!reviewing.compareAndSet(false, true)) {
            notify("Review already in progress", NotificationType.WARNING);
            return;
        }
        ApplicationManager.getApplication().invokeLater(this::notifyStateChanged);

        try {
            if (scope == ReviewIssue.Scope.BRANCH_CHANGES) {
                runParallelBranchReview(categories, depth, customInstructions, standardsInstructions, indicator);
            } else {
                runSingleReview(scope, categories, depth, customInstructions, standardsInstructions, indicator);
            }
        } catch (Exception e) {
            LOG.error("Code review failed", e);
            ReviewIssue.ReviewResult errorResult =
                    new ReviewIssue.ReviewResult(List.of(), true, "Error: " + e.getMessage());
            lastResult = errorResult;
            fireResult(errorResult);
        } finally {
            reviewing.set(false);
            ApplicationManager.getApplication().invokeLater(this::notifyStateChanged);
        }
    }

    /**
     * Resolves legacy project standards into prompt instruction text.
     * Uses the new ProjectContextService for context detection.
     */
    @Nullable
    private String resolveStandardsInstructions(@Nullable ReviewStandards.ProjectStandards standards) {
        if (standards == null) {
            // Use auto-detected context
            try {
                com.kacmedija.claudeassist.context.ProjectContext.DetectedContext ctx =
                        com.kacmedija.claudeassist.context.ProjectContextService.getInstance(project).getContext();
                return com.kacmedija.claudeassist.context.ContextPromptBuilder.buildReviewInstructions(ctx);
            } catch (Exception e) {
                LOG.debug("Failed to get project context", e);
                return null;
            }
        }

        // Legacy path: use old-style PHP standards resolution
        ReviewStandards.DetectedContext ctx = com.kacmedija.claudeassist.review.ProjectContextDetector.detect(project);
        ReviewStandards.ResolvedStandards resolved = standards.resolve(ctx);

        LOG.info("Resolved standards: php=" + resolved.phpVersion()
                + ", coding=" + resolved.codingStandard()
                + ", framework=" + resolved.framework());

        return resolved.promptInstructions();
    }

    /**
     * Standard review: gather all files, build one prompt, call Claude once.
     */
    private void runSingleReview(
            @NotNull ReviewIssue.Scope scope,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @Nullable String standardsInstructions,
            @NotNull ProgressIndicator indicator
    ) {
        indicator.setText("Gathering files...");
        Map<String, String> files = gatherFiles(scope);
        if (files.isEmpty()) {
            notify("No files to review", NotificationType.WARNING);
            return;
        }

        if (indicator.isCanceled()) return;

        indicator.setText("Building review prompt...");
        String prompt = ReviewPromptBuilder.build(files, categories, depth, customInstructions, standardsInstructions);
        LOG.info("Review prompt length: " + prompt.length() + " chars, files: " + files.keySet());

        if (indicator.isCanceled()) return;

        ReviewIssue.ReviewResult result = callClaudeAndParse(prompt, indicator, "Waiting for Claude response...");
        if (result != null) {
            lastResult = result;
            fireResult(result);
            ReviewPersistence.save(project, result);
        }
    }

    /**
     * Parallel branch review: find all files changed since branch diverged from base,
     * group them into batches of {@link #FILES_PER_BATCH}, and review each batch
     * in parallel with a separate Claude process. Results fire incrementally.
     */
    private void runParallelBranchReview(
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @Nullable String standardsInstructions,
            @NotNull ProgressIndicator indicator
    ) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            notify("Cannot determine project path", NotificationType.WARNING);
            return;
        }

        indicator.setText("Finding branch changes...");
        List<String> branchFiles;
        try {
            branchFiles = getBranchChangedFiles(basePath);
        } catch (Exception e) {
            LOG.warn("Failed to get branch changes", e);
            notify("Failed to determine branch changes: " + e.getMessage(), NotificationType.WARNING);
            return;
        }

        if (branchFiles.isEmpty()) {
            notify("No changed files found on this branch", NotificationType.INFORMATION);
            return;
        }

        if (indicator.isCanceled()) return;

        // Read all file contents
        indicator.setText("Reading " + branchFiles.size() + " files...");
        Map<String, String> allFiles = new LinkedHashMap<>();
        for (String fileName : branchFiles) {
            String fullPath = basePath + "/" + fileName;
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath);
            if (vf != null && vf.exists() && !vf.isDirectory()) {
                ReadAction.run(() -> readFileIntoMap(allFiles, vf, fileName));
            }
        }

        if (allFiles.isEmpty()) {
            notify("Could not read any changed files", NotificationType.WARNING);
            return;
        }

        // Split files into batches
        List<Map<String, String>> batches = createBatches(allFiles, FILES_PER_BATCH);
        totalBatches = batches.size();

        // Reset incremental state
        accumulatedIssues.clear();
        completedBatches.set(0);

        LOG.info("Branch review: " + allFiles.size() + " files in " + totalBatches
                + " batches (" + FILES_PER_BATCH + " files/batch, "
                + MAX_PARALLEL_REVIEWS + " parallel)");
        indicator.setText("Reviewing " + allFiles.size() + " files in " + totalBatches + " batches...");
        indicator.setIndeterminate(false);

        // Launch parallel reviews — one per batch
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(totalBatches, MAX_PARALLEL_REVIEWS));

        List<Future<ReviewIssue.ReviewResult>> futures = new ArrayList<>();

        for (int i = 0; i < totalBatches; i++) {
            Map<String, String> batch = batches.get(i);

            futures.add(executor.submit(() -> {
                if (indicator.isCanceled()) return null;

                String prompt = ReviewPromptBuilder.build(batch, categories, depth, customInstructions, standardsInstructions);
                ReviewIssue.ReviewResult result = callClaudeForParallel(prompt, indicator);

                if (result != null && !result.parseError()) {
                    // Add issues to accumulated list
                    accumulatedIssues.addAll(result.issues());
                }

                int done = completedBatches.incrementAndGet();
                indicator.setFraction((double) done / totalBatches);
                indicator.setText("Batch " + done + "/" + totalBatches + " done ("
                        + accumulatedIssues.size() + " issues found)...");

                // Fire incremental result — snapshot of all accumulated issues so far
                List<ReviewIssue.Issue> snapshot;
                synchronized (accumulatedIssues) {
                    snapshot = new ArrayList<>(accumulatedIssues);
                }
                snapshot.sort(Comparator.comparingInt(issue -> issue.severity().ordinal()));
                ReviewIssue.ReviewResult incrementalResult = new ReviewIssue.ReviewResult(snapshot, false, null);
                lastResult = incrementalResult;
                fireResult(incrementalResult);
                ReviewPersistence.save(project, incrementalResult);

                // Fire progress
                fireProgress(new ReviewProgress(done, totalBatches, snapshot.size()));

                return result;
            }));
        }

        executor.shutdown();

        // Wait for all to complete
        boolean anyParseError = false;
        for (Future<ReviewIssue.ReviewResult> future : futures) {
            try {
                ReviewIssue.ReviewResult result = future.get();
                if (result != null && result.parseError()) {
                    anyParseError = true;
                }
            } catch (Exception e) {
                LOG.warn("Parallel review task failed", e);
                anyParseError = true;
            }
        }

        // If ALL batches had parse errors and no issues accumulated, report error
        if (accumulatedIssues.isEmpty() && anyParseError) {
            ReviewIssue.ReviewResult errorResult = new ReviewIssue.ReviewResult(
                    List.of(), true, "All batch reviews failed to parse");
            lastResult = errorResult;
            fireResult(errorResult);
        }
        // Otherwise, the last incremental fireResult already has the final state
    }

    /**
     * Splits a map of files into batches of the given size.
     */
    @NotNull
    private static List<Map<String, String>> createBatches(
            @NotNull Map<String, String> files, int batchSize
    ) {
        List<Map<String, String>> batches = new ArrayList<>();
        Map<String, String> currentBatch = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : files.entrySet()) {
            currentBatch.put(entry.getKey(), entry.getValue());
            if (currentBatch.size() >= batchSize) {
                batches.add(currentBatch);
                currentBatch = new LinkedHashMap<>();
            }
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    // ── Claude Interaction ─────────────────────────────────────────

    /**
     * Calls Claude and parses the result. Used by single-review mode.
     * Uses the shared StreamJsonService (which manages a single process).
     */
    @Nullable
    private ReviewIssue.ReviewResult callClaudeAndParse(
            @NotNull String prompt,
            @NotNull ProgressIndicator indicator,
            @NotNull String statusText
    ) {
        indicator.setText(statusText);
        String workDir = ContextManager.getInstance(project).getWorkDir();
        StreamJsonService.StreamResult streamResult =
                StreamJsonService.getInstance().sendStreamJson(prompt, workDir, indicator).join();

        if (streamResult.aborted()) {
            LOG.info("Review aborted by user");
            return null;
        }

        LOG.info("Claude response: exitCode=" + streamResult.exitCode()
                + ", textLength=" + streamResult.fullText().length()
                + ", events=" + streamResult.events().size());

        if (streamResult.fullText().isBlank()) {
            String errorMsg = "Claude returned an empty response (exit code " + streamResult.exitCode() + ").";
            if (streamResult.exitCode() != 0) {
                errorMsg += "\nThe CLI process exited with a non-zero code – check that 'claude' is available and authenticated.";
            }
            return new ReviewIssue.ReviewResult(List.of(), true, errorMsg);
        }

        indicator.setText("Parsing review results...");
        return ReviewJsonParser.parse(streamResult.fullText());
    }

    /**
     * Calls Claude for parallel review — launches its own process directly,
     * bypassing the shared StreamJsonService (which only tracks one process).
     */
    @Nullable
    private ReviewIssue.ReviewResult callClaudeForParallel(
            @NotNull String prompt,
            @NotNull ProgressIndicator indicator
    ) {
        Path tempFile = null;
        try {
            ClaudeAssistSettings.State settings = getSettings();
            tempFile = Files.createTempFile("claude-review-", ".txt");
            Files.writeString(tempFile, prompt, StandardCharsets.UTF_8);

            String workDir = ContextManager.getInstance(project).getWorkDir();
            List<String> command = buildClaudeCommand(settings, tempFile, workDir);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().remove("CLAUDECODE");
            pb.redirectErrorStream(false);

            Process process = pb.start();

            StringBuilder fullText = new StringBuilder();
            String resultText = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (indicator.isCanceled()) {
                        process.destroy();
                        return null;
                    }
                    // Quick parse for result text
                    if (line.contains("\"type\":\"result\"") && line.contains("\"result\":")) {
                        // Extract result field from JSON
                        try {
                            var obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                            var resultEl = obj.get("result");
                            if (resultEl != null && resultEl.isJsonPrimitive()) {
                                resultText = resultEl.getAsString();
                            }
                        } catch (Exception ignored) {}
                    } else if (line.contains("\"type\":\"assistant\"") && line.contains("\"content\"")) {
                        // Extract text from assistant message
                        try {
                            var obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                            if (obj.has("message") && obj.get("message").isJsonObject()) {
                                var msg = obj.getAsJsonObject("message");
                                if (msg.has("content") && msg.get("content").isJsonArray()) {
                                    for (var el : msg.getAsJsonArray("content")) {
                                        if (el.isJsonObject()) {
                                            var block = el.getAsJsonObject();
                                            var typeEl = block.get("type");
                                            var textEl = block.get("text");
                                            if (typeEl != null && "text".equals(typeEl.getAsString())
                                                    && textEl != null) {
                                                fullText.append(textEl.getAsString());
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Drain stderr
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    LOG.debug("Claude parallel stderr: " + line);
                }
            }

            int exitCode = process.waitFor();
            String text = resultText != null ? resultText : fullText.toString().trim();

            if (text.isBlank()) {
                return new ReviewIssue.ReviewResult(List.of(), true,
                        "Empty response (exit code " + exitCode + ")");
            }

            return ReviewJsonParser.parse(text);

        } catch (Exception e) {
            LOG.warn("Parallel Claude call failed", e);
            return new ReviewIssue.ReviewResult(List.of(), true, "Error: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Builds the Claude CLI command for parallel execution.
     */
    @NotNull
    private List<String> buildClaudeCommand(
            @NotNull ClaudeAssistSettings.State settings,
            @NotNull Path promptTempFile,
            @Nullable String workDir
    ) {
        List<String> command = new ArrayList<>();
        StringBuilder claudeArgs = new StringBuilder("--print --verbose --output-format stream-json");
        if (!settings.model.isEmpty()) {
            claudeArgs.append(" --model '").append(settings.model).append("'");
        }

        if (settings.useWsl) {
            command.add("wsl.exe");
            if (!settings.wslDistro.isEmpty()) {
                command.add("-d");
                command.add(settings.wslDistro);
            }
            command.add("--");
            command.add("bash");
            command.add("-lc");
            String wslTemp = ClaudeAssistService.toWslPath(promptTempFile.toString());
            String wslWork = workDir != null ? ClaudeAssistService.toWslPath(workDir) : null;
            String cdPart = wslWork != null ? "cd '" + wslWork + "' && " : "";
            command.add(cdPart + "cat '" + wslTemp + "' | " + settings.claudePath + " " + claudeArgs);
        } else {
            command.add("bash");
            command.add("-lc");
            String cdPart = workDir != null ? "cd '" + workDir + "' && " : "";
            command.add(cdPart + "cat '" + promptTempFile + "' | " + settings.claudePath + " " + claudeArgs);
        }

        return command;
    }

    // ── File Gathering (EDT-safe) ──────────────────────────────────

    @NotNull
    private Map<String, String> gatherFiles(@NotNull ReviewIssue.Scope scope) {
        Map<String, String> files = new LinkedHashMap<>();

        switch (scope) {
            case CURRENT_FILE -> gatherCurrentFile(files);
            case SELECTION -> gatherSelection(files);
            case CHANGED_FILES -> gatherChangedFiles(files);
            case BRANCH_CHANGES -> {} // handled by runParallelBranchReview
        }

        return files;
    }

    private void gatherCurrentFile(@NotNull Map<String, String> files) {
        AtomicReference<VirtualFile> fileRef = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() ->
                ReadAction.run(() -> {
                    VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
                    if (selected.length > 0) {
                        fileRef.set(selected[0]);
                    }
                })
        );
        VirtualFile file = fileRef.get();
        if (file != null) {
            ReadAction.run(() -> readFileIntoMap(files, file, file.getName()));
        }
    }

    private void gatherSelection(@NotNull Map<String, String> files) {
        AtomicReference<String> selectionRef = new AtomicReference<>();
        AtomicReference<String> fileNameRef = new AtomicReference<>();
        AtomicReference<VirtualFile> fileRef = new AtomicReference<>();
        AtomicReference<Integer> startLineRef = new AtomicReference<>(1);

        ApplicationManager.getApplication().invokeAndWait(() ->
                ReadAction.run(() -> {
                    FileEditorManager fem = FileEditorManager.getInstance(project);
                    Editor editor = fem.getSelectedTextEditor();
                    VirtualFile[] selected = fem.getSelectedFiles();

                    if (editor != null && selected.length > 0) {
                        String selText = editor.getSelectionModel().getSelectedText();
                        if (selText != null && !selText.isEmpty()) {
                            selectionRef.set(selText);
                            fileNameRef.set(selected[0].getName());
                            startLineRef.set(
                                    editor.getDocument().getLineNumber(
                                            editor.getSelectionModel().getSelectionStart()) + 1);
                        } else {
                            fileRef.set(selected[0]);
                        }
                    }
                })
        );

        if (selectionRef.get() != null) {
            files.put(fileNameRef.get() + " (selection, starting at line " + startLineRef.get() + ")",
                    selectionRef.get());
        } else if (fileRef.get() != null) {
            ReadAction.run(() -> readFileIntoMap(files, fileRef.get(), fileRef.get().getName()));
        }
    }

    private void gatherChangedFiles(@NotNull Map<String, String> files) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            notify("Cannot determine project path for git diff", NotificationType.WARNING);
            gatherCurrentFile(files);
            return;
        }

        try {
            List<String> changedFileNames = getGitChangedFiles(basePath);
            if (changedFileNames.isEmpty()) {
                notify("No changed files found, reviewing current file", NotificationType.INFORMATION);
                gatherCurrentFile(files);
                return;
            }

            for (String fileName : changedFileNames) {
                String fullPath = basePath + "/" + fileName;
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath);
                if (vf != null && vf.exists() && !vf.isDirectory()) {
                    ReadAction.run(() -> readFileIntoMap(files, vf, fileName));
                }
            }
        } catch (Exception e) {
            LOG.warn("Git diff failed, falling back to current file", e);
            notify("Git diff failed, reviewing current file instead", NotificationType.WARNING);
            gatherCurrentFile(files);
        }
    }

    // ── Git Operations ─────────────────────────────────────────────

    @NotNull
    private List<String> getGitChangedFiles(@NotNull String basePath) throws Exception {
        Set<String> changedFiles = new LinkedHashSet<>();
        for (String gitCmd : List.of("git diff --name-only", "git diff --name-only --cached")) {
            changedFiles.addAll(runGitCommand(basePath, gitCmd));
        }
        return new ArrayList<>(changedFiles);
    }

    /**
     * Gets all files changed since the current branch diverged from main/master.
     * Uses {@code git merge-base} to find the fork point.
     */
    @NotNull
    private List<String> getBranchChangedFiles(@NotNull String basePath) throws Exception {
        // Detect the default branch (main or master)
        String defaultBranch = detectDefaultBranch(basePath);
        LOG.info("Branch review: detected default branch = " + defaultBranch);

        // Find the merge-base (fork point)
        List<String> mergeBaseOutput = runGitCommand(basePath,
                "git merge-base " + defaultBranch + " HEAD");
        if (mergeBaseOutput.isEmpty()) {
            throw new RuntimeException("Could not find merge-base between " + defaultBranch + " and HEAD");
        }
        String mergeBase = mergeBaseOutput.get(0).trim();
        LOG.info("Branch review: merge-base = " + mergeBase);

        // Get all files changed since merge-base
        List<String> changedFiles = runGitCommand(basePath,
                "git diff --name-only " + mergeBase + "...HEAD");

        // Also include uncommitted changes
        Set<String> allFiles = new LinkedHashSet<>(changedFiles);
        allFiles.addAll(runGitCommand(basePath, "git diff --name-only"));
        allFiles.addAll(runGitCommand(basePath, "git diff --name-only --cached"));

        LOG.info("Branch review: " + allFiles.size() + " total changed files");
        return new ArrayList<>(allFiles);
    }

    @NotNull
    private String detectDefaultBranch(@NotNull String basePath) throws Exception {
        // Try common default branch names
        for (String candidate : List.of("main", "master", "develop")) {
            List<String> result = runGitCommand(basePath,
                    "git rev-parse --verify " + candidate + " 2>/dev/null && echo " + candidate);
            if (!result.isEmpty() && result.get(result.size() - 1).trim().equals(candidate)) {
                return candidate;
            }
        }
        // Fallback: use origin/HEAD
        List<String> result = runGitCommand(basePath,
                "git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null");
        if (!result.isEmpty()) {
            String ref = result.get(0).trim();
            // refs/remotes/origin/main -> main
            int lastSlash = ref.lastIndexOf('/');
            if (lastSlash >= 0) {
                return ref.substring(lastSlash + 1);
            }
        }
        return "main"; // ultimate fallback
    }

    @NotNull
    private List<String> runGitCommand(@NotNull String basePath, @NotNull String gitCmd) throws Exception {
        List<String> command = new ArrayList<>();
        ClaudeAssistSettings.State settings = getSettings();

        if (settings.useWsl) {
            command.add("wsl.exe");
            if (!settings.wslDistro.isEmpty()) {
                command.add("-d");
                command.add(settings.wslDistro);
            }
            command.add("--");
            command.add("bash");
            command.add("-c");
            String wslPath = ClaudeAssistService.toWslPath(basePath);
            command.add("cd '" + wslPath + "' && " + gitCmd);
        } else {
            command.add("bash");
            command.add("-c");
            command.add("cd '" + basePath + "' && " + gitCmd);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }
        process.waitFor();
        return lines;
    }

    // ── File Reading ───────────────────────────────────────────────

    private void readFileIntoMap(
            @NotNull Map<String, String> files,
            @NotNull VirtualFile file,
            @NotNull String mapKey
    ) {
        try {
            byte[] bytes = file.contentsToByteArray();
            String content;
            if (bytes.length > 100_000) {
                content = new String(bytes, 0, 50_000, StandardCharsets.UTF_8)
                        + "\n... [truncated, file too large] ...";
            } else {
                content = new String(bytes, StandardCharsets.UTF_8);
            }
            files.put(mapKey, content);
        } catch (Exception e) {
            LOG.warn("Failed to read file: " + file.getPath(), e);
        }
    }

    // ── Process Control ────────────────────────────────────────────

    public void abort() {
        StreamJsonService.getInstance().abort();
        reviewing.set(false);
        notifyStateChanged();
    }

    public boolean isReviewing() {
        return reviewing.get();
    }

    @Nullable
    public ReviewIssue.ReviewResult getLastResult() {
        return lastResult;
    }

    // ── Persistence ───────────────────────────────────────────────

    private void loadPersistedResult() {
        ReviewIssue.ReviewResult persisted = ReviewPersistence.load(project);
        if (persisted != null) {
            lastResult = persisted;
        }
    }

    /**
     * Called when an issue's fixed state changes. Re-fires the last result to update UI
     * and persists the updated state.
     */
    public void notifyIssueStateChanged() {
        ReviewIssue.ReviewResult result = lastResult;
        if (result != null) {
            fireResult(result);
            ReviewPersistence.save(project, result);
        }
    }

    // ── Listeners ──────────────────────────────────────────────────

    public void addResultListener(@NotNull Consumer<ReviewIssue.ReviewResult> listener) {
        resultListeners.add(listener);
    }

    public void removeResultListener(@NotNull Consumer<ReviewIssue.ReviewResult> listener) {
        resultListeners.remove(listener);
    }

    public void addStateListener(@NotNull Runnable listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(@NotNull Runnable listener) {
        stateListeners.remove(listener);
    }

    public void addProgressListener(@NotNull Consumer<ReviewProgress> listener) {
        progressListeners.add(listener);
    }

    public void removeProgressListener(@NotNull Consumer<ReviewProgress> listener) {
        progressListeners.remove(listener);
    }

    @Nullable
    public ReviewProgress getCurrentProgress() {
        if (!reviewing.get()) return null;
        int total = totalBatches;
        if (total == 0) return null;
        int done = completedBatches.get();
        int issues;
        synchronized (accumulatedIssues) {
            issues = accumulatedIssues.size();
        }
        return new ReviewProgress(done, total, issues);
    }

    private void notifyStateChanged() {
        for (Runnable listener : stateListeners) {
            listener.run();
        }
    }

    private void fireResult(@NotNull ReviewIssue.ReviewResult result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Consumer<ReviewIssue.ReviewResult> listener : resultListeners) {
                listener.accept(result);
            }
        });
    }

    private void fireProgress(@NotNull ReviewProgress progress) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Consumer<ReviewProgress> listener : progressListeners) {
                listener.accept(progress);
            }
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void notify(@NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Assist Notifications")
                .createNotification(message, type)
                .notify(project);
    }

    private ClaudeAssistSettings.State getSettings() {
        ClaudeAssistSettings.State s = ClaudeAssistSettings.getInstance().getState();
        return s != null ? s : new ClaudeAssistSettings.State();
    }
}
