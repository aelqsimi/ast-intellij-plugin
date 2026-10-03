package com.aelqsimi.ast.model;

import com.intellij.openapi.vfs.VirtualFile;

public record AstAnalysisResult(VirtualFile file, AstNode root) {
}
