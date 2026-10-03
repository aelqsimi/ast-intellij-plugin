package com.aelqsimi.ast.model;

import com.intellij.openapi.vfs.VirtualFile;

public record CallGraphNode(
        String id,
        String label,
        Kind kind,
        VirtualFile file,
        int startOffset,
        int endOffset
) {
    public boolean contains(VirtualFile candidateFile, int offset) {
        return file != null
                && file.equals(candidateFile)
                && startOffset >= 0
                && offset >= startOffset
                && offset <= endOffset;
    }

    public enum Kind {
        INTERNAL,
        EXTERNAL,
        UNRESOLVED
    }
}
