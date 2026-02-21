package com.kacmedija.claudeassist.agents;

import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Left-side panel containing the scrollable list of agents.
 */
public final class AgentListPanel extends JPanel {

    private final DefaultListModel<AgentFile> listModel;
    private final JBList<AgentFile> list;

    public AgentListPanel() {
        super(new java.awt.BorderLayout());

        listModel = new DefaultListModel<>();
        list = new JBList<>(listModel);
        list.setCellRenderer(new AgentListCellRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setEmptyText("No agents found");

        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setBorder(JBUI.Borders.empty());
        add(scrollPane, java.awt.BorderLayout.CENTER);
    }

    /**
     * Updates the list with the given agents, preserving the current selection if possible.
     */
    public void setAgents(@NotNull List<AgentFile> agents) {
        AgentFile selected = list.getSelectedValue();
        String selectedName = selected != null ? selected.getName() : null;

        listModel.clear();
        for (AgentFile agent : agents) {
            listModel.addElement(agent);
        }

        // Restore selection
        if (selectedName != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).getName().equals(selectedName)) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /**
     * Returns the currently selected agent, or null if none.
     */
    @Nullable
    public AgentFile getSelectedAgent() {
        return list.getSelectedValue();
    }

    /**
     * Selects an agent by name.
     */
    public void selectAgent(@NotNull String name) {
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).getName().equals(name)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                break;
            }
        }
    }

    /**
     * Registers a selection change listener.
     */
    public void addSelectionListener(@NotNull Consumer<AgentFile> listener) {
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                listener.accept(list.getSelectedValue());
            }
        });
    }

    public int getAgentCount() {
        return listModel.size();
    }
}
