package com.kacmedija.claudeassist.services;

import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extracts IDE code style settings for a given language and formats them
 * as a prompt block for Claude.
 */
public final class CodeStyleExtractor {

    private CodeStyleExtractor() {}

    /**
     * Extracts code style settings for the given language and returns a formatted prompt block.
     *
     * @return formatted code style block, or null if no relevant settings found
     */
    @Nullable
    public static String extractForLanguage(@NotNull Project project, @Nullable Language language) {
        if (language == null) return null;

        CodeStyleSettings settings;
        try {
            settings = CodeStyleSettingsManager.getInstance(project).getCurrentSettings();
        } catch (Exception e) {
            return null;
        }

        CommonCodeStyleSettings langSettings = settings.getCommonSettings(language);
        CommonCodeStyleSettings.IndentOptions indentOptions = langSettings.getIndentOptions();

        StringBuilder sb = new StringBuilder();
        sb.append("=== IDE Code Style Settings ===\n");

        boolean hasContent = false;

        // Indent type and size
        if (indentOptions != null) {
            String indentType = indentOptions.USE_TAB_CHARACTER ? "tabs" : "spaces";
            sb.append("- Indentation: ").append(indentOptions.INDENT_SIZE).append(" ").append(indentType).append("\n");
            hasContent = true;

            if (indentOptions.USE_TAB_CHARACTER && indentOptions.TAB_SIZE != indentOptions.INDENT_SIZE) {
                sb.append("- Tab size: ").append(indentOptions.TAB_SIZE).append("\n");
            }

            if (indentOptions.CONTINUATION_INDENT_SIZE != indentOptions.INDENT_SIZE * 2
                    && indentOptions.CONTINUATION_INDENT_SIZE > 0) {
                sb.append("- Continuation indent: ").append(indentOptions.CONTINUATION_INDENT_SIZE).append("\n");
            }
        }

        // Brace placement
        int classBrace = langSettings.CLASS_BRACE_STYLE;
        int methodBrace = langSettings.METHOD_BRACE_STYLE;

        if (classBrace == CommonCodeStyleSettings.NEXT_LINE || methodBrace == CommonCodeStyleSettings.NEXT_LINE) {
            String classStyle = braceStyleName(classBrace);
            String methodStyle = braceStyleName(methodBrace);
            if (classStyle.equals(methodStyle)) {
                sb.append("- Brace placement: ").append(classStyle).append("\n");
            } else {
                sb.append("- Class brace placement: ").append(classStyle).append("\n");
                sb.append("- Method brace placement: ").append(methodStyle).append("\n");
            }
            hasContent = true;
        }

        // Right margin / line length
        int rightMargin = settings.getRightMargin(language);
        if (rightMargin > 0 && rightMargin != 120) {
            sb.append("- Line length limit: ").append(rightMargin).append(" characters\n");
            hasContent = true;
        } else if (rightMargin == 120) {
            sb.append("- Line length limit: 120 characters\n");
            hasContent = true;
        }

        return hasContent ? sb.toString() : null;
    }

    @NotNull
    private static String braceStyleName(int style) {
        return switch (style) {
            case CommonCodeStyleSettings.END_OF_LINE -> "end of line";
            case CommonCodeStyleSettings.NEXT_LINE -> "next line";
            case CommonCodeStyleSettings.NEXT_LINE_SHIFTED -> "next line shifted";
            case CommonCodeStyleSettings.NEXT_LINE_SHIFTED2 -> "next line shifted2";
            default -> "end of line";
        };
    }
}
