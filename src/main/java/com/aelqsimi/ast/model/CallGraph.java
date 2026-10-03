package com.aelqsimi.ast.model;

import java.util.List;

public record CallGraph(List<CallGraphNode> nodes, List<CallGraphEdge> edges) {
    public CallGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
