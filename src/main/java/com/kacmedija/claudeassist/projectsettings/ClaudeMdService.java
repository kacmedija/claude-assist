package com.kacmedija.claudeassist.projectsettings;

import com.kacmedija.claudeassist.agents.AgentVersion;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Project-level service for reading/writing CLAUDE.md and managing its versions.
 * Versions are stored in {@code .claude/.versions/claude-md/{timestamp}.md}.
 */
@Service(Service.Level.PROJECT)
public final class ClaudeMdService {

    private static final Logger LOG = Logger.getInstance(ClaudeMdService.class);
    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String VERSIONS_DIR = ".claude/.versions/claude-md";
    private static final String AGENT_NAME = "claude-md";

    private final Project project;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public ClaudeMdService(@NotNull Project project) {
        this.project = project;

        // Watch for external changes to CLAUDE.md
        project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                Path claudeMdPath = getClaudeMdPath();
                if (claudeMdPath == null) return;
                String claudeMdStr = claudeMdPath.toString();

                for (VFileEvent event : events) {
                    if (event.getPath().equals(claudeMdStr)) {
                        notifyChanged();
                        break;
                    }
                }
            }
        });
    }

    public static ClaudeMdService getInstance(@NotNull Project project) {
        return project.getService(ClaudeMdService.class);
    }

    // ── CLAUDE.md I/O ───────────────────────────────────────────

    /**
     * Returns true if CLAUDE.md exists in the project root.
     */
    public boolean claudeMdExists() {
        Path path = getClaudeMdPath();
        return path != null && Files.exists(path);
    }

    /**
     * Reads CLAUDE.md content. Returns empty string if file doesn't exist.
     */
    @NotNull
    public String readClaudeMd() {
        Path path = getClaudeMdPath();
        if (path == null || !Files.exists(path)) return "";

        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read CLAUDE.md", e);
            return "";
        }
    }

    /**
     * Saves content to CLAUDE.md, creating the file if it doesn't exist.
     */
    public boolean saveClaudeMd(@NotNull String content) {
        Path path = getClaudeMdPath();
        if (path == null) return false;

        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            refreshVfs(path);
            notifyChanged();
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to save CLAUDE.md", e);
            return false;
        }
    }

    // ── Versioning ──────────────────────────────────────────────

    /**
     * Saves a version snapshot of CLAUDE.md.
     *
     * @param content The content to version
     * @param label   Optional label (e.g. "Before AI enhance")
     * @return The created version, or null on failure
     */
    @Nullable
    public AgentVersion saveVersion(@NotNull String content, @Nullable String label) {
        Path versionsDir = getVersionsDir();
        if (versionsDir == null) return null;

        try {
            Files.createDirectories(versionsDir);
            String timestamp = AgentVersion.createTimestamp();
            Path versionFile = versionsDir.resolve(timestamp + ".md");

            String versionContent;
            if (label != null && !label.isBlank()) {
                versionContent = "<!-- version-label: " + label + " -->\n" + content;
            } else {
                versionContent = content;
            }

            Files.writeString(versionFile, versionContent, StandardCharsets.UTF_8);
            refreshVfs(versionFile);

            return new AgentVersion(AGENT_NAME, timestamp, versionFile, label);
        } catch (IOException e) {
            LOG.warn("Failed to save CLAUDE.md version", e);
            return null;
        }
    }

    /**
     * Lists all versions of CLAUDE.md, newest first.
     */
    @NotNull
    public List<AgentVersion> listVersions() {
        List<AgentVersion> versions = new ArrayList<>();
        Path versionsDir = getVersionsDir();
        if (versionsDir == null || !Files.isDirectory(versionsDir)) return versions;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir, "*.md")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String timestamp = fileName.substring(0, fileName.length() - 3);
                String label = readVersionLabel(file);
                versions.add(new AgentVersion(AGENT_NAME, timestamp, file, label));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list CLAUDE.md versions", e);
        }

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
     * Restores CLAUDE.md to a specific version. Auto-saves a version before restoring.
     */
    public boolean restoreVersion(@NotNull AgentVersion version) {
        String currentContent = readClaudeMd();
        if (!currentContent.isEmpty()) {
            saveVersion(currentContent, "Before restore");
        }

        String versionContent = readVersionContent(version);
        return saveClaudeMd(versionContent);
    }

    // ── Listener Pattern ────────────────────────────────────────

    public void addChangeListener(@NotNull Runnable listener) {
        changeListeners.add(listener);
    }

    public void removeChangeListener(@NotNull Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : changeListeners) {
                listener.run();
            }
        });
    }

    // ── Path Helpers ────────────────────────────────────────────

    @Nullable
    private Path getClaudeMdPath() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, CLAUDE_MD);
    }

    @Nullable
    private Path getVersionsDir() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, VERSIONS_DIR);
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
