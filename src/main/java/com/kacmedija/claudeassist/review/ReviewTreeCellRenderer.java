package com.kacmedija.claudeassist.review;

import com.intellij.icons.AllIcons;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Custom tree cell renderer for the review issue tree.
 * Renders severity nodes, file nodes, and issue nodes with appropriate icons and colors.
 * Fixed issues are shown with a green check icon, strikeout text, and gray color.
 */
public final class ReviewTreeCellRenderer extends ColoredTreeCellRenderer {

    private static final SimpleTextAttributes FIXING_ATTRS = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY);
    private static final SimpleTextAttributes FIXED_ATTRS = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY);
    private static final SimpleTextAttributes FIXED_LINE_ATTRS = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY);

    @Override
    public void customizeCellRenderer(
            @NotNull JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
    ) {
        if (!(value instanceof DefaultMutableTreeNode node)) return;
        Object userObject = node.getUserObject();

        if (userObject instanceof ReviewTreeModel.SeverityNodeData sevData) {
            renderSeverityNode(sevData);
        } else if (userObject instanceof ReviewTreeModel.FileNodeData fileData) {
            renderFileNode(fileData);
        } else if (userObject instanceof ReviewIssue.Issue issue) {
            renderIssueNode(issue);
        } else if (userObject instanceof String text) {
            append(text);
        }
    }

    private void renderSeverityNode(@NotNull ReviewTreeModel.SeverityNodeData data) {
        setIcon(data.severity().getIcon());
        append(data.severity().getDisplayName(),
                new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, data.severity().getColor()));
        String countText = data.fixedCount() > 0
                ? " (" + data.count() + ", " + data.fixedCount() + " fixed)"
                : " (" + data.count() + ")";
        append(countText,
                new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, data.severity().getColor()));
    }

    private void renderFileNode(@NotNull ReviewTreeModel.FileNodeData data) {
        setIcon(AllIcons.FileTypes.Any_type);
        append(data.fileName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
    }

    private void renderIssueNode(@NotNull ReviewIssue.Issue issue) {
        if (issue.isFixing()) {
            setIcon(new AnimatedIcon.Default());
            append(issue.title(), FIXING_ATTRS);
            if (issue.line() > 0) {
                append(":" + issue.line(), FIXING_ATTRS);
            }
            append(" (fixing...)", FIXING_ATTRS);
        } else if (issue.isFixed()) {
            setIcon(AllIcons.General.InspectionsOK);
            append(issue.title(), FIXED_ATTRS);
            if (issue.line() > 0) {
                append(":" + issue.line(), FIXED_LINE_ATTRS);
            }
        } else {
            setIcon(issue.severity().getIcon());
            append(issue.title(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (issue.line() > 0) {
                append(":" + issue.line(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }
    }
}
