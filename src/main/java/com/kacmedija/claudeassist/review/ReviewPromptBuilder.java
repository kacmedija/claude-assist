package com.kacmedija.claudeassist.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Builds the prompt sent to Claude for code review, including JSON schema instructions,
 * category filters, depth settings, and file contents.
 */
public final class ReviewPromptBuilder {

    private ReviewPromptBuilder() {}

    private static final String JSON_SCHEMA = """
            [
              {
                "severity": "CRITICAL | WARNING | INFO | SUGGESTION",
                "category": "BUG | SECURITY | PERFORMANCE | STYLE",
                "file": "filename.java",
                "line": 42,
                "title": "Short issue title",
                "description": "Detailed explanation of the issue",
                "suggestion": "How to fix it (code or text)"
              }
            ]""";

    private static final String JSON_EXAMPLE = """
            [
              {
                "severity": "CRITICAL",
                "category": "SECURITY",
                "file": "UserController.java",
                "line": 55,
                "title": "SQL Injection vulnerability",
                "description": "The query uses string concatenation with user input, which is vulnerable to SQL injection attacks.",
                "suggestion": "Use PreparedStatement with parameterized queries instead of string concatenation."
              },
              {
                "severity": "WARNING",
                "category": "PERFORMANCE",
                "file": "DataService.java",
                "line": 120,
                "title": "N+1 query in loop",
                "description": "Database query inside a for-each loop causes N+1 query problem.",
                "suggestion": "Batch the queries or use a JOIN to fetch all related data at once."
              }
            ]""";

    /**
     * Builds the full prompt for Claude code review (backward-compatible, no standards).
     */
    @NotNull
    public static String build(
            @NotNull Map<String, String> files,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions
    ) {
        return build(files, categories, depth, customInstructions, null);
    }

    /**
     * Builds the full prompt for Claude code review.
     *
     * @param files                 Map of filename -> file content
     * @param categories            Categories to check for
     * @param depth                 Review depth (quick vs thorough)
     * @param customInstructions    Optional additional instructions
     * @param standardsInstructions Optional project standards instructions block
     * @return The assembled prompt string
     */
    @NotNull
    public static String build(
            @NotNull Map<String, String> files,
            @NotNull Set<ReviewIssue.Category> categories,
            @NotNull ReviewIssue.Depth depth,
            @Nullable String customInstructions,
            @Nullable String standardsInstructions
    ) {
        StringBuilder prompt = new StringBuilder();

        // Core instruction
        prompt.append("You are an expert code reviewer. Analyze the following code and return issues found.\n\n");

        // JSON-only output instruction
        prompt.append("IMPORTANT: Return ONLY a valid JSON array. No markdown fences, no explanations, no text before or after the JSON.\n\n");

        // JSON schema
        prompt.append("Use this exact JSON schema:\n");
        prompt.append(JSON_SCHEMA).append("\n\n");

        // Example
        prompt.append("Example output:\n");
        prompt.append(JSON_EXAMPLE).append("\n\n");

        // Category filter
        prompt.append("Focus on these categories: ");
        prompt.append(String.join(", ", categories.stream().map(ReviewIssue.Category::getDisplayName).toList()));
        prompt.append(".\n\n");

        // Depth instruction
        prompt.append("Review depth: ").append(depth.getDisplayName()).append("\n");
        prompt.append(depth.getPromptInstruction()).append("\n\n");

        // Project standards
        if (standardsInstructions != null && !standardsInstructions.isBlank()) {
            prompt.append(standardsInstructions.trim()).append("\n\n");
        }

        // Custom instructions
        if (customInstructions != null && !customInstructions.isBlank()) {
            prompt.append("Additional instructions: ").append(customInstructions.trim()).append("\n\n");
        }

        // File contents
        prompt.append("=== FILES TO REVIEW ===\n\n");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();

            // Truncate very large files
            if (content.length() > 100_000) {
                content = content.substring(0, 50_000) + "\n... [truncated, file too large] ...";
            }

            prompt.append("=== FILE: ").append(fileName).append(" ===\n");
            prompt.append(content).append("\n\n");
        }

        // Closing instruction
        prompt.append("If no issues are found, return an empty JSON array: []\n");
        prompt.append("Remember: Return ONLY the JSON array, nothing else.\n");

        return prompt.toString();
    }
}
