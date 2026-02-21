package com.kacmedija.claudeassist.services;

import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Application-level service that manages communication with the Claude CLI.
 * Handles WSL path translation, process lifecycle, streaming output, and session tracking.
 */
@Service(Service.Level.APP)
public final class ClaudeAssistService {

    private static final Logger LOG = Logger.getInstance(ClaudeAssistService.class);

    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static ClaudeAssistService getInstance() {
        return ApplicationManager.getApplication().getService(ClaudeAssistService.class);
    }

    // ── Path Conversion (delegates to CommandExecutor) ────────────

    /**
     * Converts a Windows path to a WSL-compatible path.
     * @deprecated Use {@link CommandExecutor#toWslPath(String)} directly.
     */
    @Deprecated
    public static String toWslPath(@NotNull String windowsPath) {
        return CommandExecutor.toWslPath(windowsPath);
    }

    /**
     * Converts a WSL path back to Windows format.
     * @deprecated Use {@link CommandExecutor#toWindowsPath(String)} directly.
     */
    @Deprecated
    public static String toWindowsPath(@NotNull String wslPath) {
        return CommandExecutor.toWindowsPath(wslPath);
    }

    // ── Platform Detection (delegates to CommandExecutor) ─────────

    /**
     * Returns {@code true} when running on native Windows (not inside WSL).
     * @deprecated Use {@link CommandExecutor#isNativeWindows()} directly.
     */
    @Deprecated
    public static boolean isNativeWindows() {
        return CommandExecutor.isNativeWindows();
    }

    // ── Core Execution (Deprecated) ──────────────────────────────

    /**
     * Response container for a completed Claude CLI request.
     * @deprecated Use {@link StreamJsonService.StreamResult} instead.
     */
    @Deprecated
    public record ClaudeResponse(String text, int exitCode, boolean aborted) {}

    /**
     * Sends a prompt to Claude CLI and streams the response.
     *
     * @deprecated Use {@link StreamJsonService#sendStreamJson} instead.
     */
    @Deprecated
    public CompletableFuture<ClaudeResponse> sendPrompt(
            @NotNull String prompt,
            @Nullable String workDir,
            @Nullable String sessionId,
            @Nullable Consumer<String> onChunk,
            @Nullable ProgressIndicator indicator
    ) {
        abort();

        return CompletableFuture.supplyAsync(() -> {
            isRunning.set(true);
            Path tempFile = null;
            try {
                ClaudeAssistSettings.State settings = getSettings();

                tempFile = Files.createTempFile("claude-prompt-", ".txt");
                Files.writeString(tempFile, prompt, StandardCharsets.UTF_8);

                List<String> cliArgs = new ArrayList<>();
                cliArgs.add("--print");
                if (sessionId != null && !sessionId.isEmpty()) {
                    cliArgs.add("--session-id");
                    cliArgs.add(sessionId);
                }

                CommandExecutor.ProcessSpec spec = CommandExecutor.claude(
                        settings, tempFile, workDir,
                        cliArgs.toArray(new String[0]));

                LOG.info("Executing: " + String.join(" ", spec.command()));

                ProcessBuilder pb = spec.createProcessBuilder();
                pb.redirectErrorStream(false);

                Process process = pb.start();
                currentProcess.set(process);

                StringBuilder fullOutput = new StringBuilder();
                StringBuilder errorOutput = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (indicator != null && indicator.isCanceled()) {
                            abort();
                            return new ClaudeResponse(fullOutput.toString().trim(), -1, true);
                        }

                        fullOutput.append(line).append('\n');

                        if (onChunk != null) {
                            final String chunk = line;
                            ApplicationManager.getApplication().invokeLater(() -> onChunk.accept(chunk));
                        }
                    }
                }

                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        errorOutput.append(line).append('\n');
                    }
                }

                int exitCode = process.waitFor();
                currentProcess.set(null);

                if (exitCode != 0 && errorOutput.length() > 0) {
                    LOG.warn("Claude CLI stderr: " + errorOutput);
                }

                return new ClaudeResponse(fullOutput.toString().trim(), exitCode, false);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ClaudeResponse("", -1, true);
            } catch (Exception e) {
                LOG.error("Failed to execute Claude CLI", e);
                return new ClaudeResponse("Error: " + e.getMessage(), 1, false);
            } finally {
                isRunning.set(false);
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                }
            }
        });
    }

    // ── Process Control ───────────────────────────────────────────

    /**
     * Aborts the currently running Claude CLI process, if any.
     * @deprecated Use {@link StreamJsonService#abort()} instead.
     */
    @Deprecated
    public void abort() {
        Process proc = currentProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            LOG.info("Aborting Claude CLI process");
            proc.destroy();
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(3000);
                    if (proc.isAlive()) {
                        proc.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        isRunning.set(false);
    }

    /**
     * @deprecated Use {@link StreamJsonService#isRunning()} instead.
     */
    @Deprecated
    public boolean isRunning() {
        return isRunning.get();
    }

    // ── Health Check ──────────────────────────────────────────────

    /**
     * Checks whether Claude CLI is available and returns its version.
     */
    public CompletableFuture<HealthStatus> checkHealth() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ClaudeAssistSettings.State settings = getSettings();

                CommandExecutor.ProcessSpec spec = CommandExecutor.direct(
                        settings, settings.claudePath, "--version");

                ProcessBuilder pb = spec.createProcessBuilder();
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                String output;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    output = reader.lines().reduce("", (a, b) -> a + b).trim();
                }

                int exitCode = proc.waitFor();
                if (exitCode == 0) {
                    return new HealthStatus(true, output, null);
                } else {
                    return new HealthStatus(false, null, "Exit code: " + exitCode);
                }
            } catch (Exception e) {
                return new HealthStatus(false, null, e.getMessage());
            }
        });
    }

    public record HealthStatus(boolean available, @Nullable String version, @Nullable String error) {}

    // ── Helpers ────────────────────────────────────────────────────

    private ClaudeAssistSettings.State getSettings() {
        ClaudeAssistSettings.State s = ClaudeAssistSettings.getInstance().getState();
        return s != null ? s : new ClaudeAssistSettings.State();
    }
}
