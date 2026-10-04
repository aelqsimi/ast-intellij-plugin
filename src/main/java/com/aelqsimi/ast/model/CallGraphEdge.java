package com.aelqsimi.ast.model;

public record CallGraphEdge(String sourceId, String targetId, int callCount, String label) {
    public CallGraphEdge(String sourceId, String targetId, int callCount) {
        this(sourceId, targetId, callCount, "");
    }

    public CallGraphEdge {
        label = label == null ? "" : label;
    }
}
