package com.aelqsimi.ast.model;

import java.util.List;

public record AstNode(
        Kind kind,
        String name,
        int startOffset,
        int endOffset,
        List<AstNode> children
) {
    public AstNode {
        children = List.copyOf(children);
    }

    public boolean containsOffset(int offset) {
        return startOffset >= 0 && offset >= startOffset && offset <= endOffset;
    }

    public enum Kind {
        FILE,
        CLASS,
        METHOD,
        FIELD,
        CALL
    }
}
