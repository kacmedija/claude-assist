package com.kacmedija.claudeassist.review;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Data model for code review issues, including enums for severity, category, scope, and depth,
 * plus records for individual issues and aggregated results.
 */
public final class ReviewIssue {

    private ReviewIssue() {}

    // ── Enums ──────────────────────────────────────────────────────

    public enum Severity {
        CRITICAL("Critical", AllIcons.General.Error, new JBColor(new Color(0xCC0000), new Color(0xFF6B6B))),
        WARNING("Warning", AllIcons.General.Warning, new JBColor(new Color(0xCC8800), new Color(0xFFCC00))),
        INFO("Info", AllIcons.General.Information, new JBColor(new Color(0x0066CC), new Color(0x6CB8FF))),
        SUGGESTION("Suggestion", AllIcons.Actions.IntentionBulb, new JBColor(new Color(0x008800), new Color(0x6BCB77)));

        private final String displayName;
        private final Icon icon;
        private final Color color;

        Severity(String displayName, Icon icon, Color color) {
            this.displayName = displayName;
            this.icon = icon;
            this.color = color;
        }

        public String getDisplayName() { return displayName; }
        public Icon getIcon() { return icon; }
        public Color getColor() { return color; }

        @Nullable
        public static Severity fromString(@Nullable String value) {
            if (value == null) return null;
            try {
                return valueOf(value.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                return switch (value.toLowerCase().trim()) {
                    case "error", "critical", "high" -> CRITICAL;
                    case "warning", "warn", "medium" -> WARNING;
                    case "info", "information", "low" -> INFO;
                    case "suggestion", "hint", "style" -> SUGGESTION;
                    default -> null;
                };
            }
        }
    }

    public enum Category {
        BUG("Bug"),
        SECURITY("Security"),
        PERFORMANCE("Performance"),
        STYLE("Style");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }

        @Nullable
        public static Category fromString(@Nullable String value) {
            if (value == null) return null;
            try {
                return valueOf(value.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                return switch (value.toLowerCase().trim()) {
                    case "bug", "error", "defect" -> BUG;
                    case "security", "vulnerability", "sec" -> SECURITY;
                    case "performance", "perf", "optimization" -> PERFORMANCE;
                    case "style", "formatting", "convention", "readability" -> STYLE;
                    default -> null;
                };
            }
        }
    }

    public enum Scope {
        CURRENT_FILE("Current File"),
        SELECTION("Selection"),
        CHANGED_FILES("Changed Files (Git)"),
        BRANCH_CHANGES("Branch Changes (Git)");

        private final String displayName;

        Scope(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    public enum Depth {
        QUICK("Quick", "Focus on obvious issues, bugs, and security vulnerabilities. Be concise."),
        THOROUGH("Thorough", "Perform a deep analysis including edge cases, performance implications, error handling, and architectural concerns.");

        private final String displayName;
        private final String promptInstruction;

        Depth(String displayName, String promptInstruction) {
            this.displayName = displayName;
            this.promptInstruction = promptInstruction;
        }

        public String getDisplayName() { return displayName; }
        public String getPromptInstruction() { return promptInstruction; }
    }

    // ── Issue Class ─────────────────────────────────────────────────

    public static final class Issue {
        private final @NotNull Severity severity;
        private final @NotNull Category category;
        private final @NotNull String file;
        private final int line;
        private final @NotNull String title;
        private final @NotNull String description;
        private final @Nullable String suggestion;
        private volatile boolean fixing;
        private volatile boolean fixed;

        public Issue(
                @NotNull Severity severity,
                @NotNull Category category,
                @NotNull String file,
                int line,
                @NotNull String title,
                @NotNull String description,
                @Nullable String suggestion
        ) {
            this.severity = severity;
            this.category = category;
            this.file = file;
            this.line = line;
            this.title = title;
            this.description = description;
            this.suggestion = suggestion;
        }

        // Record-style accessors (zero changes elsewhere)
        public @NotNull Severity severity() { return severity; }
        public @NotNull Category category() { return category; }
        public @NotNull String file() { return file; }
        public int line() { return line; }
        public @NotNull String title() { return title; }
        public @NotNull String description() { return description; }
        public @Nullable String suggestion() { return suggestion; }

        public boolean isFixing() { return fixing; }
        public void setFixing(boolean fixing) { this.fixing = fixing; }

        public boolean isFixed() { return fixed; }
        public void setFixed(boolean fixed) { this.fixed = fixed; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Issue that)) return false;
            return line == that.line
                    && severity == that.severity
                    && category == that.category
                    && file.equals(that.file)
                    && title.equals(that.title);
        }

        @Override
        public int hashCode() {
            return Objects.hash(severity, category, file, line, title);
        }

        @Override
        public String toString() {
            return "Issue{" + severity + " " + category + " " + file + ":" + line + " " + title + "}";
        }
    }

    // ── ReviewResult Record ─────────────────────────────────────────

    public record ReviewResult(
            @NotNull List<Issue> issues,
            boolean parseError,
            @Nullable String rawResponse
    ) {
        public Map<Severity, Long> countBySeverity() {
            return issues.stream()
                    .collect(Collectors.groupingBy(Issue::severity, Collectors.counting()));
        }

        public Map<Severity, List<Issue>> groupBySeverity() {
            return issues.stream()
                    .collect(Collectors.groupingBy(Issue::severity,
                            () -> new EnumMap<>(Severity.class),
                            Collectors.toList()));
        }

        public List<Issue> filter(@Nullable Set<Severity> severities, @Nullable Set<Category> categories) {
            return filter(severities, categories, true);
        }

        public List<Issue> filter(@Nullable Set<Severity> severities, @Nullable Set<Category> categories, boolean showFixed) {
            return issues.stream()
                    .filter(i -> severities == null || severities.contains(i.severity()))
                    .filter(i -> categories == null || categories.contains(i.category()))
                    .filter(i -> showFixed || !i.isFixed())
                    .collect(Collectors.toList());
        }
    }
}
