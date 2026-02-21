package com.kacmedija.claudeassist.review;

import com.google.gson.*;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Claude's JSON response into structured ReviewIssue.Issue objects.
 * Uses a 4-level fallback strategy to handle various response formats.
 */
public final class ReviewJsonParser {

    private static final Logger LOG = Logger.getInstance(ReviewJsonParser.class);
    private static final Gson GSON = new Gson();

    private ReviewJsonParser() {}

    /**
     * Parses the raw Claude response text into a ReviewResult.
     * Tries 4 levels of fallback parsing before giving up.
     */
    @NotNull
    public static ReviewIssue.ReviewResult parse(@NotNull String responseText) {
        String trimmed = responseText.trim();
        if (trimmed.isEmpty()) {
            return new ReviewIssue.ReviewResult(List.of(), true, responseText);
        }

        // Level 1: Direct JSON array parse
        List<ReviewIssue.Issue> issues = tryParseJsonArray(trimmed);
        if (issues != null) {
            return new ReviewIssue.ReviewResult(issues, false, null);
        }

        // Level 2: Remove markdown code fences
        String stripped = stripMarkdownFences(trimmed);
        if (!stripped.equals(trimmed)) {
            issues = tryParseJsonArray(stripped);
            if (issues != null) {
                return new ReviewIssue.ReviewResult(issues, false, null);
            }
        }

        // Level 3: Find first '[' and last ']' in the text
        issues = tryExtractJsonArray(trimmed);
        if (issues != null) {
            return new ReviewIssue.ReviewResult(issues, false, null);
        }

        // Level 4: Line-level partial recovery
        issues = tryPartialRecovery(trimmed);
        if (issues != null && !issues.isEmpty()) {
            return new ReviewIssue.ReviewResult(issues, false, null);
        }

        // All levels failed
        LOG.warn("All JSON parse levels failed for review response");
        return new ReviewIssue.ReviewResult(List.of(), true, responseText);
    }

    /**
     * Level 1: Try to parse the entire string as a JSON array.
     */
    private static List<ReviewIssue.Issue> tryParseJsonArray(@NotNull String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonArray()) {
                return parseIssueArray(element.getAsJsonArray());
            }
            // If it's a JSON object with an "issues" key
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("issues") && obj.get("issues").isJsonArray()) {
                    return parseIssueArray(obj.getAsJsonArray("issues"));
                }
            }
        } catch (JsonSyntaxException e) {
            // Not valid JSON
        }
        return null;
    }

    /**
     * Level 2: Strip markdown code fences (```json ... ```)
     */
    @NotNull
    private static String stripMarkdownFences(@NotNull String text) {
        String result = text;

        // Remove opening fence with optional language
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1);
            }
        }

        // Remove closing fence
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }

        return result.trim();
    }

    /**
     * Level 3: Find the first '[' and last ']' and try to parse that substring.
     */
    private static List<ReviewIssue.Issue> tryExtractJsonArray(@NotNull String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');

        if (start >= 0 && end > start) {
            String candidate = text.substring(start, end + 1);
            return tryParseJsonArray(candidate);
        }
        return null;
    }

    /**
     * Level 4: Try to parse individual JSON objects line by line or by finding { } blocks.
     */
    private static List<ReviewIssue.Issue> tryPartialRecovery(@NotNull String text) {
        List<ReviewIssue.Issue> issues = new ArrayList<>();
        int depth = 0;
        int objStart = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String candidate = text.substring(objStart, i + 1);
                    try {
                        JsonElement element = JsonParser.parseString(candidate);
                        if (element.isJsonObject()) {
                            ReviewIssue.Issue issue = parseIssueObject(element.getAsJsonObject());
                            if (issue != null) {
                                issues.add(issue);
                            }
                        }
                    } catch (JsonSyntaxException ignored) {
                        // Skip malformed objects
                    }
                    objStart = -1;
                }
            }
        }

        return issues.isEmpty() ? null : issues;
    }

    // ── JSON to Issue Mapping ────────────────────────────────────

    @NotNull
    private static List<ReviewIssue.Issue> parseIssueArray(@NotNull JsonArray array) {
        List<ReviewIssue.Issue> issues = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                ReviewIssue.Issue issue = parseIssueObject(element.getAsJsonObject());
                if (issue != null) {
                    issues.add(issue);
                }
            }
        }
        return issues;
    }

    private static ReviewIssue.Issue parseIssueObject(@NotNull JsonObject obj) {
        try {
            ReviewIssue.Severity severity = ReviewIssue.Severity.fromString(getStr(obj, "severity"));
            ReviewIssue.Category category = ReviewIssue.Category.fromString(getStr(obj, "category"));
            String file = getStr(obj, "file");
            int line = getInt(obj, "line");
            String title = getStr(obj, "title");
            String description = getStr(obj, "description");
            String suggestion = getStr(obj, "suggestion");

            // Defaults for missing fields
            if (severity == null) severity = ReviewIssue.Severity.INFO;
            if (category == null) category = ReviewIssue.Category.BUG;
            if (file == null) file = "unknown";
            if (title == null) title = description != null ? truncate(description, 60) : "Unknown issue";
            if (description == null) description = title;

            return new ReviewIssue.Issue(severity, category, file, line, title, description, suggestion);
        } catch (Exception e) {
            LOG.debug("Failed to parse issue object: " + obj, e);
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static int getInt(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @NotNull
    private static String truncate(@NotNull String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }
}
