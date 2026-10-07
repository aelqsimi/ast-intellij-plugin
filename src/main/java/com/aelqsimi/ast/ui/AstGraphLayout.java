package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.AstNode;
import com.intellij.openapi.progress.ProgressManager;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class AstGraphLayout {
    static final int NODE_WIDTH = 190;
    static final int NODE_HEIGHT = 56;
    private static final int HORIZONTAL_GAP = 20;
    private static final int VERTICAL_GAP = 60;
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

    private static Map<AstNode, Point> compactPositions(List<AstNode> neighbors) {
        Map<AstNode, Point> positions = new IdentityHashMap<>();
        int index = 0;
        int ring = 1;
        while (index < neighbors.size()) {
            int radius = 170 + (ring - 1) * 125;
            int capacity = Math.max(4, (int) Math.floor(2 * Math.PI * radius / (NODE_WIDTH + 30.0)));
            int count = Math.min(capacity, neighbors.size() - index);
            for (int position = 0; position < count; position++) {
                double angle = -Math.PI / 2 + 2 * Math.PI * position / count;
                positions.put(neighbors.get(index++), new Point(
                        (int) Math.round(Math.cos(angle) * radius),
                        (int) Math.round(Math.sin(angle) * radius)
                ));
            }
            ring++;
        }
        return positions;
    }

    private static Rectangle boundsOf(List<Node> nodes, Set<AstNode> focusedModels) {
        Rectangle bounds = null;
        for (Node node : nodes) {
            if (!focusedModels.contains(node.node())) {
                continue;
            }
            bounds = bounds == null ? node.bounds() : bounds.union(node.bounds());
        }
        if (bounds == null) {
            return new Rectangle();
        }
        bounds.grow(45, 45);
        return bounds;
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

    AstGraphLayout moveNode(AstNode model, int x, int y) {
        Map<AstNode, Node> movedNodesByModel = new IdentityHashMap<>();
        List<Node> movedNodes = new ArrayList<>(nodes.size());
        boolean found = false;
        for (Node node : nodes) {
            Node moved = node.node() == model
                    ? new Node(node.node(), x, y, node.depth())
                    : node;
            found |= node.node() == model;
            movedNodes.add(moved);
            movedNodesByModel.put(moved.node(), moved);
        }
        if (!found) {
            return this;
        }

        List<Edge> movedEdges = edges.stream()
                .map(edge -> new Edge(
                        movedNodesByModel.get(edge.parent().node()),
                        movedNodesByModel.get(edge.child().node())
                ))
                .toList();
        int width = movedNodes.stream()
                .mapToInt(node -> node.x() + NODE_WIDTH + MARGIN)
                .max()
                .orElse(logicalSize.width);
        int height = movedNodes.stream()
                .mapToInt(node -> node.y() + NODE_HEIGHT + MARGIN)
                .max()
                .orElse(logicalSize.height);
        return new AstGraphLayout(
                movedNodes,
                movedEdges,
                new Dimension(Math.max(logicalSize.width, width), Math.max(logicalSize.height, height))
        );
    }

    Focus focus(AstNode selectedNode) {
        Node selected = nodes.stream()
                .filter(node -> node.node() == selectedNode)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return new Focus(this, Set.of(), new Rectangle());
        }

        Set<AstNode> focusedModels = Collections.newSetFromMap(new IdentityHashMap<>());
        focusedModels.add(selectedNode);
        List<AstNode> neighbors = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.parent().node() == selectedNode) {
                if (focusedModels.add(edge.child().node())) {
                    neighbors.add(edge.child().node());
                }
            } else if (edge.child().node() == selectedNode) {
                if (focusedModels.add(edge.parent().node())) {
                    neighbors.add(edge.parent().node());
                }
            }
        }

        Map<AstNode, Point> positions = compactPositions(neighbors);
        Dimension focusedLogicalSize = focusedLogicalSize(positions.values());
        int centerX = focusedLogicalSize.width / 2 - NODE_WIDTH / 2;
        int centerY = focusedLogicalSize.height / 2 - NODE_HEIGHT / 2;
        positions.replaceAll((node, point) -> new Point(centerX + point.x, centerY + point.y));
        positions.put(selectedNode, new Point(centerX, centerY));

        Map<AstNode, Node> focusedNodesByModel = new IdentityHashMap<>();
        List<Node> focusedNodes = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Point position = positions.get(node.node());
            Node focused = position == null
                    ? node
                    : new Node(node.node(), position.x, position.y, node.depth());
            focusedNodes.add(focused);
            focusedNodesByModel.put(focused.node(), focused);
        }
        List<Edge> focusedEdges = edges.stream()
                .map(edge -> new Edge(
                        focusedNodesByModel.get(edge.parent().node()),
                        focusedNodesByModel.get(edge.child().node())
                ))
                .toList();
        Rectangle bounds = boundsOf(focusedNodes, focusedModels);
        return new Focus(
                new AstGraphLayout(focusedNodes, focusedEdges, focusedLogicalSize),
                Collections.unmodifiableSet(focusedModels),
                bounds
        );
    }

    private Dimension focusedLogicalSize(java.util.Collection<Point> positions) {
        int horizontalExtent = positions.stream().mapToInt(point -> Math.abs(point.x)).max().orElse(0);
        int verticalExtent = positions.stream().mapToInt(point -> Math.abs(point.y)).max().orElse(0);
        return new Dimension(
                Math.max(logicalSize.width, 2 * (horizontalExtent + NODE_WIDTH / 2 + MARGIN)),
                Math.max(logicalSize.height, 2 * (verticalExtent + NODE_HEIGHT / 2 + MARGIN))
        );
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

    record Focus(AstGraphLayout layout, Set<AstNode> nodes, Rectangle bounds) {
        Focus {
            bounds = new Rectangle(bounds);
        }

        @Override
        public Rectangle bounds() {
            return new Rectangle(bounds);
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
