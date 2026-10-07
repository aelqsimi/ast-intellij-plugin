package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import com.intellij.openapi.progress.ProgressManager;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

final class CallGraphLayout {
    static final int NODE_WIDTH = 210;
    static final int NODE_HEIGHT = 58;
    private static final int MARGIN = 70;
    private static final int EDGE_PADDING = 52;
    private static final int FIRST_RING_RADIUS = 210;
    private static final int RING_GAP = 170;
    private static final int NODE_ARC_GAP = 28;
    private static final CallGraphLayout EMPTY = new CallGraphLayout(List.of(), List.of(), new Dimension(500, 300));

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Dimension logicalSize;

    private CallGraphLayout(List<Node> nodes, List<Edge> edges, Dimension logicalSize) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.logicalSize = new Dimension(logicalSize);
    }

    static CallGraphLayout empty() {
        return EMPTY;
    }

    static CallGraphLayout calculate(CallGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return empty();
        }

        int nodeCount = graph.nodes().size();
        Map<String, Integer> degrees = new HashMap<>();
        for (CallGraphEdge edge : graph.edges()) {
            ProgressManager.checkCanceled();
            degrees.merge(edge.sourceId(), 1, Integer::sum);
            degrees.merge(edge.targetId(), 1, Integer::sum);
        }

        Map<String, Integer> originalIndexes = new HashMap<>();
        for (int index = 0; index < nodeCount; index++) {
            originalIndexes.put(graph.nodes().get(index).id(), index);
        }
        List<CallGraphNode> placementOrder = new ArrayList<>(graph.nodes());
        placementOrder.sort(Comparator
                .comparingInt((CallGraphNode node) -> degrees.getOrDefault(node.id(), 0))
                .reversed()
                .thenComparingInt(node -> originalIndexes.getOrDefault(node.id(), Integer.MAX_VALUE)));

        Map<String, Point> relativePositions = concentricPositions(placementOrder);
        int horizontalExtent = relativePositions.values().stream()
                .mapToInt(point -> Math.abs(point.x))
                .max()
                .orElse(0);
        int verticalExtent = relativePositions.values().stream()
                .mapToInt(point -> Math.abs(point.y))
                .max()
                .orElse(0);
        int logicalWidth = Math.max(500, 2 * (horizontalExtent + NODE_WIDTH / 2 + MARGIN));
        int logicalHeight = Math.max(300, 2 * (verticalExtent + NODE_HEIGHT / 2 + MARGIN));
        double centerX = logicalWidth / 2.0;
        double centerY = logicalHeight / 2.0;

        List<Node> nodes = new ArrayList<>(nodeCount);
        Map<String, Node> nodesById = new HashMap<>();
        for (CallGraphNode node : graph.nodes()) {
            ProgressManager.checkCanceled();
            Point position = relativePositions.get(node.id());
            int x = (int) Math.round(centerX + position.x - NODE_WIDTH / 2.0);
            int y = (int) Math.round(centerY + position.y - NODE_HEIGHT / 2.0);
            Node visual = new Node(node, x, y);
            nodes.add(visual);
            nodesById.put(visual.node.id(), visual);
        }

        List<Edge> edges = new ArrayList<>(graph.edges().size());
        for (CallGraphEdge edge : graph.edges()) {
            ProgressManager.checkCanceled();
            Node source = nodesById.get(edge.sourceId());
            Node target = nodesById.get(edge.targetId());
            if (source != null && target != null) {
                edges.add(new Edge(source, target, edge.callCount(), edge.label()));
            }
        }
        return new CallGraphLayout(nodes, edges, new Dimension(logicalWidth, logicalHeight));
    }

    private static Map<String, Point> concentricPositions(List<CallGraphNode> placementOrder) {
        Map<String, Point> positions = new HashMap<>();
        positions.put(placementOrder.getFirst().id(), new Point());
        int index = 1;
        int ring = 0;
        while (index < placementOrder.size()) {
            ProgressManager.checkCanceled();
            int radius = FIRST_RING_RADIUS + ring * RING_GAP;
            int capacity = Math.max(
                    4,
                    (int) Math.floor(2 * Math.PI * radius / (NODE_WIDTH + NODE_ARC_GAP))
            );
            int count = Math.min(capacity, placementOrder.size() - index);
            double offset = ring % 2 == 0 ? 0 : Math.PI / count;
            for (int position = 0; position < count; position++) {
                double angle = -Math.PI / 2 + offset + 2 * Math.PI * position / count;
                positions.put(placementOrder.get(index++).id(), new Point(
                        (int) Math.round(Math.cos(angle) * radius),
                        (int) Math.round(Math.sin(angle) * radius)
                ));
            }
            ring++;
        }
        return positions;
    }

    private static Map<String, Point> compactPositions(List<String> neighborIds) {
        Map<String, Point> positions = new LinkedHashMap<>();
        int index = 0;
        int ring = 1;
        while (index < neighborIds.size()) {
            int radius = 180 + (ring - 1) * 135;
            int capacity = Math.max(4, (int) Math.floor(2 * Math.PI * radius / (NODE_WIDTH + 35.0)));
            int count = Math.min(capacity, neighborIds.size() - index);
            for (int position = 0; position < count; position++) {
                double angle = -Math.PI / 2 + 2 * Math.PI * position / count;
                positions.put(neighborIds.get(index++), new Point(
                        (int) Math.round(Math.cos(angle) * radius),
                        (int) Math.round(Math.sin(angle) * radius)
                ));
            }
            ring++;
        }
        return positions;
    }

    private static Rectangle boundsOf(List<Node> nodes, Set<String> focusedIds) {
        Rectangle bounds = null;
        for (Node node : nodes) {
            if (!focusedIds.contains(node.node().id())) {
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

    CallGraphLayout moveNode(String nodeId, int x, int y) {
        Map<String, Node> movedNodesById = new LinkedHashMap<>();
        List<Node> movedNodes = new ArrayList<>(nodes.size());
        boolean found = false;
        for (Node node : nodes) {
            boolean target = node.node().id().equals(nodeId);
            Node moved = target ? new Node(node.node(), x, y) : node;
            found |= target;
            movedNodes.add(moved);
            movedNodesById.put(moved.node().id(), moved);
        }
        if (!found) {
            return this;
        }

        List<Edge> movedEdges = edges.stream()
                .map(edge -> new Edge(
                        movedNodesById.get(edge.source().node().id()),
                        movedNodesById.get(edge.target().node().id()),
                        edge.callCount(),
                        edge.label()
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
        return new CallGraphLayout(
                movedNodes,
                movedEdges,
                new Dimension(Math.max(logicalSize.width, width), Math.max(logicalSize.height, height))
        );
    }

    Focus focus(String selectedId) {
        Node selected = nodes.stream()
                .filter(node -> node.node().id().equals(selectedId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return new Focus(this, Set.of(), new Rectangle());
        }

        Set<String> focusedIds = new LinkedHashSet<>();
        focusedIds.add(selectedId);
        for (Edge edge : edges) {
            if (edge.source().node().id().equals(selectedId)) {
                focusedIds.add(edge.target().node().id());
            } else if (edge.target().node().id().equals(selectedId)) {
                focusedIds.add(edge.source().node().id());
            }
        }

        Map<String, Point> focusedPositions = compactPositions(
                focusedIds.stream().filter(id -> !id.equals(selectedId)).toList()
        );
        Dimension focusedLogicalSize = focusedLogicalSize(focusedPositions.values());
        int centerX = focusedLogicalSize.width / 2 - NODE_WIDTH / 2;
        int centerY = focusedLogicalSize.height / 2 - NODE_HEIGHT / 2;
        focusedPositions.replaceAll((id, point) -> new Point(centerX + point.x, centerY + point.y));
        focusedPositions.put(selectedId, new Point(centerX, centerY));

        Map<String, Node> focusedNodesById = new LinkedHashMap<>();
        List<Node> focusedNodes = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Point position = focusedPositions.get(node.node().id());
            Node focused = position == null
                    ? node
                    : new Node(node.node(), position.x, position.y);
            focusedNodes.add(focused);
            focusedNodesById.put(focused.node().id(), focused);
        }

        List<Edge> focusedEdges = edges.stream()
                .map(edge -> new Edge(
                        focusedNodesById.get(edge.source().node().id()),
                        focusedNodesById.get(edge.target().node().id()),
                        edge.callCount(),
                        edge.label()
                ))
                .toList();
        Rectangle bounds = boundsOf(focusedNodes, focusedIds);
        return new Focus(
                new CallGraphLayout(focusedNodes, focusedEdges, focusedLogicalSize),
                Collections.unmodifiableSet(focusedIds),
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

    record Node(CallGraphNode node, int x, int y) {
        Rectangle bounds() {
            return new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT);
        }
    }

    record Edge(Node source, Node target, int callCount, String label) {
        Rectangle2D bounds() {
            double left = Math.min(source.x, target.x) - EDGE_PADDING;
            double top = Math.min(source.y, target.y) - EDGE_PADDING;
            double right = Math.max(source.x + NODE_WIDTH, target.x + NODE_WIDTH) + EDGE_PADDING;
            double bottom = Math.max(source.y + NODE_HEIGHT, target.y + NODE_HEIGHT) + EDGE_PADDING;
            return new Rectangle2D.Double(left, top, right - left, bottom - top);
        }
    }

    record Focus(CallGraphLayout layout, Set<String> nodeIds, Rectangle bounds) {
        Focus {
            bounds = new Rectangle(bounds);
        }

        @Override
        public Rectangle bounds() {
            return new Rectangle(bounds);
        }
    }
}
