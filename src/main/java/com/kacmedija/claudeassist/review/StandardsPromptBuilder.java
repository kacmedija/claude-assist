package com.kacmedija.claudeassist.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds prompt instruction text from resolved PHP project standards.
 * The output is injected into the review prompt to guide Claude's analysis.
 *
 * @deprecated Use {@link com.kacmedija.claudeassist.context.ContextPromptBuilder} for universal project context.
 */
@Deprecated
public final class StandardsPromptBuilder {

    private StandardsPromptBuilder() {}

    /**
     * Builds the full standards instruction block for the review prompt.
     *
     * @return instruction text, or null if no meaningful instructions to add
     */
    @Nullable
    public static String buildInstructions(
            @NotNull ReviewStandards.PhpVersion phpVersion,
            @NotNull ReviewStandards.CodingStandard codingStandard,
            @NotNull ReviewStandards.Framework framework
    ) {
        // Nothing to add if everything is unresolved/none
        if (phpVersion == ReviewStandards.PhpVersion.AUTO
                && codingStandard == ReviewStandards.CodingStandard.NONE
                && framework == ReviewStandards.Framework.GENERIC) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== PHP PROJECT STANDARDS ===\n\n");

        appendPhpVersionInstructions(sb, phpVersion);
        appendCodingStandardInstructions(sb, codingStandard);
        appendFrameworkInstructions(sb, framework);

        String result = sb.toString().trim();
        return result.equals("=== PHP PROJECT STANDARDS ===") ? null : result + "\n";
    }

    // ── PHP Version Instructions (cumulative) ────────────────────────

    private static void appendPhpVersionInstructions(@NotNull StringBuilder sb, @NotNull ReviewStandards.PhpVersion version) {
        if (version == ReviewStandards.PhpVersion.AUTO) return;

        sb.append("PHP Version: ").append(version.getVersionNumber()).append("\n");
        sb.append("Review the code considering these PHP features are available:\n");

        // 8.0 features (always included for any 8.x version)
        sb.append("- PHP 8.0: Named arguments, match expressions, constructor property promotion, ");
        sb.append("union types, nullsafe operator (?->), str_contains/str_starts_with/str_ends_with\n");

        if (version.ordinal() >= ReviewStandards.PhpVersion.PHP_81.ordinal()) {
            sb.append("- PHP 8.1: Enums, readonly properties, fibers, intersection types, ");
            sb.append("never return type, first-class callable syntax, array_is_list()\n");
        }

        if (version.ordinal() >= ReviewStandards.PhpVersion.PHP_82.ordinal()) {
            sb.append("- PHP 8.2: Readonly classes, DNF types, true/false/null standalone types, ");
            sb.append("constants in traits, #[\\SensitiveParameter]\n");
        }

        if (version.ordinal() >= ReviewStandards.PhpVersion.PHP_83.ordinal()) {
            sb.append("- PHP 8.3: Typed class constants, json_validate(), #[\\Override] attribute, ");
            sb.append("dynamic class constant fetch, Randomizer additions\n");
        }

        if (version.ordinal() >= ReviewStandards.PhpVersion.PHP_84.ordinal()) {
            sb.append("- PHP 8.4: Property hooks, asymmetric visibility, #[\\Deprecated] attribute, ");
            sb.append("new without parentheses chaining, array_find()/array_any()/array_all()\n");
        }

        sb.append("Suggest using newer PHP features where they improve readability or correctness. ");
        sb.append("Flag usage of features not available in this PHP version.\n\n");
    }

    // ── Coding Standard Instructions ─────────────────────────────────

    private static void appendCodingStandardInstructions(@NotNull StringBuilder sb, @NotNull ReviewStandards.CodingStandard standard) {
        switch (standard) {
            case PER_2_0 -> {
                sb.append("Coding Standard: PER 2.0 (PER Coding Style 2.0)\n");
                sb.append("Enforce these rules:\n");
                sb.append("- Opening braces for classes/methods on the next line; control structures on the same line\n");
                sb.append("- 4 spaces indentation, no tabs\n");
                sb.append("- declare(strict_types=1) in every PHP file\n");
                sb.append("- Prefer short closures (fn() =>) where the body is a single expression\n");
                sb.append("- One use statement per import, grouped by type (classes, functions, constants)\n");
                sb.append("- No closing ?> tag in PHP-only files\n");
                sb.append("- Visibility declared on all class methods and properties\n");
                sb.append("- Return type declarations on all methods\n");
                sb.append("- One blank line after namespace, after use block, between methods\n");
                sb.append("- Parameter lists: if multi-line, one per line with closing parenthesis on its own line\n\n");
            }
            case PSR_12 -> {
                sb.append("Coding Standard: PSR-12\n");
                sb.append("Enforce these rules:\n");
                sb.append("- Opening braces for classes/methods on the next line; control structures on the same line\n");
                sb.append("- 4 spaces indentation, no tabs\n");
                sb.append("- declare(strict_types=1) required\n");
                sb.append("- One use statement per import\n");
                sb.append("- No closing ?> tag in PHP-only files\n");
                sb.append("- Visibility declared on all class methods and properties\n");
                sb.append("- Return type declarations recommended\n");
                sb.append("- One blank line after namespace, after use block\n\n");
            }
            case LARAVEL -> {
                sb.append("Coding Standard: Laravel Style\n");
                sb.append("Enforce these rules:\n");
                sb.append("- Follow Laravel's coding style conventions\n");
                sb.append("- Use string helpers (Str::) over raw PHP string functions where appropriate\n");
                sb.append("- Use collection methods over raw array functions\n");
                sb.append("- Early returns to reduce nesting\n");
                sb.append("- Descriptive variable and method names\n");
                sb.append("- declare(strict_types=1) recommended\n\n");
            }
            case NONE, AUTO -> {}
        }
    }

    // ── Framework Instructions ───────────────────────────────────────

    private static void appendFrameworkInstructions(@NotNull StringBuilder sb, @NotNull ReviewStandards.Framework framework) {
        switch (framework) {
            case LARAVEL -> {
                sb.append("Framework: Laravel\n");
                sb.append("Apply Laravel-specific review rules:\n");
                sb.append("- Use Form Request classes for validation instead of inline validation\n");
                sb.append("- Prefer Eloquent relationships and scopes over raw queries\n");
                sb.append("- Use resource controllers with proper method naming (index, show, store, update, destroy)\n");
                sb.append("- Middleware for cross-cutting concerns (auth, rate limiting, etc.)\n");
                sb.append("- Use config() and env() properly — env() only in config files, config() everywhere else\n");
                sb.append("- Avoid business logic in controllers — use Service classes or Actions\n");
                sb.append("- Use Laravel's built-in features: Events, Jobs, Notifications, Policies\n");
                sb.append("- Mass assignment protection: use $fillable or $guarded on models\n");
                sb.append("- Use route model binding where appropriate\n");
                sb.append("- Prefer typed properties and return types on all methods\n\n");
            }
            case SYMFONY -> {
                sb.append("Framework: Symfony\n");
                sb.append("Apply Symfony-specific review rules:\n");
                sb.append("- Use constructor injection for dependencies (autowiring)\n");
                sb.append("- Prefer Doctrine entities with proper mapping annotations/attributes\n");
                sb.append("- Use Event Subscribers over Event Listeners for multiple events\n");
                sb.append("- Proper use of Symfony Forms and validation constraints\n");
                sb.append("- Use Voter classes for authorization logic\n");
                sb.append("- Service definitions should follow Symfony DI best practices\n");
                sb.append("- Use #[Route] attributes for routing\n");
                sb.append("- Proper exception handling with Symfony's HTTP exceptions\n\n");
            }
            case GENERIC, AUTO -> {}
        }
    }
}
