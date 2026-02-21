package com.kacmedija.claudeassist.agents;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service for managing agent prompt files in {@code .claude/agents/}.
 * Provides CRUD operations, file-based versioning, and VFS change watching.
 */
@Service(Service.Level.PROJECT)
public final class AgentService {

    private static final Logger LOG = Logger.getInstance(AgentService.class);
    private static final String AGENTS_DIR = ".claude/agents";
    private static final String VERSIONS_DIR = ".versions";

    private final Project project;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public AgentService(@NotNull Project project) {
        this.project = project;

        // Watch for file system changes in .claude/agents/
        project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                Path agentsPath = getAgentsDir();
                if (agentsPath == null) return;
                String agentsPrefix = agentsPath.toString();

                for (VFileEvent event : events) {
                    String eventPath = event.getPath();
                    if (eventPath.startsWith(agentsPrefix) && !eventPath.contains(VERSIONS_DIR)) {
                        notifyChanged();
                        break;
                    }
                }
            }
        });
    }

    public static AgentService getInstance(@NotNull Project project) {
        return project.getService(AgentService.class);
    }

    // ── CRUD Operations ──────────────────────────────────────────

    /**
     * Lists all agent .md files in .claude/agents/, excluding the .versions/ directory.
     */
    @NotNull
    public List<AgentFile> listAgents() {
        List<AgentFile> agents = new ArrayList<>();
        Path dir = getAgentsDir();
        if (dir == null || !Files.isDirectory(dir)) return agents;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - 3); // strip .md
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                agents.add(new AgentFile(name, file, content, modified));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list agents", e);
        }

        agents.sort(Comparator.comparing(AgentFile::getName, String.CASE_INSENSITIVE_ORDER));
        return agents;
    }

    /**
     * Reads a single agent by name.
     */
    @Nullable
    public AgentFile readAgent(@NotNull String name) {
        Path dir = getAgentsDir();
        if (dir == null) return null;

        Path file = dir.resolve(name + ".md");
        if (!Files.exists(file)) return null;

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return new AgentFile(name, file, content, modified);
        } catch (IOException e) {
            LOG.warn("Failed to read agent: " + name, e);
            return null;
        }
    }

    /**
     * Creates a new agent file. Returns the created AgentFile, or null on failure.
     */
    @Nullable
    public AgentFile createAgent(@NotNull String name, @NotNull String content) {
        Path dir = getAgentsDir();
        if (dir == null) return null;

        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".md");
            if (Files.exists(file)) {
                LOG.warn("Agent already exists: " + name);
                return null;
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            refreshVfs(file);
            notifyChanged();

            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return new AgentFile(name, file, content, modified);
        } catch (IOException e) {
            LOG.warn("Failed to create agent: " + name, e);
            return null;
        }
    }

    /**
     * Saves the agent's current content to disk.
     */
    public boolean saveAgent(@NotNull AgentFile agent) {
        try {
            Files.writeString(agent.getPath(), agent.getContent(), StandardCharsets.UTF_8);
            agent.setLastModified(Files.getLastModifiedTime(agent.getPath()).toInstant());
            refreshVfs(agent.getPath());
            notifyChanged();
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to save agent: " + agent.getName(), e);
            return false;
        }
    }

    /**
     * Deletes an agent file and all its versions.
     */
    public boolean deleteAgent(@NotNull AgentFile agent) {
        try {
            // Delete version directory
            Path versionsDir = getVersionsDir(agent.getName());
            if (versionsDir != null && Files.isDirectory(versionsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir)) {
                    for (Path versionFile : stream) {
                        Files.deleteIfExists(versionFile);
                    }
                }
                Files.deleteIfExists(versionsDir);
            }

            // Delete the agent file
            Files.deleteIfExists(agent.getPath());
            refreshVfs(agent.getPath());
            notifyChanged();
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to delete agent: " + agent.getName(), e);
            return false;
        }
    }

    // ── Versioning ───────────────────────────────────────────────

    /**
     * Saves a version of the agent. The version is stored at
     * {@code .claude/agents/.versions/{agentname}/{timestamp}.md}.
     *
     * @param agent The agent to version
     * @param label Optional label (e.g. "Before AI enhance")
     * @return The created version, or null on failure
     */
    @Nullable
    public AgentVersion saveVersion(@NotNull AgentFile agent, @Nullable String label) {
        Path versionsDir = getVersionsDir(agent.getName());
        if (versionsDir == null) return null;

        try {
            Files.createDirectories(versionsDir);
            String timestamp = AgentVersion.createTimestamp();
            Path versionFile = versionsDir.resolve(timestamp + ".md");

            // Store content with optional label header
            String versionContent;
            if (label != null && !label.isBlank()) {
                versionContent = "<!-- version-label: " + label + " -->\n" + agent.getContent();
            } else {
                versionContent = agent.getContent();
            }

            Files.writeString(versionFile, versionContent, StandardCharsets.UTF_8);
            refreshVfs(versionFile);

            return new AgentVersion(agent.getName(), timestamp, versionFile, label);
        } catch (IOException e) {
            LOG.warn("Failed to save version for agent: " + agent.getName(), e);
            return null;
        }
    }

    /**
     * Lists all versions for a given agent, newest first.
     */
    @NotNull
    public List<AgentVersion> listVersions(@NotNull String agentName) {
        List<AgentVersion> versions = new ArrayList<>();
        Path versionsDir = getVersionsDir(agentName);
        if (versionsDir == null || !Files.isDirectory(versionsDir)) return versions;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir, "*.md")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String timestamp = fileName.substring(0, fileName.length() - 3); // strip .md

                // Read label from header if present
                String label = readVersionLabel(file);
                versions.add(new AgentVersion(agentName, timestamp, file, label));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list versions for agent: " + agentName, e);
        }

        // Sort newest first
        versions.sort(Comparator.comparing(AgentVersion::timestamp).reversed());
        return versions;
    }

    /**
     * Reads the content of a version file (without the label header).
     */
    @NotNull
    public String readVersionContent(@NotNull AgentVersion version) {
        try {
            String content = Files.readString(version.path(), StandardCharsets.UTF_8);
            // Strip the label header if present
            if (content.startsWith("<!-- version-label:")) {
                int endIdx = content.indexOf("-->\n");
                if (endIdx >= 0) {
                    return content.substring(endIdx + 4);
                }
            }
            return content;
        } catch (IOException e) {
            LOG.warn("Failed to read version: " + version.path(), e);
            return "";
        }
    }

    /**
     * Restores an agent to a specific version. Auto-saves a version before restoring.
     */
    public boolean restoreVersion(@NotNull AgentFile agent, @NotNull AgentVersion version) {
        // Save current state as a version before restoring
        saveVersion(agent, "Before restore");

        String versionContent = readVersionContent(version);
        agent.setContent(versionContent);
        return saveAgent(agent);
    }

    /**
     * Deletes a specific version file.
     */
    public boolean deleteVersion(@NotNull AgentVersion version) {
        try {
            Files.deleteIfExists(version.path());
            refreshVfs(version.path());
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to delete version: " + version.path(), e);
            return false;
        }
    }

    // ── Listener Pattern ─────────────────────────────────────────

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    public void notifyChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : changeListeners) {
                listener.run();
            }
        });
    }

    // ── Path Helpers ─────────────────────────────────────────────

    @Nullable
    private Path getAgentsDir() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, AGENTS_DIR);
    }

    @Nullable
    private Path getVersionsDir(@NotNull String agentName) {
        Path agentsDir = getAgentsDir();
        if (agentsDir == null) return null;
        return agentsDir.resolve(VERSIONS_DIR).resolve(agentName);
    }

    @Nullable
    private String readVersionLabel(@NotNull Path file) {
        try {
            String firstLine = Files.readString(file, StandardCharsets.UTF_8);
            if (firstLine.startsWith("<!-- version-label: ")) {
                int endIdx = firstLine.indexOf(" -->");
                if (endIdx > 20) {
                    return firstLine.substring(20, endIdx);
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void refreshVfs(@NotNull Path path) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (vf != null) {
            vf.refresh(true, false);
        }
    }
}
