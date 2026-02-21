package com.kacmedija.claudeassist.agents;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Dialog for providing instructions to the AI for enhancing an agent prompt.
 */
public final class AgentEnhanceDialog extends DialogWrapper {

    private JBTextArea instructionArea;

    public AgentEnhanceDialog(@Nullable Project project) {
        super(project, false);
        setTitle("AI Enhance Agent");
        setOKButtonText("Enhance");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        instructionArea = new JBTextArea(4, 40);
        instructionArea.setLineWrap(true);
        instructionArea.setWrapStyleWord(true);
        instructionArea.getEmptyText().setText(
                "e.g., Make it more specific about error handling, Add examples..."
        );

        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Enhancement instructions:"), new JScrollPane(instructionArea))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (instructionArea.getText().trim().isEmpty()) {
            return new ValidationInfo("Please provide enhancement instructions", instructionArea);
        }
        return null;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return instructionArea;
    }

    @NotNull
    public String getInstruction() {
        return instructionArea.getText().trim();
    }
}
