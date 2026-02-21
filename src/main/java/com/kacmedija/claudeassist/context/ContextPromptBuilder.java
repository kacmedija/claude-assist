package com.kacmedija.claudeassist.context;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Dynamically generates review instructions from the detected project context.
 * Replaces the PHP-specific StandardsPromptBuilder with a universal approach.
 */
public final class ContextPromptBuilder {

    private ContextPromptBuilder() {}

    /**
     * Builds review-oriented instructions from the detected context.
     *
     * @return instruction text for review prompts, or null if no context available
     */
    @Nullable
    public static String buildReviewInstructions(@Nullable ProjectContext.DetectedContext context) {
        if (context == null) return null;
        if (context.languages().isEmpty() && context.frameworks().isEmpty() && context.testFrameworks().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Project Standards ===\n\n");

        boolean hasContent = false;

        // Language + version instructions
        for (ProjectContext.LanguageInfo lang : context.languages()) {
            if (lang.version() != null) {
                sb.append("Review code considering features available in ")
                  .append(lang.name()).append(" ").append(lang.version()).append(".\n");
                sb.append("Suggest using newer ").append(lang.name())
                  .append(" features where they improve readability or correctness. ");
                sb.append("Flag usage of features not available in this version.\n\n");
                hasContent = true;
            }
        }

        // Framework instructions
        for (ProjectContext.FrameworkInfo fw : context.frameworks()) {
            sb.append("Apply ").append(fw.name()).append("-specific best practices");
            if (fw.version() != null) {
                sb.append(" (version ").append(fw.version()).append(")");
            }
            sb.append(".\n");
            hasContent = true;
        }

        if (!context.testFrameworks().isEmpty()) {
            sb.append("\n");
            for (ProjectContext.TestFramework tf : context.testFrameworks()) {
                sb.append("The project uses ").append(tf.name()).append(" for testing");
                if (tf.version() != null) {
                    sb.append(" (version ").append(tf.version()).append(")");
                }
                sb.append(". Consider test coverage and testing best practices.\n");
                hasContent = true;
            }
        }

        return hasContent ? sb.toString() : null;
    }

    /**
     * Builds a concise summary string for display in dialogs.
     */
    @NotNull
    public static String buildDisplaySummary(@Nullable ProjectContext.DetectedContext context) {
        if (context == null) return "No project context detected";

        StringBuilder sb = new StringBuilder();

        for (ProjectContext.LanguageInfo lang : context.languages()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(lang.name());
            if (lang.version() != null) sb.append(" ").append(lang.version());
        }

        for (ProjectContext.FrameworkInfo fw : context.frameworks()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(fw.name());
            if (fw.version() != null) sb.append(" ").append(fw.version());
        }

        for (ProjectContext.TestFramework tf : context.testFrameworks()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(tf.name());
        }

        return sb.isEmpty() ? "No project context detected" : "Detected: " + sb;
    }
}
