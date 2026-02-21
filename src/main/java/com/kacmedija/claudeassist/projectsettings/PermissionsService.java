package com.kacmedija.claudeassist.projectsettings;

import com.google.gson.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-level service for reading/writing permissions in
 * {@code .claude/settings.local.json}.
 * Only touches the {@code permissions} key — other top-level keys are preserved.
 */
@Service(Service.Level.PROJECT)
public final class PermissionsService {

    private static final Logger LOG = Logger.getInstance(PermissionsService.class);
    private static final String SETTINGS_FILE = ".claude/settings.local.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Project project;

    public PermissionsService(@NotNull Project project) {
        this.project = project;
    }

    public static PermissionsService getInstance(@NotNull Project project) {
        return project.getService(PermissionsService.class);
    }

    // ── Data Class ──────────────────────────────────────────────

    public static class PermissionsConfig {
        private final List<String> allow;
        private final List<String> deny;

        public PermissionsConfig(@NotNull List<String> allow, @NotNull List<String> deny) {
            this.allow = new ArrayList<>(allow);
            this.deny = new ArrayList<>(deny);
        }

        @NotNull
        public List<String> getAllow() {
            return allow;
        }

        @NotNull
        public List<String> getDeny() {
            return deny;
        }
    }

    // ── Read / Write ────────────────────────────────────────────

    /**
     * Reads the current permissions from settings.local.json.
     * Returns an empty config if the file or permissions key doesn't exist.
     */
    @NotNull
    public PermissionsConfig readPermissions() {
        Path path = getSettingsPath();
        if (path == null || !Files.exists(path)) {
            return new PermissionsConfig(List.of(), List.of());
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject perms = root.has("permissions") ? root.getAsJsonObject("permissions") : null;

            if (perms == null) {
                return new PermissionsConfig(List.of(), List.of());
            }

            List<String> allow = parseStringArray(perms, "allow");
            List<String> deny = parseStringArray(perms, "deny");
            return new PermissionsConfig(allow, deny);
        } catch (Exception e) {
            LOG.warn("Failed to read permissions from settings.local.json", e);
            return new PermissionsConfig(List.of(), List.of());
        }
    }

    /**
     * Saves permissions to settings.local.json, preserving other top-level keys.
     */
    public boolean savePermissions(@NotNull PermissionsConfig config) {
        Path path = getSettingsPath();
        if (path == null) return false;

        try {
            // Read existing JSON to preserve other keys
            JsonObject root;
            if (Files.exists(path)) {
                String existing = Files.readString(path, StandardCharsets.UTF_8);
                try {
                    root = JsonParser.parseString(existing).getAsJsonObject();
                } catch (Exception e) {
                    root = new JsonObject();
                }
            } else {
                root = new JsonObject();
            }

            // Build permissions object
            JsonObject perms = new JsonObject();

            JsonArray allowArray = new JsonArray();
            for (String s : config.getAllow()) {
                allowArray.add(s);
            }
            perms.add("allow", allowArray);

            JsonArray denyArray = new JsonArray();
            for (String s : config.getDeny()) {
                denyArray.add(s);
            }
            perms.add("deny", denyArray);

            root.add("permissions", perms);

            // Ensure .claude/ directory exists
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to save permissions to settings.local.json", e);
            return false;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    @Nullable
    private Path getSettingsPath() {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, SETTINGS_FILE);
    }

    @NotNull
    private static List<String> parseStringArray(@NotNull JsonObject obj, @NotNull String key) {
        List<String> result = new ArrayList<>();
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return result;

        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (el.isJsonPrimitive()) {
                result.add(el.getAsString());
            }
        }
        return result;
    }
}
