package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.AstAnalysisResult;
import com.aelqsimi.ast.model.AstNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class UastAnalyzer {
    public AstAnalysisResult analyze(@NotNull PsiFile psiFile) {
        UFile uFile = UastContextKt.toUElement(psiFile, UFile.class);
        if (uFile == null || psiFile.getVirtualFile() == null) {
            return null;
        }

        MutableNode root = new MutableNode(
                AstNode.Kind.FILE,
                psiFile.getName() + " · " + psiFile.getLanguage().getDisplayName(),
                0,
                psiFile.getTextLength()
        );
        Deque<MutableNode> parents = new ArrayDeque<>();
        parents.push(root);

        uFile.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitClass(@NotNull UClass node) {
                push(displayName(node.getName(), "node.unnamed.class"), AstNode.Kind.CLASS, node.getSourcePsi());
                return false;
            }

            @Override
            public void afterVisitClass(@NotNull UClass node) {
                parents.pop();
            }

            @Override
            public boolean visitMethod(@NotNull UMethod node) {
                push(displayName(node.getName(), "node.unnamed.method") + "()", AstNode.Kind.METHOD, node.getSourcePsi());
                return false;
            }

            @Override
            public void afterVisitMethod(@NotNull UMethod node) {
                parents.pop();
            }

            @Override
            public boolean visitField(@NotNull UField node) {
                addLeaf(displayName(node.getName(), "node.unnamed.field"), AstNode.Kind.FIELD, node.getSourcePsi());
                return true;
            }

            @Override
            public boolean visitCallExpression(@NotNull UCallExpression node) {
                addLeaf(displayName(node.getMethodName(), "node.unnamed.call") + "()", AstNode.Kind.CALL, node.getSourcePsi());
                return false;
            }

            private void push(String name, AstNode.Kind kind, PsiElement source) {
                SourceRange range = rangeOf(source);
                MutableNode child = new MutableNode(kind, name, range.startOffset(), range.endOffset());
                parents.peek().children.add(child);
                parents.push(child);
            }

            private void addLeaf(String name, AstNode.Kind kind, PsiElement source) {
                SourceRange range = rangeOf(source);
                parents.peek().children.add(new MutableNode(kind, name, range.startOffset(), range.endOffset()));
            }
        });

        return new AstAnalysisResult(psiFile.getVirtualFile(), root.freeze());
    }

    private static String displayName(String name, String fallbackKey) {
        return name == null || name.isBlank() ? "<" + AstLensBundle.message(fallbackKey) + ">" : name;
    }

    private static SourceRange rangeOf(PsiElement element) {
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

    private static final class MutableNode {
        private final AstNode.Kind kind;
        private final String name;
        private final int startOffset;
        private final int endOffset;
        private final List<MutableNode> children = new ArrayList<>();

        private MutableNode(AstNode.Kind kind, String name, int startOffset, int endOffset) {
            this.kind = kind;
            this.name = name;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        private AstNode freeze() {
            return new AstNode(
                    kind,
                    name,
                    startOffset,
                    endOffset,
                    children.stream().map(MutableNode::freeze).toList()
            );
        }
    }
}
