package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.AstNode;
import com.intellij.openapi.progress.ProgressManager;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class AstGraphLayout {
    static final int NODE_WIDTH = 190;
    static final int NODE_HEIGHT = 56;
    private static final int HORIZONTAL_GAP = 30;
    private static final int VERTICAL_GAP = 80;
    private static final int MARGIN = 35;
    private static final AstGraphLayout EMPTY = new AstGraphLayout(List.of(), List.of(), new Dimension(500, 300));

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Dimension logicalSize;

    private AstGraphLayout(List<Node> nodes, List<Edge> edges, Dimension logicalSize) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.logicalSize = new Dimension(logicalSize);
    }

    static AstGraphLayout empty() {
        return EMPTY;
    }

    static AstGraphLayout calculate(AstNode root) {
        if (root == null) {
            return empty();
        }

        Builder builder = new Builder();
        builder.measure(root);
        builder.layout(root, MARGIN, 0, null);
        int width = builder.subtreeWidths.get(root) + MARGIN * 2;
        int maxDepth = builder.nodes.stream().mapToInt(Node::depth).max().orElse(0);
        int height = MARGIN * 2 + NODE_HEIGHT + maxDepth * (NODE_HEIGHT + VERTICAL_GAP);
        return new AstGraphLayout(builder.nodes, builder.edges, new Dimension(width, height));
    }

    List<Node> nodes() {
        return nodes;
    }

    List<Edge> edges() {
        return edges;
    }

    Dimension logicalSize() {
        return new Dimension(logicalSize);
    }

    record Node(AstNode node, int x, int y, int depth) {
        Rectangle bounds() {
            return new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT);
        }
    }

    record Edge(Node parent, Node child) {
        Rectangle2D bounds() {
            double startX = parent.x + NODE_WIDTH / 2.0;
            double startY = parent.y + NODE_HEIGHT;
            double endX = child.x + NODE_WIDTH / 2.0;
            double endY = child.y;
            double left = Math.min(startX, endX) - 8;
            double top = Math.min(startY, endY) - 8;
            return new Rectangle2D.Double(
                    left,
                    top,
                    Math.abs(endX - startX) + 16,
                    Math.abs(endY - startY) + 16
            );
        }
    }

    private static final class Builder {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<AstNode, Integer> subtreeWidths = new IdentityHashMap<>();

        private int measure(AstNode node) {
            ProgressManager.checkCanceled();
            int childrenWidth = node.children().stream().mapToInt(this::measure).sum();
            if (node.children().size() > 1) {
                childrenWidth += (node.children().size() - 1) * HORIZONTAL_GAP;
            }
            int width = Math.max(NODE_WIDTH, childrenWidth);
            subtreeWidths.put(node, width);
            return width;
        }

        private void layout(AstNode node, int left, int depth, Node parent) {
            ProgressManager.checkCanceled();
            int subtreeWidth = subtreeWidths.get(node);
            int x = left + (subtreeWidth - NODE_WIDTH) / 2;
            int y = MARGIN + depth * (NODE_HEIGHT + VERTICAL_GAP);
            Node visual = new Node(node, x, y, depth);
            nodes.add(visual);
            if (parent != null) {
                edges.add(new Edge(parent, visual));
            }

            int childrenWidth = node.children().stream().mapToInt(subtreeWidths::get).sum();
            if (node.children().size() > 1) {
                childrenWidth += (node.children().size() - 1) * HORIZONTAL_GAP;
            }
            int childLeft = left + (subtreeWidth - childrenWidth) / 2;
            for (AstNode child : node.children()) {
                layout(child, childLeft, depth + 1, visual);
                childLeft += subtreeWidths.get(child) + HORIZONTAL_GAP;
            }
        }
    }
}
