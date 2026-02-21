package com.kacmedija.claudeassist.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings UI for Claude Assist plugin.
 * Accessible via Settings → Tools → Claude Assist.
 */
public class ClaudeAssistConfigurable implements Configurable {

    private JBTextField claudePathField;
    private JBTextField wslDistroField;
    private JBCheckBox useWslCheckBox;
    private JBTextField modelField;
    private JBTextField maxContextFilesField;
    private JBCheckBox showDiffPreviewCheckBox;
    private JBCheckBox autoFixOnSaveCheckBox;
    private JBCheckBox includeOpenTabsCheckBox;
    private JBTextField customSystemPromptField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Claude Assist";
    }

    @Override
    public @Nullable JComponent createComponent() {
        ClaudeAssistSettings.State state = ClaudeAssistSettings.getInstance().getState();
        if (state == null) state = new ClaudeAssistSettings.State();

        claudePathField = new JBTextField(state.claudePath, 30);
        wslDistroField = new JBTextField(state.wslDistro, 20);
        useWslCheckBox = new JBCheckBox("Use WSL2", state.useWsl);
        modelField = new JBTextField(state.model, 20);
        maxContextFilesField = new JBTextField(String.valueOf(state.maxContextFiles), 5);
        showDiffPreviewCheckBox = new JBCheckBox("Show diff preview before applying edits", state.showDiffPreview);
        autoFixOnSaveCheckBox = new JBCheckBox("Auto-suggest fixes on save", state.autoFixOnSave);
        includeOpenTabsCheckBox = new JBCheckBox("Include open tabs as context", state.includeOpenTabs);
        customSystemPromptField = new JBTextField(state.customSystemPrompt, 40);

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Claude binary path:"), claudePathField)
            .addComponent(useWslCheckBox)
            .addLabeledComponent(new JBLabel("WSL distro (optional):"), wslDistroField)
            .addLabeledComponent(new JBLabel("Model override:"), modelField)
            .addLabeledComponent(new JBLabel("Max context files:"), maxContextFilesField)
            .addComponent(showDiffPreviewCheckBox)
            .addComponent(autoFixOnSaveCheckBox)
            .addComponent(includeOpenTabsCheckBox)
            .addLabeledComponent(new JBLabel("Custom system prompt:"), customSystemPromptField)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    @Override
    public boolean isModified() {
        ClaudeAssistSettings.State state = ClaudeAssistSettings.getInstance().getState();
        if (state == null) return true;
        return !claudePathField.getText().equals(state.claudePath)
            || !wslDistroField.getText().equals(state.wslDistro)
            || useWslCheckBox.isSelected() != state.useWsl
            || !modelField.getText().equals(state.model)
            || !maxContextFilesField.getText().equals(String.valueOf(state.maxContextFiles))
            || showDiffPreviewCheckBox.isSelected() != state.showDiffPreview
            || autoFixOnSaveCheckBox.isSelected() != state.autoFixOnSave
            || includeOpenTabsCheckBox.isSelected() != state.includeOpenTabs
            || !customSystemPromptField.getText().equals(state.customSystemPrompt);
    }

    @Override
    public void apply() {
        ClaudeAssistSettings.State state = ClaudeAssistSettings.getInstance().getState();
        if (state == null) state = new ClaudeAssistSettings.State();

        state.claudePath = claudePathField.getText();
        state.wslDistro = wslDistroField.getText();
        state.useWsl = useWslCheckBox.isSelected();
        state.model = modelField.getText();
        try {
            state.maxContextFiles = Integer.parseInt(maxContextFilesField.getText());
        } catch (NumberFormatException ignored) {}
        state.showDiffPreview = showDiffPreviewCheckBox.isSelected();
        state.autoFixOnSave = autoFixOnSaveCheckBox.isSelected();
        state.includeOpenTabs = includeOpenTabsCheckBox.isSelected();
        state.customSystemPrompt = customSystemPromptField.getText();
    }

    @Override
    public void reset() {
        ClaudeAssistSettings.State state = ClaudeAssistSettings.getInstance().getState();
        if (state == null) state = new ClaudeAssistSettings.State();

        claudePathField.setText(state.claudePath);
        wslDistroField.setText(state.wslDistro);
        useWslCheckBox.setSelected(state.useWsl);
        modelField.setText(state.model);
        maxContextFilesField.setText(String.valueOf(state.maxContextFiles));
        showDiffPreviewCheckBox.setSelected(state.showDiffPreview);
        autoFixOnSaveCheckBox.setSelected(state.autoFixOnSave);
        includeOpenTabsCheckBox.setSelected(state.includeOpenTabs);
        customSystemPromptField.setText(state.customSystemPrompt);
    }
}
