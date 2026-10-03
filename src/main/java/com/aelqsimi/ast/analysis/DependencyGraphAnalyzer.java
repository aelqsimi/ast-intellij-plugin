package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.DependencyAnalysis;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class DependencyGraphAnalyzer {
    public DependencyAnalysis analyze(@NotNull Project project) {
        return IncrementalProjectCache.getInstance(project).snapshot().dependencies();
    }
}
