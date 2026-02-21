package com.kacmedija.claudeassist.projectsettings;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal dialog for managing allow/deny permissions in settings.local.json.
 */
public final class PermissionsDialog extends DialogWrapper {

    private final Project project;
    private final DefaultListModel<String> allowModel = new DefaultListModel<>();
    private final DefaultListModel<String> denyModel = new DefaultListModel<>();
    private JBList<String> allowList;
    private JBList<String> denyList;

    public PermissionsDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        setTitle("Permissions \u2014 settings.local.json");
        setOKButtonText("Save");
        init();
        loadPermissions();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(450, 400));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = JBUI.insets(4);

        // Allowed section
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1; gbc.weighty = 0;
        gbc.gridwidth = 2;
        panel.add(createSectionLabel("Allowed"), gbc);

        allowList = new JBList<>(allowModel);
        allowList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gbc.gridy = 1; gbc.weighty = 1;
        gbc.gridwidth = 1;
        panel.add(new JBScrollPane(allowList), gbc);

        gbc.gridx = 1; gbc.weightx = 0; gbc.weighty = 1;
        panel.add(createButtonPanel(allowModel, allowList), gbc);

        // Denied section
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 1; gbc.weighty = 0;
        gbc.gridwidth = 2;
        panel.add(createSectionLabel("Denied"), gbc);

        denyList = new JBList<>(denyModel);
        denyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gbc.gridy = 3; gbc.weighty = 1;
        gbc.gridwidth = 1;
        panel.add(new JBScrollPane(denyList), gbc);

        gbc.gridx = 1; gbc.weightx = 0; gbc.weighty = 1;
        panel.add(createButtonPanel(denyModel, denyList), gbc);

        return panel;
    }

    private JComponent createSectionLabel(@NotNull String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(JBUI.Borders.empty(8, 0, 2, 0));
        return label;
    }

    private JPanel createButtonPanel(@NotNull DefaultListModel<String> model, @NotNull JBList<String> list) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JButton addBtn = new JButton(AllIcons.General.Add);
        addBtn.setToolTipText("Add");
        addBtn.addActionListener(e -> {
            PermissionAddDialog dialog = new PermissionAddDialog(project);
            if (dialog.showAndGet()) {
                String perm = dialog.getPermission();
                if (!perm.isEmpty() && !contains(model, perm)) {
                    model.addElement(perm);
                }
            }
        });

        JButton removeBtn = new JButton(AllIcons.General.Remove);
        removeBtn.setToolTipText("Remove");
        removeBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) {
                model.remove(idx);
            }
        });

        panel.add(addBtn);
        panel.add(Box.createVerticalStrut(4));
        panel.add(removeBtn);
        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private void loadPermissions() {
        PermissionsService.PermissionsConfig config =
                PermissionsService.getInstance(project).readPermissions();

        allowModel.clear();
        for (String s : config.getAllow()) {
            allowModel.addElement(s);
        }

        denyModel.clear();
        for (String s : config.getDeny()) {
            denyModel.addElement(s);
        }
    }

    @Override
    protected void doOKAction() {
        List<String> allow = toList(allowModel);
        List<String> deny = toList(denyModel);

        PermissionsService.PermissionsConfig config =
                new PermissionsService.PermissionsConfig(allow, deny);
        PermissionsService.getInstance(project).savePermissions(config);

        super.doOKAction();
    }

    // ── Helpers ─────────────────────────────────────────────────

    @NotNull
    private static List<String> toList(@NotNull DefaultListModel<String> model) {
        List<String> list = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) {
            list.add(model.getElementAt(i));
        }
        return list;
    }

    private static boolean contains(@NotNull DefaultListModel<String> model, @NotNull String value) {
        for (int i = 0; i < model.size(); i++) {
            if (model.getElementAt(i).equals(value)) return true;
        }
        return false;
    }
}
