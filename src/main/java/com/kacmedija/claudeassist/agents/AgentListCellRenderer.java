package com.kacmedija.claudeassist.agents;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Custom cell renderer for the agent list, showing the agent name with a file icon.
 */
public final class AgentListCellRenderer extends ColoredListCellRenderer<AgentFile> {

    @Override
    protected void customizeCellRenderer(
            @NotNull JList<? extends AgentFile> list,
            AgentFile value,
            int index,
            boolean selected,
            boolean hasFocus
    ) {
        if (value == null) return;

        setIcon(AllIcons.FileTypes.Text);
        append(value.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        append("  .md", SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }
}
