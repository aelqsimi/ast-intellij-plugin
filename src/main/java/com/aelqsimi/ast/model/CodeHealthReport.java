package com.aelqsimi.ast.model;

import java.util.List;

public record CodeHealthReport(
        int classLineThreshold,
        int methodComplexityThreshold,
        List<CodeHealthIssue> issues
) {
    public CodeHealthReport {
        issues = List.copyOf(issues);
    }
}
