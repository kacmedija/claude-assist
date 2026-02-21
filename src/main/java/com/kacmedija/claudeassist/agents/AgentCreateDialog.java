package com.kacmedija.claudeassist.agents;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.regex.Pattern;

/**
 * Dialog for creating a new agent prompt file.
 */
public final class AgentCreateDialog extends DialogWrapper {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*$");

    private JBTextField nameField;
    private JBTextArea contentArea;

    public AgentCreateDialog(@Nullable Project project) {
        super(project, false);
        setTitle("Create New Agent");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        nameField = new JBTextField(30);
        nameField.getEmptyText().setText("e.g., code-reviewer, test-writer");

        contentArea = new JBTextArea(8, 40);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setText("# Agent Name\n\nDescribe the agent's role and instructions here.\n");

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Agent name:"), nameField)
                .addLabeledComponent(new JBLabel("Initial content:"), new JScrollPane(contentArea))
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Agent name is required", nameField);
        }
        if (!VALID_NAME.matcher(name).matches()) {
            return new ValidationInfo(
                    "Agent name must start with a letter or digit and contain only letters, digits, hyphens, and underscores",
                    nameField
            );
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @NotNull
    public String getAgentName() {
        return nameField.getText().trim();
    }

    @NotNull
    public String getAgentContent() {
        return contentArea.getText();
    }
}
