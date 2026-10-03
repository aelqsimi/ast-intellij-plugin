package com.aelqsimi.ast.model;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;

public record DependencyAnalysis(
        CallGraph classGraph,
        CallGraph packageGraph,
        Map<VirtualFile, String> classIdByFile,
        Map<VirtualFile, String> packageIdByFile
) {
    public DependencyAnalysis {
        classIdByFile = Map.copyOf(classIdByFile);
        packageIdByFile = Map.copyOf(packageIdByFile);
    }
}
