package com.aelqsimi.ast.model;

public record RelationshipResult(
        CallGraph graph,
        String targetId,
        String targetLabel,
        int relationCount
) {
}
