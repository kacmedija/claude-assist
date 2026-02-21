package com.kacmedija.claudeassist.projectsettings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Small dialog for adding a single permission rule.
 * Provides common templates and a text field for custom entries.
 */
public final class PermissionAddDialog extends DialogWrapper {

    private static final String CUSTOM = "Custom...";

    private static final String[] TEMPLATES = {
            "Read",
            "Write",
            "WebFetch",
            "WebSearch",
            "Bash(npm run build)",
            "Bash(npm test)",
            "Bash(npm run lint)",
            "Bash(git *)",
            "Bash(docker *)",
            CUSTOM
    };

    private JComboBox<String> templateCombo;
    private JBTextField textField;

    public PermissionAddDialog(@Nullable Project project) {
        super(project, false);
        setTitle("Add Permission");
        setOKButtonText("Add");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        templateCombo = new JComboBox<>(TEMPLATES);
        textField = new JBTextField();
        textField.setEnabled(false);

        templateCombo.addActionListener(e -> {
            String selected = (String) templateCombo.getSelectedItem();
            if (CUSTOM.equals(selected)) {
                textField.setEnabled(true);
                textField.setText("");
                textField.requestFocusInWindow();
            } else if (selected != null && selected.contains("(")) {
                // Parameterized template — enable for editing
                textField.setEnabled(true);
                textField.setText(selected);
            } else {
                // Simple template — no editing needed
                textField.setEnabled(false);
                textField.setText(selected != null ? selected : "");
            }
        });

        // Initialize text field with first template
        textField.setText(TEMPLATES[0]);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Template:"), templateCombo)
                .addLabeledComponent(new JBLabel("Permission:"), textField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (getPermission().isEmpty()) {
            return new ValidationInfo("Permission cannot be empty", textField);
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return templateCombo;
    }

    /**
     * Returns the final permission string.
     */
    @NotNull
    public String getPermission() {
        String selected = (String) templateCombo.getSelectedItem();
        if (CUSTOM.equals(selected) || (selected != null && selected.contains("("))) {
            return textField.getText().trim();
        }
        return selected != null ? selected.trim() : "";
    }
}
