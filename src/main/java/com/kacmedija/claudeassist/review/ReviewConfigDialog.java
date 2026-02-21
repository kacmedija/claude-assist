package com.kacmedija.claudeassist.review;

import com.kacmedija.claudeassist.context.ContextPromptBuilder;
import com.kacmedija.claudeassist.context.ProjectContext;
import com.kacmedija.claudeassist.context.ProjectContextService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.EnumSet;
import java.util.Set;

/**
 * Configuration dialog for code review settings:
 * scope, categories, depth, custom instructions, and auto-detected project standards.
 */
public final class ReviewConfigDialog extends DialogWrapper {

    private ComboBox<ReviewIssue.Scope> scopeCombo;
    private final JBCheckBox bugCheck = new JBCheckBox("Bug", true);
    private final JBCheckBox securityCheck = new JBCheckBox("Security", true);
    private final JBCheckBox performanceCheck = new JBCheckBox("Performance", true);
    private final JBCheckBox styleCheck = new JBCheckBox("Style", true);
    private ComboBox<ReviewIssue.Depth> depthCombo;
    private JBTextField customInstructionsField;

    // Auto-detected context display
    private JBTextArea detectedContextArea;

    private final @Nullable ProjectContext.DetectedContext detectedProjectContext;

    public ReviewConfigDialog(@Nullable Project project) {
        super(project, false);

        // Detect context before init() — fields used in createCenterPanel
        ProjectContext.DetectedContext ctx = null;
        if (project != null) {
            try {
                ctx = ProjectContextService.getInstance(project).getContext();
            } catch (Exception ignored) {}
        }
        detectedProjectContext = ctx;

        setTitle("Claude Assist — Code Review");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // Scope combo
        scopeCombo = new ComboBox<>(ReviewIssue.Scope.values());
        scopeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ReviewIssue.Scope scope) {
                    setText(scope.getDisplayName());
                }
                return this;
            }
        });

        // Categories panel
        JPanel categoriesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        categoriesPanel.add(bugCheck);
        categoriesPanel.add(securityCheck);
        categoriesPanel.add(performanceCheck);
        categoriesPanel.add(styleCheck);

        // Depth combo
        depthCombo = new ComboBox<>(ReviewIssue.Depth.values());
        depthCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ReviewIssue.Depth depth) {
                    setText(depth.getDisplayName());
                }
                return this;
            }
        });

        // Custom instructions
        customInstructionsField = new JBTextField(40);
        customInstructionsField.getEmptyText().setText("e.g., Focus on thread safety, Check error handling...");

        // ── Auto-detected project context ─────────────────────────────

        detectedContextArea = new JBTextArea(3, 40);
        detectedContextArea.setEditable(false);
        detectedContextArea.setLineWrap(true);
        detectedContextArea.setWrapStyleWord(true);
        detectedContextArea.setText(ContextPromptBuilder.buildDisplaySummary(detectedProjectContext));

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Scope:"), scopeCombo)
                .addLabeledComponent(new JBLabel("Categories:"), categoriesPanel)
                .addLabeledComponent(new JBLabel("Depth:"), depthCombo)
                .addLabeledComponent(new JBLabel("Custom instructions:"), customInstructionsField)
                .addComponent(new TitledSeparator("Project Standards"))
                .addLabeledComponent(new JBLabel("Detected:"), new JScrollPane(detectedContextArea))
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (!bugCheck.isSelected() && !securityCheck.isSelected()
                && !performanceCheck.isSelected() && !styleCheck.isSelected()) {
            return new ValidationInfo("At least one category must be selected", bugCheck);
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return scopeCombo;
    }

    // ── Getters ─────────────────────────────────────────────────────

    @NotNull
    public ReviewIssue.Scope getScope() {
        return (ReviewIssue.Scope) scopeCombo.getSelectedItem();
    }

    @NotNull
    public Set<ReviewIssue.Category> getSelectedCategories() {
        Set<ReviewIssue.Category> categories = EnumSet.noneOf(ReviewIssue.Category.class);
        if (bugCheck.isSelected()) categories.add(ReviewIssue.Category.BUG);
        if (securityCheck.isSelected()) categories.add(ReviewIssue.Category.SECURITY);
        if (performanceCheck.isSelected()) categories.add(ReviewIssue.Category.PERFORMANCE);
        if (styleCheck.isSelected()) categories.add(ReviewIssue.Category.STYLE);
        return categories;
    }

    @NotNull
    public ReviewIssue.Depth getDepth() {
        return (ReviewIssue.Depth) depthCombo.getSelectedItem();
    }

    @Nullable
    public String getCustomInstructions() {
        String text = customInstructionsField.getText().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Returns the auto-generated standards instructions based on detected project context.
     */
    @Nullable
    public String getStandardsInstructions() {
        return ContextPromptBuilder.buildReviewInstructions(detectedProjectContext);
    }

    /**
     * @deprecated Use {@link #getStandardsInstructions()} instead.
     */
    @Deprecated
    @NotNull
    public ReviewStandards.ProjectStandards getProjectStandards() {
        return new ReviewStandards.ProjectStandards(
                ReviewStandards.PhpVersion.AUTO,
                ReviewStandards.CodingStandard.AUTO,
                ReviewStandards.Framework.AUTO
        );
    }
}
