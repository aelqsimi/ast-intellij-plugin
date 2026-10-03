package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.SyntaxComparison;
import com.aelqsimi.ast.model.SyntaxTreeNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UComment;
import org.jetbrains.uast.UImportStatement;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class SyntaxComparisonAnalyzer {
    private static final int MAX_NODES_PER_TREE = 6_000;
    private static final int MAX_EXCERPT_LENGTH = 70;

    public SyntaxComparison analyze(@NotNull PsiFile psiFile) {
        Counter psiCounter = new Counter();
        SyntaxTreeNode psiRoot = buildPsiTree(psiFile, psiCounter);
        UFile uFile = UastContextKt.toUElement(psiFile, UFile.class);
        SyntaxTreeNode uastRoot = uFile == null ? null : buildUastTree(uFile);
        return new SyntaxComparison(psiRoot, uastRoot);
    }

    private static SyntaxTreeNode buildPsiTree(PsiElement element, Counter counter) {
        counter.value++;
        SourceRange range = sourceRange(element);
        List<SyntaxTreeNode> children = new ArrayList<>();
        for (PsiElement child : element.getChildren()) {
            if (counter.value >= MAX_NODES_PER_TREE) {
                children.add(truncatedNode());
                break;
            }
            children.add(buildPsiTree(child, counter));
        }
        return new SyntaxTreeNode(
                element.getClass().getSimpleName(),
                excerpt(element.getText()),
                range.startOffset(),
                range.endOffset(),
                psiCategory(element),
                children
        );
    }

    private static SyntaxTreeNode buildUastTree(UFile uFile) {
        MutableNode syntheticRoot = new MutableNode(
                "UAST",
                "",
                0,
                0,
                SyntaxTreeNode.Category.SYNTHETIC
        );
        Deque<MutableNode> parents = new ArrayDeque<>();
        parents.push(syntheticRoot);
        Counter counter = new Counter();
        Set<UElement> pushedElements = Collections.newSetFromMap(new IdentityHashMap<>());

        uFile.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitElement(@NotNull UElement element) {
                if (counter.value >= MAX_NODES_PER_TREE) {
                    if (parents.peek().children.stream().noneMatch(node -> node.truncated)) {
                        parents.peek().children.add(MutableNode.truncated());
                    }
                    return true;
                }
                counter.value++;
                PsiElement source = element.getSourcePsi();
                SourceRange range = sourceRange(source);
                MutableNode child = new MutableNode(
                        element.getClass().getSimpleName(),
                        excerpt(source == null ? element.asRenderString() : source.getText()),
                        range.startOffset(),
                        range.endOffset(),
                        uastCategory(element, source)
                );
                parents.peek().children.add(child);
                parents.push(child);
                pushedElements.add(element);
                return false;
            }

            @Override
            public void afterVisitElement(@NotNull UElement element) {
                if (pushedElements.remove(element) && parents.size() > 1) {
                    parents.pop();
                }
            }
        });

        return syntheticRoot.children.isEmpty() ? null : syntheticRoot.children.getFirst().freeze();
    }

    private static SyntaxTreeNode truncatedNode() {
        return new SyntaxTreeNode(
                AstLensBundle.message("comparison.truncated"),
                "",
                -1,
                -1,
                SyntaxTreeNode.Category.TRUNCATED,
                List.of()
        );
    }

    private static SyntaxTreeNode.Category psiCategory(PsiElement element) {
        if (element instanceof PsiWhiteSpace) {
            return SyntaxTreeNode.Category.WHITESPACE;
        }
        if (element instanceof PsiComment) {
            return SyntaxTreeNode.Category.COMMENT;
        }
        String typeName = element.getClass().getSimpleName().toLowerCase();
        if (typeName.contains("import")) {
            return SyntaxTreeNode.Category.IMPORT;
        }
        if (element.getChildren().length == 0 && isPunctuation(element.getText())) {
            return SyntaxTreeNode.Category.PUNCTUATION;
        }
        return SyntaxTreeNode.Category.NORMAL;
    }

    private static SyntaxTreeNode.Category uastCategory(UElement element, PsiElement source) {
        if (element instanceof UComment) {
            return SyntaxTreeNode.Category.COMMENT;
        }
        if (element instanceof UImportStatement) {
            return SyntaxTreeNode.Category.IMPORT;
        }
        if (source == null) {
            return SyntaxTreeNode.Category.SYNTHETIC;
        }
        return SyntaxTreeNode.Category.NORMAL;
    }

    private static boolean isPunctuation(String text) {
        return text != null && text.trim().matches("[{}()\\[\\];,.]+");
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_EXCERPT_LENGTH
                ? normalized
                : normalized.substring(0, MAX_EXCERPT_LENGTH - 1) + "…";
    }

    private static SourceRange sourceRange(PsiElement element) {
        if (element == null) {
            return new SourceRange(-1, -1);
        }
        TextRange range = element.getTextRange();
        return range == null
                ? new SourceRange(-1, -1)
                : new SourceRange(range.getStartOffset(), range.getEndOffset());
    }

    private record SourceRange(int startOffset, int endOffset) {
    }

    private static final class Counter {
        private int value;
    }

    private static final class MutableNode {
        private final String type;
        private final String excerpt;
        private final int startOffset;
        private final int endOffset;
        private final SyntaxTreeNode.Category category;
        private final List<MutableNode> children = new ArrayList<>();
        private final boolean truncated;

        private MutableNode(
                String type,
                String excerpt,
                int startOffset,
                int endOffset,
                SyntaxTreeNode.Category category
        ) {
            this(type, excerpt, startOffset, endOffset, category, false);
        }

        private MutableNode(
                String type,
                String excerpt,
                int startOffset,
                int endOffset,
                SyntaxTreeNode.Category category,
                boolean truncated
        ) {
            this.type = type;
            this.excerpt = excerpt;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.category = category;
            this.truncated = truncated;
        }

        private static MutableNode truncated() {
            return new MutableNode(
                    AstLensBundle.message("comparison.truncated"),
                    "",
                    -1,
                    -1,
                    SyntaxTreeNode.Category.TRUNCATED,
                    true
            );
        }

        private SyntaxTreeNode freeze() {
            return new SyntaxTreeNode(
                    type,
                    excerpt,
                    startOffset,
                    endOffset,
                    category,
                    children.stream().map(MutableNode::freeze).toList()
            );
        }
    }
}
