package com.kacmedija.claudeassist.review;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code composer.json} from the project root and detects
 * PHP version, framework, and framework version.
 *
 * @deprecated Use {@link com.kacmedija.claudeassist.context.ProjectContextDetector} for universal multi-ecosystem detection.
 */
@Deprecated
public final class ProjectContextDetector {

    private static final Logger LOG = Logger.getInstance(ProjectContextDetector.class);

    /** Matches version constraints like ^8.4, ~8.2, >=8.1, 8.3.*, 8.0 */
    private static final Pattern PHP_VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+)");

    private ProjectContextDetector() {}

    /**
     * Detects project context from composer.json.
     *
     * @return detected context, or null if composer.json doesn't exist or can't be parsed
     */
    @Nullable
    public static ReviewStandards.DetectedContext detect(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        Path composerJson = Path.of(basePath, "composer.json");
        if (!Files.exists(composerJson)) return null;

        try {
            String json = Files.readString(composerJson, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            JsonObject require = getJsonObject(root, "require");

            // PHP version
            String phpConstraint = null;
            String resolvedPhp = null;
            if (require != null) {
                phpConstraint = getJsonString(require, "php");
                if (phpConstraint != null) {
                    resolvedPhp = extractPhpVersion(phpConstraint);
                }
            }

            // Framework detection
            ReviewStandards.Framework framework = null;
            String frameworkVersion = null;

            if (require != null) {
                String laravelVersion = getJsonString(require, "laravel/framework");
                if (laravelVersion != null) {
                    framework = ReviewStandards.Framework.LARAVEL;
                    frameworkVersion = laravelVersion;
                } else {
                    String symfonyVersion = getJsonString(require, "symfony/framework-bundle");
                    if (symfonyVersion != null) {
                        framework = ReviewStandards.Framework.SYMFONY;
                        frameworkVersion = symfonyVersion;
                    }
                }
            }

            LOG.info("Detected context: php=" + phpConstraint
                    + " -> " + resolvedPhp
                    + ", framework=" + framework
                    + " " + frameworkVersion);

            return new ReviewStandards.DetectedContext(phpConstraint, resolvedPhp, framework, frameworkVersion);

        } catch (Exception e) {
            LOG.warn("Failed to parse composer.json", e);
            return null;
        }
    }

    /**
     * Extracts the major.minor PHP version from a constraint string.
     * Examples: "^8.4" -> "8.4", ">=8.1.0" -> "8.1", "~8.2" -> "8.2", "8.3.*" -> "8.3"
     */
    @Nullable
    static String extractPhpVersion(@NotNull String constraint) {
        Matcher matcher = PHP_VERSION_PATTERN.matcher(constraint);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Nullable
    private static JsonObject getJsonObject(@NotNull JsonObject parent, @NotNull String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    @Nullable
    private static String getJsonString(@NotNull JsonObject parent, @NotNull String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }
}
