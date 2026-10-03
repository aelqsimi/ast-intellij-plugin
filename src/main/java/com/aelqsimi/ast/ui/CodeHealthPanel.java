package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.CodeHealthIssue;
import com.aelqsimi.ast.model.CodeHealthReport;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class CodeHealthPanel extends JPanel {
    private final Consumer<CodeHealthIssue> navigator;
    private final Tree tree = new Tree();
    private final JLabel summary = new JLabel();

    public CodeHealthPanel(Consumer<CodeHealthIssue> navigator) {
        super(new BorderLayout());
        this.navigator = navigator;
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new IssueRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                CodeHealthIssue issue = selectedIssue();
                if (issue != null && issue.file() != null && issue.startOffset() >= 0) {
                    navigator.accept(issue);
                }
            }
        });
        add(summary, BorderLayout.NORTH);
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER);
        setReport(null);
    }

    public void setReport(CodeHealthReport report) {
        if (report == null) {
            summary.setText(AstLensBundle.message("health.empty"));
            tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(
                    AstLensBundle.message("health.empty")
            )));
            return;
        }

        summary.setText(AstLensBundle.message(
                "health.summary",
                report.issues().size(),
                report.classLineThreshold(),
                report.methodComplexityThreshold()
        ));
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                AstLensBundle.message("health.root", report.issues().size())
        );
        Map<CodeHealthIssue.Kind, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        for (CodeHealthIssue.Kind kind : CodeHealthIssue.Kind.values()) {
            DefaultMutableTreeNode group = new DefaultMutableTreeNode(new IssueGroup(kind));
            groups.put(kind, group);
            root.add(group);
        }
        report.issues().forEach(issue -> groups.get(issue.kind()).add(new DefaultMutableTreeNode(issue)));
        tree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private CodeHealthIssue selectedIssue() {
        Object selected = tree.getLastSelectedPathComponent();
        if (selected instanceof DefaultMutableTreeNode swingNode
                && swingNode.getUserObject() instanceof CodeHealthIssue issue) {
            return issue;
        }
        return null;
    }

    private static String issueLabel(CodeHealthIssue issue) {
        return switch (issue.kind()) {
            case LARGE_CLASS -> AstLensBundle.message(
                    "health.issue.large.class",
                    issue.symbol(),
                    issue.value(),
                    issue.threshold()
            );
            case COMPLEX_METHOD -> AstLensBundle.message(
                    "health.issue.complex.method",
                    issue.symbol(),
                    issue.value(),
                    issue.threshold()
            );
            case CLASS_DEPENDENCY_CYCLE, PACKAGE_DEPENDENCY_CYCLE -> AstLensBundle.message(
                    "health.issue.cycle",
                    String.join(" ↔ ", issue.participants())
            );
        };
    }

    private static String groupLabel(CodeHealthIssue.Kind kind) {
        return AstLensBundle.message("health.group." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }

    private record IssueGroup(CodeHealthIssue.Kind kind) {
    }

    private static final class IssueRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode swingNode) {
                if (swingNode.getUserObject() instanceof CodeHealthIssue issue) {
                    setText(issueLabel(issue));
                    setToolTipText(issue.symbol());
                } else if (swingNode.getUserObject() instanceof IssueGroup group) {
                    setText(groupLabel(group.kind()) + " (" + swingNode.getChildCount() + ")");
                }
            }
            return this;
        }
    }
}
