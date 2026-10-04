package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import com.intellij.openapi.progress.ProgressManager;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CallGraphLayout {
    static final int NODE_WIDTH = 210;
    static final int NODE_HEIGHT = 58;
    private static final int MARGIN = 70;
    private static final int EDGE_PADDING = 52;
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
        double radius = nodeCount == 1 ? 0 : Math.max(190, nodeCount * 48.0);
        int logicalWidth = (int) Math.ceil(radius * 2 + NODE_WIDTH + MARGIN * 2);
        int logicalHeight = (int) Math.ceil(radius * 2 + NODE_HEIGHT + MARGIN * 2);
        double centerX = logicalWidth / 2.0;
        double centerY = logicalHeight / 2.0;

        List<Node> nodes = new ArrayList<>(nodeCount);
        Map<String, Node> nodesById = new HashMap<>();
        for (int index = 0; index < nodeCount; index++) {
            ProgressManager.checkCanceled();
            double angle = nodeCount == 1 ? 0 : -Math.PI / 2 + 2 * Math.PI * index / nodeCount;
            int x = (int) Math.round(centerX + Math.cos(angle) * radius - NODE_WIDTH / 2.0);
            int y = (int) Math.round(centerY + Math.sin(angle) * radius - NODE_HEIGHT / 2.0);
            Node visual = new Node(graph.nodes().get(index), x, y);
            nodes.add(visual);
            nodesById.put(visual.node.id(), visual);
        }

        List<Edge> edges = new ArrayList<>(graph.edges().size());
        for (CallGraphEdge edge : graph.edges()) {
            ProgressManager.checkCanceled();
            Node source = nodesById.get(edge.sourceId());
            Node target = nodesById.get(edge.targetId());
            if (source != null && target != null) {
                edges.add(new Edge(source, target, edge.callCount()));
            }
        }
        return new CallGraphLayout(nodes, edges, new Dimension(logicalWidth, logicalHeight));
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

    record Node(CallGraphNode node, int x, int y) {
        Rectangle bounds() {
            return new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT);
        }
    }

    record Edge(Node source, Node target, int callCount) {
        Rectangle2D bounds() {
            double left = Math.min(source.x, target.x) - EDGE_PADDING;
            double top = Math.min(source.y, target.y) - EDGE_PADDING;
            double right = Math.max(source.x + NODE_WIDTH, target.x + NODE_WIDTH) + EDGE_PADDING;
            double bottom = Math.max(source.y + NODE_HEIGHT, target.y + NODE_HEIGHT) + EDGE_PADDING;
            return new Rectangle2D.Double(left, top, right - left, bottom - top);
        }
    }
}
