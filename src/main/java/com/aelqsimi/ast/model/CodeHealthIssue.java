package com.aelqsimi.ast.model;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.List;

public record CodeHealthIssue(
        Kind kind,
        String symbol,
        int value,
        int threshold,
        List<String> participants,
        VirtualFile file,
        int startOffset,
        int endOffset
) {
    public CodeHealthIssue {
        participants = List.copyOf(participants);
    }

    public enum Kind {
        LARGE_CLASS,
        COMPLEX_METHOD,
        CLASS_DEPENDENCY_CYCLE,
        PACKAGE_DEPENDENCY_CYCLE
    }
}
