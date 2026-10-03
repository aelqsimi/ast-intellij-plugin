package com.aelqsimi.ast.model;

public record CallGraphEdge(String sourceId, String targetId, int callCount) {
}
