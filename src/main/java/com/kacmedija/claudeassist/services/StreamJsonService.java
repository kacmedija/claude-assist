package com.kacmedija.claudeassist.services;

import com.kacmedija.claudeassist.settings.ClaudeAssistSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Application-level service that runs Claude CLI with --print --output-format stream-json
 * and parses the JSONL output for background actions (inline edit, explain, etc.).
 */
@Service(Service.Level.APP)
public final class StreamJsonService {

    private static final Logger LOG = Logger.getInstance(StreamJsonService.class);

    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static StreamJsonService getInstance() {
        return ApplicationManager.getApplication().getService(StreamJsonService.class);
    }

    // ── Data Records ──────────────────────────────────────────────

    /**
     * A single event from the stream-json output.
     */
    public record StreamEvent(
            @NotNull String type,
            @Nullable String text,
            @Nullable String toolName,
            @Nullable String toolInput,
            @Nullable String raw
    ) {}

    /**
     * The complete result after the stream finishes.
     */
    public record StreamResult(
            @NotNull String fullText,
            int exitCode,
            boolean aborted,
            @NotNull List<StreamEvent> events
    ) {}

    // ── Core Execution ────────────────────────────────────────────

    /**
     * Sends a prompt to Claude CLI using stream-json format and returns the result.
     */
    public CompletableFuture<StreamResult> sendStreamJson(
            @NotNull String prompt,
            @Nullable String workDir,
            @Nullable ProgressIndicator indicator
    ) {
        return sendStreamJson(prompt, workDir, indicator, null);
    }

    /**
     * Sends a prompt to Claude CLI using stream-json format and returns the result.
     * Optionally streams text deltas to the provided callback as they arrive.
     *
     * @param prompt      The prompt text
     * @param workDir     Working directory (Windows or WSL path)
     * @param indicator   Optional progress indicator for cancellation
     * @param onTextDelta Optional callback invoked for each text chunk (from background thread)
     * @return Future that completes with the parsed stream result
     */
    public CompletableFuture<StreamResult> sendStreamJson(
            @NotNull String prompt,
            @Nullable String workDir,
            @Nullable ProgressIndicator indicator,
            @Nullable Consumer<String> onTextDelta
    ) {
        abort();

        return CompletableFuture.supplyAsync(() -> {
            isRunning.set(true);
            Path tempFile = null;
            try {
                ClaudeAssistSettings.State settings = getSettings();

                tempFile = Files.createTempFile("claude-prompt-", ".txt");
                Files.writeString(tempFile, prompt, StandardCharsets.UTF_8);

                CommandExecutor.ProcessSpec spec = CommandExecutor.claude(
                        settings, tempFile, workDir,
                        "--print", "--verbose", "--output-format", "stream-json");

                LOG.info("StreamJson executing: " + String.join(" ", spec.command()));

                ProcessBuilder pb = spec.createProcessBuilder();
                pb.environment().remove("CLAUDECODE");
                pb.redirectErrorStream(false);

                Process process = pb.start();
                currentProcess.set(process);

                StringBuilder fullText = new StringBuilder();
                String resultText = null;
                List<StreamEvent> events = new ArrayList<>();

                // Drain stderr in a separate thread to prevent pipe buffer deadlock.
                // If the CLI fills the stderr buffer while we're blocked reading stdout,
                // both sides deadlock. Reading concurrently avoids this.
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String errLine;
                        while ((errLine = errReader.readLine()) != null) {
                            LOG.warn("Claude stderr: " + errLine);
                        }
                    } catch (IOException e) {
                        LOG.debug("Error reading stderr", e);
                    }
                }, "claude-stderr-drain");
                stderrThread.setDaemon(true);
                stderrThread.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (indicator != null && indicator.isCanceled()) {
                            abort();
                            return new StreamResult(fullText.toString(), -1, true, events);
                        }

                        StreamEvent event = parseStreamEvent(line);
                        if (event != null) {
                            events.add(event);
                            if (event.text() != null) {
                                // "result" event is the authoritative final text —
                                // it supersedes accumulated text from assistant/delta events
                                if ("result".equals(event.type())) {
                                    resultText = event.text();
                                } else {
                                    fullText.append(event.text());
                                    if (onTextDelta != null) {
                                        onTextDelta.accept(event.text());
                                    }
                                }
                            }
                        }
                    }
                }

                // Wait for stderr drain to finish
                stderrThread.join(5000);

                int exitCode = process.waitFor();
                currentProcess.set(null);

                // Prefer the result event's text if available (avoids duplication)
                String finalText = resultText != null ? resultText : fullText.toString().trim();
                return new StreamResult(finalText, exitCode, false, events);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StreamResult("", -1, true, List.of());
            } catch (Exception e) {
                LOG.error("Failed to execute Claude CLI stream-json", e);
                return new StreamResult("Error: " + e.getMessage(), 1, false, List.of());
            } finally {
                isRunning.set(false);
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                }
            }
        });
    }

    // ── JSONL Parsing ─────────────────────────────────────────────

    @Nullable
    private StreamEvent parseStreamEvent(@NotNull String jsonLine) {
        if (jsonLine.isBlank()) return null;

        try {
            JsonObject obj = JsonParser.parseString(jsonLine).getAsJsonObject();
            String type = getStringField(obj, "type");
            if (type == null) return null;

            String text = null;
            String toolName = null;
            String toolInput = null;

            switch (type) {
                case "content_block_delta" -> {
                    JsonObject delta = obj.has("delta") ? obj.getAsJsonObject("delta") : null;
                    if (delta != null && "text_delta".equals(getStringField(delta, "type"))) {
                        text = getStringField(delta, "text");
                    }
                }
                case "content_block_start" -> {
                    JsonObject contentBlock = obj.has("content_block")
                            ? obj.getAsJsonObject("content_block") : null;
                    if (contentBlock != null && "tool_use".equals(getStringField(contentBlock, "type"))) {
                        toolName = getStringField(contentBlock, "name");
                    }
                }
                case "assistant" -> {
                    // Claude CLI 2.x verbose stream-json: full assistant message
                    // message.content[].text contains the response text
                    if (obj.has("message") && obj.get("message").isJsonObject()) {
                        JsonObject message = obj.getAsJsonObject("message");
                        if (message.has("content") && message.get("content").isJsonArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (var el : message.getAsJsonArray("content")) {
                                if (el.isJsonObject()) {
                                    JsonObject block = el.getAsJsonObject();
                                    if ("text".equals(getStringField(block, "type"))) {
                                        String t = getStringField(block, "text");
                                        if (t != null) sb.append(t);
                                    }
                                }
                            }
                            if (!sb.isEmpty()) text = sb.toString();
                        }
                    }
                }
                case "message_start", "message_delta", "message_stop" -> {
                    // Lifecycle events, no text extraction needed
                }
                case "result" -> {
                    // Final result event from claude --print
                    text = getStringField(obj, "result");
                }
                default -> {
                    // Unknown event type
                }
            }

            return new StreamEvent(type, text, toolName, toolInput, jsonLine);

        } catch (Exception e) {
            LOG.debug("Failed to parse stream-json line: " + jsonLine, e);
            return null;
        }
    }

    @Nullable
    private String getStringField(@NotNull JsonObject obj, @NotNull String field) {
        JsonElement el = obj.get(field);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    // ── Process Control ───────────────────────────────────────────

    public void abort() {
        Process proc = currentProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            LOG.info("Aborting Claude CLI stream-json process");
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

    public boolean isRunning() {
        return isRunning.get();
    }

    private ClaudeAssistSettings.State getSettings() {
        ClaudeAssistSettings.State s = ClaudeAssistSettings.getInstance().getState();
        return s != null ? s : new ClaudeAssistSettings.State();
    }
}
