package com.kacmedija.claudeassist.agents;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a saved version of an agent prompt file.
 * Versions are stored at {@code .claude/agents/.versions/{agentname}/{timestamp}.md}.
 */
public record AgentVersion(
        @NotNull String agentName,
        @NotNull String timestamp,
        @NotNull Path path,
        @Nullable String label
) {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter FILE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /**
     * Returns a display-friendly string for the version.
     * Format: "2026-02-21 14:30:00" or "2026-02-21 14:30:00 — Before AI enhance"
     */
    public @NotNull String getDisplayLabel() {
        String timeDisplay = formatTimestamp();
        if (label != null && !label.isBlank()) {
            return timeDisplay + " — " + label;
        }
        return timeDisplay;
    }

    /**
     * Parses the file-format timestamp into a human-readable date string.
     */
    private @NotNull String formatTimestamp() {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(timestamp, FILE_FORMAT);
            return dateTime.format(DISPLAY_FORMAT);
        } catch (Exception e) {
            return timestamp;
        }
    }

    /**
     * Creates a new timestamp string suitable for use as a version file name.
     */
    public static @NotNull String createTimestamp() {
        return LocalDateTime.now().format(FILE_FORMAT);
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
