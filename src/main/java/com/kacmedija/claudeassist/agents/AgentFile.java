package com.kacmedija.claudeassist.agents;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Data model representing an agent prompt file (.md) in {@code .claude/agents/}.
 */
public final class AgentFile {

    private final @NotNull String name;
    private final @NotNull Path path;
    private @NotNull String content;
    private @NotNull Instant lastModified;

    public AgentFile(@NotNull String name, @NotNull Path path, @NotNull String content, @NotNull Instant lastModified) {
        this.name = name;
        this.path = path;
        this.content = content;
        this.lastModified = lastModified;
    }

    public @NotNull String getName() {
        return name;
    }

    public @NotNull Path getPath() {
        return path;
    }

    public @NotNull String getContent() {
        return content;
    }

    public void setContent(@NotNull String content) {
        this.content = content;
    }

    public @NotNull Instant getLastModified() {
        return lastModified;
    }

    public void setLastModified(@NotNull Instant lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Returns the file name with extension (e.g. "my-agent.md").
     */
    public @NotNull String getFileName() {
        return name + ".md";
    }

    @Override
    public String toString() {
        return name;
    }
}
