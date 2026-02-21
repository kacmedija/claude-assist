package com.kacmedija.claudeassist.projectsettings;

import com.kacmedija.claudeassist.agents.AgentVersion;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor panel for CLAUDE.md with a title bar and version bar.
 * Always shows the editor (no CardLayout) — empty text area when file doesn't exist.
 */
public final class ClaudeMdEditorPanel extends JPanel {

    private final JBLabel titleLabel;
    private final JBTextArea textArea;
    private JComboBox<AgentVersion> versionCombo;
    private JButton restoreButton;
    private JButton compareButton;

    private @Nullable String savedContent;
    private boolean modified = false;
    private boolean fileExists = false;

    // Callbacks
    private @Nullable Runnable onModifiedChanged;
    private @Nullable Consumer<AgentVersion> onRestoreVersion;
    private @Nullable Consumer<AgentVersion> onCompareVersion;

    public ClaudeMdEditorPanel(@NotNull Project project) {
        super(new BorderLayout());

        // Title bar
        titleLabel = new JBLabel("CLAUDE.md");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setBorder(JBUI.Borders.empty(6, 8));
        add(titleLabel, BorderLayout.NORTH);

        // Text editor
        textArea = new JBTextArea();
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkModified(); }
        });

        JBScrollPane editorScroll = new JBScrollPane(textArea);
        editorScroll.setBorder(JBUI.Borders.empty());
        add(editorScroll, BorderLayout.CENTER);

        // Version bar
        add(createVersionBar(), BorderLayout.SOUTH);
    }

    private JPanel createVersionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0));

        bar.add(new JBLabel("Versions:"));

        versionCombo = new JComboBox<>();
        versionCombo.setPrototypeDisplayValue(null);
        versionCombo.setPreferredSize(new Dimension(260, 24));
        bar.add(versionCombo);

        restoreButton = new JButton("Restore", AllIcons.Actions.Rollback);
        restoreButton.setEnabled(false);
        restoreButton.addActionListener(e -> {
            AgentVersion version = (AgentVersion) versionCombo.getSelectedItem();
            if (version != null && onRestoreVersion != null) {
                onRestoreVersion.accept(version);
            }
        });
        bar.add(restoreButton);

        compareButton = new JButton("Compare", AllIcons.Actions.Diff);
        compareButton.setEnabled(false);
        compareButton.addActionListener(e -> {
            AgentVersion version = (AgentVersion) versionCombo.getSelectedItem();
            if (version != null && onCompareVersion != null) {
                onCompareVersion.accept(version);
            }
        });
        bar.add(compareButton);

        versionCombo.addActionListener(e -> {
            boolean hasVersion = versionCombo.getSelectedItem() != null;
            restoreButton.setEnabled(hasVersion);
            compareButton.setEnabled(hasVersion);
        });

        return bar;
    }

    // ── Content Loading ─────────────────────────────────────────

    /**
     * Loads content into the editor.
     *
     * @param content    The file content (empty string if file doesn't exist)
     * @param exists     Whether CLAUDE.md currently exists on disk
     */
    public void loadContent(@NotNull String content, boolean exists) {
        this.fileExists = exists;
        this.savedContent = content;
        textArea.setText(content);
        textArea.setCaretPosition(0);
        modified = false;
        updateTitle();
    }

    /**
     * Refreshes the version dropdown.
     */
    public void refreshVersions(@NotNull List<AgentVersion> versions) {
        versionCombo.removeAllItems();
        for (AgentVersion version : versions) {
            versionCombo.addItem(version);
        }
        boolean hasVersions = !versions.isEmpty();
        restoreButton.setEnabled(hasVersions);
        compareButton.setEnabled(hasVersions);
    }

    // ── Content Access ──────────────────────────────────────────

    @NotNull
    public String getContent() {
        return textArea.getText();
    }

    public void setContent(@NotNull String content) {
        textArea.setText(content);
        textArea.setCaretPosition(0);
    }

    public void markSaved() {
        savedContent = textArea.getText();
        fileExists = true;
        modified = false;
        updateTitle();
    }

    public boolean isModified() {
        return modified;
    }

    // ── Callbacks ───────────────────────────────────────────────

    public void setOnModifiedChanged(@Nullable Runnable callback) {
        this.onModifiedChanged = callback;
    }

    public void setOnRestoreVersion(@Nullable Consumer<AgentVersion> callback) {
        this.onRestoreVersion = callback;
    }

    public void setOnCompareVersion(@Nullable Consumer<AgentVersion> callback) {
        this.onCompareVersion = callback;
    }

    // ── Internal ────────────────────────────────────────────────

    private void checkModified() {
        boolean wasModified = modified;
        modified = savedContent != null && !savedContent.equals(textArea.getText());
        if (modified != wasModified) {
            updateTitle();
            if (onModifiedChanged != null) {
                onModifiedChanged.run();
            }
        }
    }

    private void updateTitle() {
        String title = "CLAUDE.md";
        if (!fileExists && (savedContent == null || savedContent.isEmpty())) {
            title += " (new)";
        }
        if (modified) {
            title += " *";
        }
        titleLabel.setText(title);
    }
}
