package com.kacmedija.claudeassist.review;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.*;

/**
 * Tree model for the review issue tree. Builds a tree structure:
 * Root
 *   |- Severity (Critical, Warning, ...)
 *       |- File (App.java)
 *           |- Issue (SQL Injection:42)
 */
public final class ReviewTreeModel extends DefaultTreeModel {

    public ReviewTreeModel() {
        super(new DefaultMutableTreeNode("Review Results"));
    }

    // ── Typed Node Data ────────────────────────────────────────────

    public record SeverityNodeData(@NotNull ReviewIssue.Severity severity, int count, int fixedCount) {}

    public record FileNodeData(@NotNull String fileName) {}

    // ── Tree Building ──────────────────────────────────────────────

    /**
     * Rebuilds the entire tree from the given result, applying severity and category filters.
     * Backward-compatible overload — shows fixed issues by default.
     */
    public void setResult(
            @Nullable ReviewIssue.ReviewResult result,
            @Nullable Set<ReviewIssue.Severity> visibleSeverities,
            @Nullable Set<ReviewIssue.Category> visibleCategories
    ) {
        setResult(result, visibleSeverities, visibleCategories, true);
    }

    /**
     * Rebuilds the entire tree from the given result, applying severity, category, and fixed filters.
     */
    public void setResult(
            @Nullable ReviewIssue.ReviewResult result,
            @Nullable Set<ReviewIssue.Severity> visibleSeverities,
            @Nullable Set<ReviewIssue.Category> visibleCategories,
            boolean showFixed
    ) {
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) getRoot();
        rootNode.removeAllChildren();

        if (result == null || result.issues().isEmpty()) {
            reload();
            return;
        }

        // Filter issues
        List<ReviewIssue.Issue> filtered = result.filter(visibleSeverities, visibleCategories, showFixed);

        // Group by severity, then by file
        Map<ReviewIssue.Severity, Map<String, List<ReviewIssue.Issue>>> grouped = new LinkedHashMap<>();

        // Ensure severity order: CRITICAL -> WARNING -> INFO -> SUGGESTION
        for (ReviewIssue.Severity sev : ReviewIssue.Severity.values()) {
            grouped.put(sev, new LinkedHashMap<>());
        }

        for (ReviewIssue.Issue issue : filtered) {
            grouped.get(issue.severity())
                    .computeIfAbsent(issue.file(), k -> new ArrayList<>())
                    .add(issue);
        }

        // Build tree nodes
        for (Map.Entry<ReviewIssue.Severity, Map<String, List<ReviewIssue.Issue>>> sevEntry : grouped.entrySet()) {
            Map<String, List<ReviewIssue.Issue>> fileMap = sevEntry.getValue();
            if (fileMap.isEmpty()) continue;

            int totalCount = fileMap.values().stream().mapToInt(List::size).sum();
            int fixedCount = (int) fileMap.values().stream()
                    .flatMap(List::stream)
                    .filter(ReviewIssue.Issue::isFixed)
                    .count();
            SeverityNodeData sevData = new SeverityNodeData(sevEntry.getKey(), totalCount, fixedCount);
            DefaultMutableTreeNode sevNode = new DefaultMutableTreeNode(sevData);

            for (Map.Entry<String, List<ReviewIssue.Issue>> fileEntry : fileMap.entrySet()) {
                FileNodeData fileData = new FileNodeData(fileEntry.getKey());
                DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileData);

                for (ReviewIssue.Issue issue : fileEntry.getValue()) {
                    fileNode.add(new DefaultMutableTreeNode(issue));
                }

                sevNode.add(fileNode);
            }

            rootNode.add(sevNode);
        }

        reload();
    }

    /**
     * Finds the TreePath to the given issue in the current tree, or null if not found.
     * Useful for restoring selection after tree rebuild.
     */
    @Nullable
    public TreePath findIssuePath(@Nullable ReviewIssue.Issue issue) {
        if (issue == null) return null;

        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) getRoot();
        for (int s = 0; s < rootNode.getChildCount(); s++) {
            DefaultMutableTreeNode sevNode = (DefaultMutableTreeNode) rootNode.getChildAt(s);
            for (int f = 0; f < sevNode.getChildCount(); f++) {
                DefaultMutableTreeNode fileNode = (DefaultMutableTreeNode) sevNode.getChildAt(f);
                for (int i = 0; i < fileNode.getChildCount(); i++) {
                    DefaultMutableTreeNode issueNode = (DefaultMutableTreeNode) fileNode.getChildAt(i);
                    if (issue.equals(issueNode.getUserObject())) {
                        return new TreePath(issueNode.getPath());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Clears all nodes from the tree.
     */
    public void clear() {
        ((DefaultMutableTreeNode) getRoot()).removeAllChildren();
        reload();
    }
}
