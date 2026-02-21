package com.kacmedija.claudeassist.review;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists review results to {@code {projectBasePath}/.claude/review-results.json}
 * so they survive IDE restarts.
 */
public final class ReviewPersistence {

    private static final Logger LOG = Logger.getInstance(ReviewPersistence.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ReviewPersistence() {}

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Saves the review result asynchronously on a pooled thread.
     */
    public static void save(@NotNull Project project, @NotNull ReviewIssue.ReviewResult result) {
        Path path = getFilePath(project);
        if (path == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                PersistedReview review = toPersistedReview(result);
                String json = GSON.toJson(review);

                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
                LOG.info("Saved review results to " + path + " (" + result.issues().size() + " issues)");
            } catch (IOException e) {
                LOG.warn("Failed to save review results", e);
            }
        });
    }

    /**
     * Loads persisted review results synchronously (small file, fast).
     */
    @Nullable
    public static ReviewIssue.ReviewResult load(@NotNull Project project) {
        Path path = getFilePath(project);
        if (path == null || !Files.exists(path)) return null;

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            PersistedReview review = GSON.fromJson(json, PersistedReview.class);
            if (review == null || review.issues == null) return null;

            ReviewIssue.ReviewResult result = fromPersistedReview(review);
            LOG.info("Loaded review results from " + path + " (" + result.issues().size() + " issues)");
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to load review results from " + path, e);
            return null;
        }
    }

    /**
     * Deletes the persisted review results file asynchronously.
     */
    public static void delete(@NotNull Project project) {
        Path path = getFilePath(project);
        if (path == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Files.deleteIfExists(path);
                LOG.info("Deleted review results file: " + path);
            } catch (IOException e) {
                LOG.warn("Failed to delete review results file", e);
            }
        });
    }

    // ── Standards Persistence ────────────────────────────────────────

    /**
     * Saves project standards asynchronously to {@code .claude/review-standards.json}.
     */
    public static void saveStandards(@NotNull Project project, @NotNull ReviewStandards.ProjectStandards standards) {
        Path path = getStandardsFilePath(project);
        if (path == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                PersistedStandards ps = new PersistedStandards();
                ps.phpVersion = standards.phpVersion().name();
                ps.codingStandard = standards.codingStandard().name();
                ps.framework = standards.framework().name();

                String json = GSON.toJson(ps);
                Files.createDirectories(path.getParent());
                Files.writeString(path, json, StandardCharsets.UTF_8);
                LOG.info("Saved review standards to " + path);
            } catch (IOException e) {
                LOG.warn("Failed to save review standards", e);
            }
        });
    }

    /**
     * Loads persisted project standards synchronously.
     */
    @Nullable
    public static ReviewStandards.ProjectStandards loadStandards(@NotNull Project project) {
        Path path = getStandardsFilePath(project);
        if (path == null || !Files.exists(path)) return null;

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            PersistedStandards ps = GSON.fromJson(json, PersistedStandards.class);
            if (ps == null) return null;

            ReviewStandards.PhpVersion phpVersion = safeEnum(ReviewStandards.PhpVersion.class, ps.phpVersion, ReviewStandards.PhpVersion.AUTO);
            ReviewStandards.CodingStandard codingStandard = safeEnum(ReviewStandards.CodingStandard.class, ps.codingStandard, ReviewStandards.CodingStandard.AUTO);
            ReviewStandards.Framework framework = safeEnum(ReviewStandards.Framework.class, ps.framework, ReviewStandards.Framework.AUTO);

            LOG.info("Loaded review standards from " + path);
            return new ReviewStandards.ProjectStandards(phpVersion, codingStandard, framework);
        } catch (Exception e) {
            LOG.warn("Failed to load review standards from " + path, e);
            return null;
        }
    }

    private static <E extends Enum<E>> E safeEnum(@NotNull Class<E> clazz, @Nullable String name, @NotNull E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(clazz, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ── Paths ────────────────────────────────────────────────────────

    @Nullable
    private static Path getFilePath(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, ".claude", "review-results.json");
    }

    @Nullable
    private static Path getStandardsFilePath(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return Path.of(basePath, ".claude", "review-standards.json");
    }

    // ── DTO Conversion ──────────────────────────────────────────────

    @NotNull
    private static PersistedReview toPersistedReview(@NotNull ReviewIssue.ReviewResult result) {
        PersistedReview review = new PersistedReview();
        review.savedAt = Instant.now().toString();
        review.issues = new ArrayList<>();

        for (ReviewIssue.Issue issue : result.issues()) {
            PersistedIssue pi = new PersistedIssue();
            pi.severity = issue.severity().name();
            pi.category = issue.category().name();
            pi.file = issue.file();
            pi.line = issue.line();
            pi.title = issue.title();
            pi.description = issue.description();
            pi.suggestion = issue.suggestion();
            pi.fixed = issue.isFixed();
            review.issues.add(pi);
        }

        return review;
    }

    @NotNull
    private static ReviewIssue.ReviewResult fromPersistedReview(@NotNull PersistedReview review) {
        List<ReviewIssue.Issue> issues = new ArrayList<>();

        for (PersistedIssue pi : review.issues) {
            ReviewIssue.Severity severity = ReviewIssue.Severity.fromString(pi.severity);
            ReviewIssue.Category category = ReviewIssue.Category.fromString(pi.category);

            if (severity == null) severity = ReviewIssue.Severity.INFO;
            if (category == null) category = ReviewIssue.Category.BUG;

            ReviewIssue.Issue issue = new ReviewIssue.Issue(
                    severity, category,
                    pi.file != null ? pi.file : "unknown",
                    pi.line,
                    pi.title != null ? pi.title : "Unknown",
                    pi.description != null ? pi.description : "",
                    pi.suggestion
            );
            issue.setFixed(pi.fixed);
            issues.add(issue);
        }

        return new ReviewIssue.ReviewResult(issues, false, null);
    }

    // ── DTO Classes (for Gson serialization) ────────────────────────

    static class PersistedReview {
        String savedAt;
        List<PersistedIssue> issues;
    }

    static class PersistedIssue {
        String severity;
        String category;
        String file;
        int line;
        String title;
        String description;
        String suggestion;
        boolean fixed;
    }

    static class PersistedStandards {
        String phpVersion;
        String codingStandard;
        String framework;
    }
}
