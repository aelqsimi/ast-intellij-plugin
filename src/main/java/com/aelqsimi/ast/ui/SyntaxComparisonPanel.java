package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.SyntaxComparison;
import com.aelqsimi.ast.model.SyntaxTreeNode;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class SyntaxComparisonPanel extends JPanel {
    private final Consumer<SyntaxTreeNode> navigator;
    private final Tree psiTree = new Tree();
    private final Tree uastTree = new Tree();
    private final Map<SyntaxTreeNode, TreePath> psiPaths = new IdentityHashMap<>();
    private final Map<SyntaxTreeNode, TreePath> uastPaths = new IdentityHashMap<>();
    private final JCheckBox hideTrivia = new JCheckBox(
            AstLensBundle.message("comparison.filter.trivia"),
            true
    );
    private final JCheckBox hidePunctuation = new JCheckBox(
            AstLensBundle.message("comparison.filter.punctuation"),
            true
    );
    private final JCheckBox hideImports = new JCheckBox(
            AstLensBundle.message("comparison.filter.imports")
    );
    private final JCheckBox hideSynthetic = new JCheckBox(
            AstLensBundle.message("comparison.filter.synthetic")
    );
    private SyntaxComparison currentComparison;
    private boolean synchronizing;

    public SyntaxComparisonPanel(Consumer<SyntaxTreeNode> navigator) {
        super(new BorderLayout());
        this.navigator = navigator;
        configureTree(psiTree);
        configureTree(uastTree);

        JPanel psiPanel = sidePanel(psiTree, AstLensBundle.message("comparison.psi"));
        JPanel uastPanel = sidePanel(uastTree, AstLensBundle.message("comparison.uast"));
        JSplitPane splitter = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, psiPanel, uastPanel);
        splitter.setResizeWeight(0.5);
        splitter.setContinuousLayout(true);

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        filters.add(hideTrivia);
        filters.add(hidePunctuation);
        filters.add(hideImports);
        filters.add(hideSynthetic);
        hideTrivia.addActionListener(event -> rebuildTrees());
        hidePunctuation.addActionListener(event -> rebuildTrees());
        hideImports.addActionListener(event -> rebuildTrees());
        hideSynthetic.addActionListener(event -> rebuildTrees());

        add(filters, BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        psiTree.addTreeSelectionListener(event -> synchronizeSelection(psiTree, uastTree, uastPaths));
        uastTree.addTreeSelectionListener(event -> synchronizeSelection(uastTree, psiTree, psiPaths));
    }

    public void setComparison(SyntaxComparison comparison) {
        currentComparison = comparison;
        rebuildTrees();
    }

    private void rebuildTrees() {
        int selectedOffset = selectedOffset();
        psiPaths.clear();
        uastPaths.clear();
        setTree(psiTree, currentComparison == null ? null : currentComparison.psiRoot(), psiPaths);
        setTree(uastTree, currentComparison == null ? null : currentComparison.uastRoot(), uastPaths);
        if (selectedOffset >= 0) {
            selectOffset(selectedOffset);
        }
    }

    public void selectOffset(int offset) {
        selectBestPath(psiTree, psiPaths, offset);
        selectBestPath(uastTree, uastPaths, offset);
    }

    private void configureTree(Tree tree) {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new SyntaxNodeRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                SyntaxTreeNode node = selectedNode(tree);
                if (node != null && node.startOffset() >= 0) {
                    navigator.accept(node);
                }
            }
        });
    }

    private JPanel sidePanel(Tree tree, String title) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER);
        return panel;
    }

    private void setTree(Tree tree, SyntaxTreeNode root, Map<SyntaxTreeNode, TreePath> paths) {
        DefaultMutableTreeNode swingRoot;
        if (root == null || isDropped(root)) {
            swingRoot = new DefaultMutableTreeNode(AstLensBundle.message("comparison.empty"));
        } else if (isPromoted(root)) {
            swingRoot = new DefaultMutableTreeNode(AstLensBundle.message("comparison.filtered.root"));
            appendVisibleChildren(root, swingRoot, new TreePath(swingRoot), paths);
        } else {
            swingRoot = buildSwingTree(root, null, paths);
        }
        tree.setModel(new DefaultTreeModel(swingRoot));
        tree.expandRow(0);
    }

    private DefaultMutableTreeNode buildSwingTree(
            SyntaxTreeNode node,
            TreePath parentPath,
            Map<SyntaxTreeNode, TreePath> paths
    ) {
        DefaultMutableTreeNode swingNode = new DefaultMutableTreeNode(node);
        TreePath path = parentPath == null
                ? new TreePath(swingNode)
                : parentPath.pathByAddingChild(swingNode);
        paths.put(node, path);
        appendVisibleChildren(node, swingNode, path, paths);
        return swingNode;
    }

    private void appendVisibleChildren(
            SyntaxTreeNode sourceParent,
            DefaultMutableTreeNode swingParent,
            TreePath parentPath,
            Map<SyntaxTreeNode, TreePath> paths
    ) {
        for (SyntaxTreeNode child : sourceParent.children()) {
            if (isDropped(child)) {
                continue;
            }
            if (isPromoted(child)) {
                appendVisibleChildren(child, swingParent, parentPath, paths);
                continue;
            }
            swingParent.add(buildSwingTree(child, parentPath, paths));
        }
    }

    private boolean isDropped(SyntaxTreeNode node) {
        return switch (node.category()) {
            case WHITESPACE, COMMENT -> hideTrivia.isSelected();
            case PUNCTUATION -> hidePunctuation.isSelected();
            case IMPORT -> hideImports.isSelected();
            default -> false;
        };
    }

    private boolean isPromoted(SyntaxTreeNode node) {
        return hideSynthetic.isSelected() && node.category() == SyntaxTreeNode.Category.SYNTHETIC;
    }

    private int selectedOffset() {
        SyntaxTreeNode psiSelection = selectedNode(psiTree);
        if (psiSelection != null && psiSelection.startOffset() >= 0) {
            return psiSelection.startOffset();
        }
        SyntaxTreeNode uastSelection = selectedNode(uastTree);
        return uastSelection == null ? -1 : uastSelection.startOffset();
    }

    private void synchronizeSelection(
            Tree sourceTree,
            Tree targetTree,
            Map<SyntaxTreeNode, TreePath> targetPaths
    ) {
        if (synchronizing) {
            return;
        }
        SyntaxTreeNode selected = selectedNode(sourceTree);
        if (selected == null || selected.startOffset() < 0) {
            return;
        }
        synchronizing = true;
        try {
            selectBestPath(targetTree, targetPaths, selected.startOffset());
        } finally {
            synchronizing = false;
        }
    }

    private void selectBestPath(Tree tree, Map<SyntaxTreeNode, TreePath> paths, int offset) {
        TreePath path = paths.entrySet().stream()
                .filter(entry -> entry.getKey().containsOffset(offset))
                .min(Comparator.comparingInt(entry ->
                        entry.getKey().endOffset() - entry.getKey().startOffset()))
                .map(Map.Entry::getValue)
                .orElse(null);
        if (path != null) {
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private static SyntaxTreeNode selectedNode(JTree tree) {
        Object selected = tree.getLastSelectedPathComponent();
        if (selected instanceof DefaultMutableTreeNode swingNode
                && swingNode.getUserObject() instanceof SyntaxTreeNode node) {
            return node;
        }
        return null;
    }

    private static final class SyntaxNodeRenderer extends DefaultTreeCellRenderer {
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
            if (value instanceof DefaultMutableTreeNode swingNode
                    && swingNode.getUserObject() instanceof SyntaxTreeNode node) {
                String range = node.startOffset() < 0
                        ? ""
                        : " [" + node.startOffset() + ".." + node.endOffset() + "]";
                String excerpt = node.excerpt().isBlank() ? "" : " — " + node.excerpt();
                setText(node.type() + range + excerpt);
                setToolTipText(node.excerpt().isBlank() ? node.type() : node.excerpt());
            }
            return this;
        }
    }
}
