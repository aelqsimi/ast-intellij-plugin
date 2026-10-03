package com.aelqsimi.ast.model;

public record ProjectAnalysis(
        CallGraph structureGraph,
        CallGraph callGraph,
        int fileCount,
        int packageCount,
        int classCount,
        int methodCount,
        boolean truncated,
        int reanalyzedFileCount,
        int reusedFileCount,
        int removedFileCount
) {
}
