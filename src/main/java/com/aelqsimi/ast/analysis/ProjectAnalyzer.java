package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.ProjectAnalysis;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class ProjectAnalyzer {
    public ProjectAnalysis analyze(@NotNull Project project) {
        return analyze(project, AnalysisScope.PROJECT_AND_DEPENDENCIES);
    }

    public ProjectAnalysis analyze(@NotNull Project project, @NotNull AnalysisScope scope) {
        return IncrementalProjectCache.getInstance(project).snapshot(scope).projectAnalysis();
    }
}
