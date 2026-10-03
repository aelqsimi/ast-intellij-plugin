package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.ProjectAnalysis;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ProjectAnalyzer {
    public ProjectAnalysis analyze(@NotNull Project project) {
        return IncrementalProjectCache.getInstance(project).snapshot().projectAnalysis();
    }
}
