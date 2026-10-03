package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class CallGraphAnalyzer {
    public CallGraph analyze(@NotNull PsiFile psiFile) {
        UFile uFile = UastContextKt.toUElement(psiFile, UFile.class);
        if (uFile == null || psiFile.getVirtualFile() == null) {
            return new CallGraph(java.util.List.of(), java.util.List.of());
        }

        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        Map<EdgeKey, Integer> edges = new LinkedHashMap<>();
        Deque<String> currentMethods = new ArrayDeque<>();
        VirtualFile analyzedFile = psiFile.getVirtualFile();

        uFile.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitMethod(@NotNull UMethod method) {
                PsiMethod javaMethod = method.getJavaPsi();
                String id = methodId(javaMethod);
                SourceLocation location = sourceLocation(method.getSourcePsi());
                MutableNode declaration = nodes.computeIfAbsent(
                        id,
                        ignored -> new MutableNode(id, methodLabel(javaMethod), CallGraphNode.Kind.INTERNAL)
                );
                declaration.label = methodLabel(javaMethod);
                declaration.kind = CallGraphNode.Kind.INTERNAL;
                declaration.file = analyzedFile;
                declaration.startOffset = location.startOffset();
                declaration.endOffset = location.endOffset();
                currentMethods.push(id);
                return false;
            }

            @Override
            public void afterVisitMethod(@NotNull UMethod method) {
                currentMethods.pop();
            }

            @Override
            public boolean visitCallExpression(@NotNull UCallExpression call) {
                if (currentMethods.isEmpty()) {
                    return false;
                }

                String targetId;
                PsiMethod resolved = call.resolve();
                if (resolved == null) {
                    String methodName = call.getMethodName();
                    String label = (methodName == null || methodName.isBlank())
                            ? "<" + AstLensBundle.message("node.unnamed.call") + ">"
                            : methodName + "()";
                    targetId = "unresolved:" + label;
                    nodes.computeIfAbsent(
                            targetId,
                            ignored -> new MutableNode(targetId, label, CallGraphNode.Kind.UNRESOLVED)
                    );
                } else {
                    targetId = methodId(resolved);
                    MutableNode target = nodes.computeIfAbsent(
                            targetId,
                            ignored -> createResolvedNode(resolved, analyzedFile)
                    );
                    updateNavigation(target, resolved, analyzedFile);
                }

                EdgeKey edge = new EdgeKey(currentMethods.peek(), targetId);
                edges.merge(edge, 1, Integer::sum);
                return false;
            }
        });

        return new CallGraph(
                nodes.values().stream().map(MutableNode::freeze).toList(),
                edges.entrySet().stream()
                        .map(entry -> new CallGraphEdge(
                                entry.getKey().sourceId(),
                                entry.getKey().targetId(),
                                entry.getValue()
                        ))
                        .toList()
        );
    }

    private static MutableNode createResolvedNode(PsiMethod method, VirtualFile analyzedFile) {
        MutableNode node = new MutableNode(
                methodId(method),
                methodLabel(method),
                CallGraphNode.Kind.EXTERNAL
        );
        updateNavigation(node, method, analyzedFile);
        return node;
    }

    private static void updateNavigation(MutableNode node, PsiMethod method, VirtualFile analyzedFile) {
        PsiElement navigation = method.getNavigationElement();
        SourceLocation location = sourceLocation(navigation);
        PsiFile containingFile = navigation.getContainingFile();
        VirtualFile file = containingFile == null ? null : containingFile.getVirtualFile();
        if (file != null) {
            node.file = file;
            node.startOffset = location.startOffset();
            node.endOffset = location.endOffset();
            if (file.equals(analyzedFile)) {
                node.kind = CallGraphNode.Kind.INTERNAL;
            }
        }
    }

    private static String methodId(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null
                ? "<top-level>"
                : owner.getQualifiedName() == null ? owner.getName() : owner.getQualifiedName();
        String parameters = Arrays.stream(method.getParameterList().getParameters())
                .map(PsiParameter::getType)
                .map(type -> type.getCanonicalText(false))
                .collect(Collectors.joining(","));
        return ownerName + "#" + method.getName() + "(" + parameters + ")";
    }

    private static String methodLabel(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null || owner.getName() == null ? "" : owner.getName() + ".";
        return ownerName + method.getName() + "()";
    }

    private static SourceLocation sourceLocation(PsiElement element) {
        if (element == null) {
            return new SourceLocation(-1, -1);
        }
        TextRange range = element.getTextRange();
        return range == null
                ? new SourceLocation(-1, -1)
                : new SourceLocation(range.getStartOffset(), range.getEndOffset());
    }

    private record EdgeKey(String sourceId, String targetId) {
    }

    private record SourceLocation(int startOffset, int endOffset) {
    }

    private static final class MutableNode {
        private final String id;
        private String label;
        private CallGraphNode.Kind kind;
        private VirtualFile file;
        private int startOffset = -1;
        private int endOffset = -1;

        private MutableNode(String id, String label, CallGraphNode.Kind kind) {
            this.id = id;
            this.label = label;
            this.kind = kind;
        }

        private CallGraphNode freeze() {
            return new CallGraphNode(id, label, kind, file, startOffset, endOffset);
        }
    }
}
