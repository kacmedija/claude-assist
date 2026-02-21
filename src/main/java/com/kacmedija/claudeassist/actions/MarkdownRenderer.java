package com.kacmedija.claudeassist.actions;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;

/**
 * Converts markdown text to HTML suitable for rendering in JEditorPane (HTMLEditorKit).
 * Handles: fenced code blocks, headers, bold, italic, inline code, lists, paragraphs, links, hr.
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return wrapHtml("");
        }

        String[] lines = markdown.split("\n", -1);
        StringBuilder html = new StringBuilder();

        boolean inCodeBlock = false;
        StringBuilder codeBuffer = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;
        StringBuilder paragraph = new StringBuilder();

        for (String line : lines) {
            // ── Inside fenced code block ──
            if (inCodeBlock) {
                if (line.startsWith("```")) {
                    html.append("<pre>")
                        .append(escapeHtml(trimTrailingNewline(codeBuffer.toString())))
                        .append("</pre>");
                    codeBuffer.setLength(0);
                    inCodeBlock = false;
                } else {
                    codeBuffer.append(line).append("\n");
                }
                continue;
            }

            // ── Start fenced code block ──
            if (line.startsWith("```")) {
                flushParagraph(html, paragraph);
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                inCodeBlock = true;
                continue;
            }

            // ── Blank line ──
            if (line.trim().isEmpty()) {
                flushParagraph(html, paragraph);
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                continue;
            }

            // ── Horizontal rule ──
            if (line.matches("^-{3,}$") || line.matches("^\\*{3,}$") || line.matches("^_{3,}$")) {
                flushParagraph(html, paragraph);
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (inOl) { html.append("</ol>"); inOl = false; }
                html.append("<hr>");
                continue;
            }

            // ── Headers ──
            if (line.startsWith("#### ")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl); inUl = false; inOl = false;
                html.append("<h4>").append(processInline(line.substring(5))).append("</h4>");
                continue;
            }
            if (line.startsWith("### ")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl); inUl = false; inOl = false;
                html.append("<h3>").append(processInline(line.substring(4))).append("</h3>");
                continue;
            }
            if (line.startsWith("## ")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl); inUl = false; inOl = false;
                html.append("<h2>").append(processInline(line.substring(3))).append("</h2>");
                continue;
            }
            if (line.startsWith("# ")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl); inUl = false; inOl = false;
                html.append("<h1>").append(processInline(line.substring(2))).append("</h1>");
                continue;
            }

            // ── Unordered list ──
            if (line.matches("^\\s*[-*+]\\s+.*")) {
                flushParagraph(html, paragraph);
                if (inOl) { html.append("</ol>"); inOl = false; }
                if (!inUl) { html.append("<ul>"); inUl = true; }
                String content = line.replaceFirst("^\\s*[-*+]\\s+", "");
                html.append("<li>").append(processInline(content)).append("</li>");
                continue;
            }

            // ── Ordered list ──
            if (line.matches("^\\s*\\d+\\.\\s+.*")) {
                flushParagraph(html, paragraph);
                if (inUl) { html.append("</ul>"); inUl = false; }
                if (!inOl) { html.append("<ol>"); inOl = true; }
                String content = line.replaceFirst("^\\s*\\d+\\.\\s+", "");
                html.append("<li>").append(processInline(content)).append("</li>");
                continue;
            }

            // ── Regular text → accumulate into paragraph ──
            if (paragraph.length() > 0) {
                paragraph.append(" ");
            }
            paragraph.append(line);
        }

        // Flush remaining state
        flushParagraph(html, paragraph);
        if (inCodeBlock) {
            html.append("<pre>")
                .append(escapeHtml(trimTrailingNewline(codeBuffer.toString())))
                .append("</pre>");
        }
        if (inUl) html.append("</ul>");
        if (inOl) html.append("</ol>");

        return wrapHtml(html.toString());
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(processInline(paragraph.toString())).append("</p>");
            paragraph.setLength(0);
        }
    }

    private static void closeLists(StringBuilder html, boolean inUl, boolean inOl) {
        if (inUl) html.append("</ul>");
        if (inOl) html.append("</ol>");
    }

    /**
     * Process inline markdown elements. Order matters:
     * 1. Escape HTML entities
     * 2. Inline code (protect contents from further processing)
     * 3. Links
     * 4. Bold
     * 5. Italic
     */
    private static String processInline(String text) {
        text = escapeHtml(text);

        // Inline code: `code` → <code>code</code>
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");

        // Links: [text](url) → <a href="url">text</a>
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");

        // Bold: **text** → <b>text</b>
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");

        // Italic: *text* → <i>text</i>
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<i>$1</i>");

        return text;
    }

    private static String escapeHtml(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String trimTrailingNewline(String text) {
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    // ── HTML Document Wrapper ────────────────────────────────────

    private static String wrapHtml(String bodyContent) {
        Color bg = UIUtil.getPanelBackground();
        Color fg = UIUtil.getLabelForeground();

        Color codeBg = JBColor.isBright()
            ? new Color(245, 245, 245)
            : new Color(43, 43, 43);
        Color borderColor = JBColor.isBright()
            ? new Color(220, 220, 220)
            : new Color(60, 60, 60);
        Color hrColor = JBColor.isBright()
            ? new Color(200, 200, 200)
            : new Color(80, 80, 80);
        Color linkColor = JBColor.isBright()
            ? new Color(0, 82, 204)
            : new Color(104, 151, 227);

        Font labelFont = UIManager.getFont("Label.font");
        int fontSize = labelFont != null ? labelFont.getSize() : 13;
        String fontFamily = labelFont != null ? labelFont.getFamily() : "sans-serif";

        return "<html><head><style>" +
            "body { font-family: '" + fontFamily + "', sans-serif; " +
            "  font-size: " + fontSize + "px; " +
            "  color: " + hex(fg) + "; " +
            "  background: " + hex(bg) + "; " +
            "  margin: 8px; }" +
            "h1 { font-size: " + (fontSize + 6) + "px; margin: 12px 0 6px 0; }" +
            "h2 { font-size: " + (fontSize + 4) + "px; margin: 10px 0 5px 0; }" +
            "h3 { font-size: " + (fontSize + 2) + "px; margin: 8px 0 4px 0; }" +
            "h4 { font-size: " + (fontSize + 1) + "px; margin: 6px 0 3px 0; }" +
            "p { margin: 4px 0; }" +
            "pre { background: " + hex(codeBg) + "; " +
            "  font-family: 'JetBrains Mono', monospace; " +
            "  font-size: " + (fontSize - 1) + "px; " +
            "  padding: 8px; margin: 6px 0; " +
            "  border: 1px solid " + hex(borderColor) + "; }" +
            "code { background: " + hex(codeBg) + "; " +
            "  font-family: 'JetBrains Mono', monospace; " +
            "  font-size: " + (fontSize - 1) + "px; " +
            "  padding: 1px 3px; }" +
            "ul, ol { margin: 4px 0 4px 20px; padding: 0; }" +
            "li { margin: 2px 0; }" +
            "hr { border: none; border-top: 1px solid " + hex(hrColor) + "; margin: 8px 0; }" +
            "a { color: " + hex(linkColor) + "; }" +
            "</style></head><body>" +
            bodyContent +
            "</body></html>";
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
