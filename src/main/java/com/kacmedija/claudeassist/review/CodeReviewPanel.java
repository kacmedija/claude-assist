package com.kacmedija.claudeassist.review;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Main panel for the Code Review tab. Assembles the toolbar, tree, detail panel,
 * and status bar into a cohesive UI.
 */
public final class CodeReviewPanel extends JPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(CodeReviewPanel.class);

    private final Project project;
    private final ReviewTreeModel treeModel;
    private final Tree tree;
    private final ReviewDetailPanel detailPanel;
    private final JBLabel statusBar;

    // Filter state
    private final Set<ReviewIssue.Severity> visibleSeverities = EnumSet.allOf(ReviewIssue.Severity.class);
    private final Set<ReviewIssue.Category> visibleCategories = EnumSet.allOf(ReviewIssue.Category.class);
    private boolean showFixedIssues = true;

    private ReviewIssue.ReviewResult currentResult;
    private final Consumer<ReviewIssue.ReviewResult> resultListener;
    private final Runnable stateListener;
    private final Consumer<CodeReviewService.ReviewProgress> progressListener;

    public CodeReviewPanel(@NotNull Project project, @NotNull Disposable parentDisposable) {
        super(new BorderLayout());
        this.project = project;
        Disposer.register(parentDisposable, this);

        // Initialize components
        treeModel = new ReviewTreeModel();
        tree = new Tree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ReviewTreeCellRenderer());

        detailPanel = new ReviewDetailPanel(project);
        statusBar = new JBLabel("Ready");
        statusBar.setBorder(JBUI.Borders.empty(4, 8));

        // Build layout
        add(createToolbar(), BorderLayout.NORTH);
        add(createSplitter(), BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Wire up listeners
        setupTreeListeners();

        resultListener = this::onReviewComplete;
        stateListener = this::updateStatusBar;
        progressListener = this::onProgressUpdate;

        CodeReviewService service = CodeReviewService.getInstance(project);
        service.addResultListener(resultListener);
        service.addStateListener(stateListener);
        service.addProgressListener(progressListener);

        // Load persisted results
        ReviewIssue.ReviewResult persisted = service.getLastResult();
        if (persisted != null) {
            onReviewComplete(persisted);
        }
    }

    // ── Toolbar ─────────────────────────────────────────────────────

    private JComponent createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        // Run Review
        group.add(new AnAction("Run Review", "Start a new code review", AllIcons.Actions.Execute) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                runReview();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!CodeReviewService.getInstance(project).isReviewing());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Stop
        group.add(new AnAction("Stop", "Stop running review", AllIcons.Actions.Suspend) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                CodeReviewService.getInstance(project).abort();
                statusBar.setText("Review stopped");
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(CodeReviewService.getInstance(project).isReviewing());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Clear
        group.add(new AnAction("Clear", "Clear review results", AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                clearResults();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        // Filter
        group.add(new AnAction("Filter", "Filter issues by severity and category", AllIcons.General.Filter) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                showFilterPopup(e);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("CodeReviewToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar.getComponent();
    }

    // ── Splitter ────────────────────────────────────────────────────

    private JComponent createSplitter() {
        JBSplitter splitter = new JBSplitter(false, 0.35f);

        JBScrollPane treeScroll = new JBScrollPane(tree);
        treeScroll.setBorder(JBUI.Borders.empty());

        splitter.setFirstComponent(treeScroll);
        splitter.setSecondComponent(detailPanel);

        return splitter;
    }

    // ── Tree Listeners ──────────────────────────────────────────────

    private void setupTreeListeners() {
        // Selection listener -> update detail panel
        tree.addTreeSelectionListener(e -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                detailPanel.showEmpty();
                return;
            }

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObject = node.getUserObject();

            if (userObject instanceof ReviewIssue.Issue issue) {
                detailPanel.showContent();
                detailPanel.showIssue(issue);
            } else {
                detailPanel.showEmpty();
            }
        });

        // Double-click -> navigate to file
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;

                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof ReviewIssue.Issue) {
                        detailPanel.navigateToIssue();
                    }
                }
            }
        });
    }

    // ── Actions ─────────────────────────────────────────────────────

    private void runReview() {
        ReviewConfigDialog dialog = new ReviewConfigDialog(project);
        if (!dialog.showAndGet()) return;

        ReviewIssue.Scope scope = dialog.getScope();
        Set<ReviewIssue.Category> categories = dialog.getSelectedCategories();
        ReviewIssue.Depth depth = dialog.getDepth();
        String customInstructions = dialog.getCustomInstructions();
        String standardsInstructions = dialog.getStandardsInstructions();

        statusBar.setText("Running review...");

        // Task.Backgroundable provides the background thread + progress bar.
        // CodeReviewService.runReview() is synchronous — it blocks this thread
        // until Claude responds, keeping the indicator alive the entire time.
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Claude Assist: Running Review", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                CodeReviewService.getInstance(project)
                        .runReview(scope, categories, depth, customInstructions, standardsInstructions, indicator);
            }
        });
    }

    private void clearResults() {
        currentResult = null;
        treeModel.clear();
        detailPanel.showEmpty();
        statusBar.setText("Ready");
        ReviewPersistence.delete(project);
    }

    private void showFilterPopup(@NotNull AnActionEvent e) {
        JPopupMenu popup = new JBPopupMenu();

        // Severity filters
        popup.add(createHeaderItem("Severity"));
        for (ReviewIssue.Severity sev : ReviewIssue.Severity.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(sev.getDisplayName(), visibleSeverities.contains(sev));
            item.addActionListener(ev -> {
                if (item.isSelected()) {
                    visibleSeverities.add(sev);
                } else {
                    visibleSeverities.remove(sev);
                }
                applyFilter();
            });
            popup.add(item);
        }

        popup.addSeparator();

        // Category filters
        popup.add(createHeaderItem("Category"));
        for (ReviewIssue.Category cat : ReviewIssue.Category.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(cat.getDisplayName(), visibleCategories.contains(cat));
            item.addActionListener(ev -> {
                if (item.isSelected()) {
                    visibleCategories.add(cat);
                } else {
                    visibleCategories.remove(cat);
                }
                applyFilter();
            });
            popup.add(item);
        }

        popup.addSeparator();

        // Show Fixed Issues toggle
        JCheckBoxMenuItem fixedItem = new JCheckBoxMenuItem("Show Fixed Issues", showFixedIssues);
        fixedItem.addActionListener(ev -> {
            showFixedIssues = fixedItem.isSelected();
            applyFilter();
        });
        popup.add(fixedItem);

        // Show popup at the filter button location
        Component source = e.getInputEvent() != null ? e.getInputEvent().getComponent() : this;
        popup.show(source, 0, source.getHeight());
    }

    @NotNull
    private JMenuItem createHeaderItem(@NotNull String text) {
        JMenuItem header = new JMenuItem(text);
        header.setEnabled(false);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        return header;
    }

    private void applyFilter() {
        if (currentResult != null) {
            // Save current selection
            ReviewIssue.Issue selectedIssue = getSelectedIssue();

            treeModel.setResult(currentResult, visibleSeverities, visibleCategories, showFixedIssues);
            expandAllNodes();
            updateStatusBar();

            // Restore selection
            restoreSelection(selectedIssue);
        }
    }

    // ── Review Callbacks ────────────────────────────────────────────

    private void onReviewComplete(@NotNull ReviewIssue.ReviewResult result) {
        // Save current selection before rebuild
        ReviewIssue.Issue selectedIssue = getSelectedIssue();

        currentResult = result;

        if (result.parseError()) {
            // Show raw response / error if parsing failed
            treeModel.clear();

            String rawText = result.rawResponse() != null ? result.rawResponse() : "(empty response)";
            statusBar.setText("Review failed — see detail panel");

            ReviewIssue.Issue fakeIssue = new ReviewIssue.Issue(
                    ReviewIssue.Severity.INFO,
                    ReviewIssue.Category.BUG,
                    "parse-error",
                    0,
                    "Failed to parse review response",
                    rawText,
                    "Check the IDE log (Help > Show Log) for details. "
                            + "Make sure 'claude' CLI is installed and authenticated."
            );
            detailPanel.showContent();
            detailPanel.showIssue(fakeIssue);
            return;
        }

        if (result.issues().isEmpty()) {
            treeModel.clear();
            detailPanel.showEmpty();
            statusBar.setText("No issues found");
            return;
        }

        treeModel.setResult(result, visibleSeverities, visibleCategories, showFixedIssues);
        expandAllNodes();
        updateStatusBar();

        // Restore selection
        restoreSelection(selectedIssue);
    }

    private void onProgressUpdate(@NotNull CodeReviewService.ReviewProgress progress) {
        statusBar.setText("Reviewing... " + progress.completedBatches() + "/"
                + progress.totalBatches() + " batches ("
                + progress.issueCount() + " issues found)");
    }

    private void expandAllNodes() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    /**
     * Returns the currently selected issue, or null if none selected.
     */
    @org.jetbrains.annotations.Nullable
    private ReviewIssue.Issue getSelectedIssue() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();
        return userObject instanceof ReviewIssue.Issue issue ? issue : null;
    }

    /**
     * Restores tree selection to the given issue after a tree rebuild.
     */
    private void restoreSelection(@org.jetbrains.annotations.Nullable ReviewIssue.Issue issue) {
        if (issue == null) return;
        TreePath path = treeModel.findIssuePath(issue);
        if (path != null) {
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private void updateStatusBar() {
        CodeReviewService service = CodeReviewService.getInstance(project);

        if (service.isReviewing()) {
            // During review, show progress if available
            CodeReviewService.ReviewProgress progress = service.getCurrentProgress();
            if (progress != null) {
                statusBar.setText("Reviewing... " + progress.completedBatches() + "/"
                        + progress.totalBatches() + " batches ("
                        + progress.issueCount() + " issues found)");
            } else {
                statusBar.setText("Running review...");
            }
            return;
        }

        if (currentResult == null || currentResult.issues().isEmpty()) {
            if (currentResult != null && !currentResult.parseError()) {
                statusBar.setText("No issues found");
            }
            // else keep current text (Ready / error message)
            return;
        }

        Map<ReviewIssue.Severity, Long> counts = currentResult.countBySeverity();
        long fixedCount = currentResult.issues().stream().filter(ReviewIssue.Issue::isFixed).count();
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ReviewIssue.Severity sev : ReviewIssue.Severity.values()) {
            long count = counts.getOrDefault(sev, 0L);
            if (count > 0) {
                if (!first) sb.append("  ·  ");
                sb.append(count).append(' ').append(sev.getDisplayName());
                first = false;
            }
        }

        if (fixedCount > 0) {
            sb.append("  ·  ").append(fixedCount).append(" Fixed");
        }

        statusBar.setText(sb.toString());
    }

    // ── Dispose ─────────────────────────────────────────────────────

    @Override
    public void dispose() {
        CodeReviewService service = CodeReviewService.getInstance(project);
        service.removeResultListener(resultListener);
        service.removeStateListener(stateListener);
        service.removeProgressListener(progressListener);
    }
}
