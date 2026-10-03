package com.aelqsimi.ast.model;

import java.util.List;

public record SyntaxTreeNode(
        String type,
        String excerpt,
        int startOffset,
        int endOffset,
        Category category,
        List<SyntaxTreeNode> children
) {
    public SyntaxTreeNode {
        children = List.copyOf(children);
    }

    public boolean containsOffset(int offset) {
        return startOffset >= 0 && offset >= startOffset && offset <= endOffset;
    }

    public enum Category {
        NORMAL,
        WHITESPACE,
        COMMENT,
        PUNCTUATION,
        IMPORT,
        SYNTHETIC,
        TRUNCATED
    }
}
