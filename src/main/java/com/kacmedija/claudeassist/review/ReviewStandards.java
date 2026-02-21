package com.kacmedija.claudeassist.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Data model for PHP project standards: version, coding standard, and framework.
 * Used by the review dialog, detector, and prompt builder.
 *
 * @deprecated Use {@link com.kacmedija.claudeassist.context.ProjectContext} for universal project context detection.
 */
@Deprecated
public final class ReviewStandards {

    private ReviewStandards() {}

    // ── Enums ────────────────────────────────────────────────────────

    public enum PhpVersion {
        AUTO("Auto-detect"),
        PHP_80("PHP 8.0"),
        PHP_81("PHP 8.1"),
        PHP_82("PHP 8.2"),
        PHP_83("PHP 8.3"),
        PHP_84("PHP 8.4");

        private final String displayName;

        PhpVersion(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * Returns the numeric version string (e.g. "8.4"), or null for AUTO.
         */
        @Nullable
        public String getVersionNumber() {
            return switch (this) {
                case AUTO -> null;
                case PHP_80 -> "8.0";
                case PHP_81 -> "8.1";
                case PHP_82 -> "8.2";
                case PHP_83 -> "8.3";
                case PHP_84 -> "8.4";
            };
        }

        /**
         * Resolves a version constraint string (e.g. "8.4", "8.1") to a PhpVersion enum.
         */
        @Nullable
        public static PhpVersion fromConstraint(@Nullable String constraint) {
            if (constraint == null) return null;
            return switch (constraint) {
                case "8.0" -> PHP_80;
                case "8.1" -> PHP_81;
                case "8.2" -> PHP_82;
                case "8.3" -> PHP_83;
                case "8.4" -> PHP_84;
                default -> null;
            };
        }
    }

    public enum CodingStandard {
        AUTO("Auto-detect"),
        PER_2_0("PER 2.0"),
        PSR_12("PSR-12"),
        LARAVEL("Laravel"),
        NONE("None");

        private final String displayName;

        CodingStandard(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum Framework {
        AUTO("Auto-detect"),
        LARAVEL("Laravel"),
        SYMFONY("Symfony"),
        GENERIC("Generic PHP");

        private final String displayName;

        Framework(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // ── Records ──────────────────────────────────────────────────────

    /**
     * User-selected standards from the dialog. AUTO values need resolving via DetectedContext.
     */
    public record ProjectStandards(
            @NotNull PhpVersion phpVersion,
            @NotNull CodingStandard codingStandard,
            @NotNull Framework framework
    ) {
        /**
         * Resolves AUTO values using the detected context and builds prompt instructions.
         */
        @NotNull
        public ResolvedStandards resolve(@Nullable DetectedContext ctx) {
            // Resolve PHP version
            PhpVersion resolvedPhp = phpVersion;
            if (resolvedPhp == PhpVersion.AUTO && ctx != null) {
                PhpVersion detected = PhpVersion.fromConstraint(ctx.resolvedPhpVersion());
                resolvedPhp = detected != null ? detected : PhpVersion.AUTO;
            }

            // Resolve framework
            Framework resolvedFramework = framework;
            if (resolvedFramework == Framework.AUTO && ctx != null) {
                resolvedFramework = ctx.detectedFramework() != null ? ctx.detectedFramework() : Framework.GENERIC;
            }

            // Resolve coding standard
            CodingStandard resolvedStandard = codingStandard;
            if (resolvedStandard == CodingStandard.AUTO) {
                if (resolvedFramework == Framework.LARAVEL) {
                    resolvedStandard = CodingStandard.LARAVEL;
                } else {
                    resolvedStandard = CodingStandard.PER_2_0;
                }
            }

            // Build prompt instructions
            String instructions = StandardsPromptBuilder.buildInstructions(
                    resolvedPhp, resolvedStandard, resolvedFramework
            );

            return new ResolvedStandards(resolvedPhp, resolvedStandard, resolvedFramework, instructions);
        }
    }

    /**
     * Result of parsing composer.json — raw detected values.
     */
    public record DetectedContext(
            @Nullable String phpVersionConstraint,
            @Nullable String resolvedPhpVersion,
            @Nullable Framework detectedFramework,
            @Nullable String frameworkVersion
    ) {}

    /**
     * Fully resolved standards with generated prompt instructions.
     */
    public record ResolvedStandards(
            @NotNull PhpVersion phpVersion,
            @NotNull CodingStandard codingStandard,
            @NotNull Framework framework,
            @Nullable String promptInstructions
    ) {}
}
