package com.kacmedija.claudeassist.context;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Universal data model describing detected project context:
 * languages, frameworks, and test frameworks.
 */
public final class ProjectContext {

    private ProjectContext() {}

    /**
     * A detected programming language with version info.
     */
    public record LanguageInfo(
            @NotNull String name,
            @Nullable String version,
            @NotNull String source
    ) {}

    /**
     * A detected framework or library.
     */
    public record FrameworkInfo(
            @NotNull String name,
            @Nullable String version,
            @NotNull String source
    ) {}

    /**
     * A detected test framework.
     */
    public record TestFramework(
            @NotNull String name,
            @Nullable String version,
            @NotNull String source
    ) {}

    /**
     * Aggregated detected context for a project.
     */
    public record DetectedContext(
            @NotNull List<LanguageInfo> languages,
            @NotNull List<FrameworkInfo> frameworks,
            @NotNull List<TestFramework> testFrameworks
    ) {
        /**
         * Generates a prompt block summarizing the detected context.
         *
         * @return prompt text block, or null if nothing was detected
         */
        @Nullable
        public String toPromptBlock() {
            if (languages.isEmpty() && frameworks.isEmpty() && testFrameworks.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Project Context ===\n");

            for (LanguageInfo lang : languages) {
                sb.append("Language: ").append(lang.name());
                if (lang.version() != null) {
                    sb.append(" ").append(lang.version());
                }
                sb.append(" (from ").append(lang.source()).append(")\n");
            }

            for (FrameworkInfo fw : frameworks) {
                sb.append("Framework: ").append(fw.name());
                if (fw.version() != null) {
                    sb.append(" ").append(fw.version());
                }
                sb.append(" (from ").append(fw.source()).append(")\n");
            }

            for (TestFramework tf : testFrameworks) {
                sb.append("Test framework: ").append(tf.name());
                if (tf.version() != null) {
                    sb.append(" ").append(tf.version());
                }
                sb.append(" (from ").append(tf.source()).append(")\n");
            }

            return sb.toString();
        }

        /**
         * Returns the first detected test framework name, or null.
         */
        @Nullable
        public String primaryTestFramework() {
            return testFrameworks.isEmpty() ? null : testFrameworks.get(0).name();
        }

        /**
         * Returns the first detected framework name, or null.
         */
        @Nullable
        public String primaryFramework() {
            return frameworks.isEmpty() ? null : frameworks.get(0).name();
        }

        /**
         * Returns the first detected language name, or null.
         */
        @Nullable
        public String primaryLanguage() {
            return languages.isEmpty() ? null : languages.get(0).name();
        }
    }
}
