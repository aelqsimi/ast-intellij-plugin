package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.*;
import java.util.stream.Collectors;

public final class RelationshipAnalyzer {
    private static RelationshipResult result(
            CallGraphNode target,
            Map<String, CallGraphNode> nodes,
            Map<EdgeKey, Integer> edges
    ) {
        CallGraph graph = new CallGraph(
                nodes.values().stream().toList(),
                edges.entrySet().stream()
                        .map(entry -> new CallGraphEdge(
                                entry.getKey().sourceId(),
                                entry.getKey().targetId(),
                                entry.getValue()
                        ))
                        .toList()
        );
        return new RelationshipResult(graph, target.id(), target.label(), graph.edges().size());
    }

    private static CallGraph filterGraph(
            CallGraph graph,
            String targetId,
            boolean incoming,
            CallGraphNode fallbackTarget
    ) {
        Map<String, CallGraphNode> allNodes = graph.nodes().stream()
                .collect(Collectors.toMap(
                        CallGraphNode::id,
                        node -> node,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        var selectedEdges = graph.edges().stream()
                .filter(edge -> incoming
                        ? targetId.equals(edge.targetId())
                        : targetId.equals(edge.sourceId()))
                .toList();
        Set<String> includedIds = new LinkedHashSet<>();
        includedIds.add(targetId);
        selectedEdges.forEach(edge -> {
            includedIds.add(edge.sourceId());
            includedIds.add(edge.targetId());
        });
        Map<String, CallGraphNode> selectedNodes = new LinkedHashMap<>();
        for (String id : includedIds) {
            CallGraphNode node = allNodes.get(id);
            if (node != null) {
                selectedNodes.put(id, node);
            }
        }
        selectedNodes.putIfAbsent(targetId, fallbackTarget);
        return new CallGraph(selectedNodes.values().stream().toList(), selectedEdges);
    }

    private static Selection findSelection(PsiFile psiFile, int offset) {
        UFile uFile = UastContextKt.toUElement(psiFile, UFile.class);
        if (uFile == null) {
            return new Selection(null, null);
        }
        SelectionHolder holder = new SelectionHolder(offset);
        uFile.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitMethod(@NotNull UMethod method) {
                holder.considerMethod(method.getJavaPsi(), method.getSourcePsi());
                return false;
            }

            @Override
            public boolean visitClass(@NotNull UClass uClass) {
                holder.considerClass(uClass.getJavaPsi(), uClass.getSourcePsi());
                return false;
            }
        });
        return new Selection(holder.method, holder.psiClass);
    }

    private static CallGraphNode nodeForMethod(PsiMethod method, Project project) {
        PsiElement navigation = method.getNavigationElement();
        SourceRange range = sourceRange(navigation);
        PsiFile containingFile = navigation.getContainingFile();
        VirtualFile file = containingFile == null ? null : containingFile.getVirtualFile();
        CallGraphNode.Kind kind = file != null && ProjectFileIndex.getInstance(project).isInContent(file)
                ? CallGraphNode.Kind.INTERNAL
                : CallGraphNode.Kind.EXTERNAL;
        return new CallGraphNode(
                methodId(method),
                methodLabel(method),
                kind,
                file,
                range.startOffset(),
                range.endOffset()
        );
    }

    private static CallGraphNode nodeForClass(PsiClass psiClass, Project project) {
        PsiElement navigation = psiClass.getNavigationElement();
        SourceRange range = sourceRange(navigation);
        PsiFile containingFile = navigation.getContainingFile();
        VirtualFile file = containingFile == null ? null : containingFile.getVirtualFile();
        CallGraphNode.Kind kind = file != null && ProjectFileIndex.getInstance(project).isInContent(file)
                ? CallGraphNode.Kind.INTERNAL
                : CallGraphNode.Kind.EXTERNAL;
        return new CallGraphNode(
                classId(psiClass),
                classLabel(psiClass),
                kind,
                file,
                range.startOffset(),
                range.endOffset()
        );
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

    private static String classId(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName != null) {
            return qualifiedName;
        }
        PsiFile file = psiClass.getContainingFile();
        VirtualFile virtualFile = file == null ? null : file.getVirtualFile();
        String name = psiClass.getName() == null ? "<anonymous>" : psiClass.getName();
        return (virtualFile == null ? "<unknown>" : virtualFile.getPath()) + "#" + name;
    }

    private static String classLabel(PsiClass psiClass) {
        return psiClass.getName() == null ? "<anonymous>" : psiClass.getName();
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

    public RelationshipResult analyze(
            @NotNull Project project,
            @NotNull PsiFile psiFile,
            int offset,
            @NotNull RelationshipQuery query,
            @NotNull DependencyAnalysis dependencies
    ) {
        return analyze(
                project,
                psiFile,
                offset,
                query,
                dependencies,
                AnalysisScope.PROJECT_AND_DEPENDENCIES
        );
    }

    public RelationshipResult analyze(
            @NotNull Project project,
            @NotNull PsiFile psiFile,
            int offset,
            @NotNull RelationshipQuery query,
            @NotNull DependencyAnalysis dependencies,
            @NotNull AnalysisScope scope
    ) {
        Selection selection = findSelection(psiFile, offset);
        if (query.methodRequired()) {
            return selection.method() == null
                    ? null
                    : analyzeMethod(project, selection.method(), query, scope);
        }
        return selection.psiClass() == null
                ? null
                : analyzeClass(project, selection.psiClass(), query, dependencies, scope);
    }

    private RelationshipResult analyzeMethod(
            Project project,
            PsiMethod method,
            RelationshipQuery query,
            AnalysisScope scope
    ) {
        String targetId = methodId(method);
        CallGraph projectGraph = projectCallGraph(project, scope);
        boolean incoming = query == RelationshipQuery.CALLERS;
        CallGraph filtered = filterGraph(projectGraph, targetId, incoming, nodeForMethod(method, project));
        return new RelationshipResult(
                filtered,
                targetId,
                methodLabel(method),
                filtered.edges().size()
        );
    }

    private RelationshipResult analyzeClass(
            Project project,
            PsiClass psiClass,
            RelationshipQuery query,
            DependencyAnalysis dependencies,
            AnalysisScope scope
    ) {
        return switch (query) {
            case DEPENDENT_CLASSES -> dependentClasses(project, psiClass, dependencies.classGraph());
            case IMPLEMENTED_INTERFACES -> implementedInterfaces(project, psiClass, scope);
            case INHERITING_CLASSES -> inheritingClasses(project, psiClass);
            default -> throw new IllegalArgumentException("Method relationship expected");
        };
    }

    private RelationshipResult dependentClasses(Project project, PsiClass targetClass, CallGraph graph) {
        String targetId = classId(targetClass);
        CallGraph filtered = filterGraph(graph, targetId, true, nodeForClass(targetClass, project));
        return new RelationshipResult(filtered, targetId, classLabel(targetClass), filtered.edges().size());
    }

    private RelationshipResult implementedInterfaces(
            Project project,
            PsiClass targetClass,
            AnalysisScope scope
    ) {
        CallGraphNode target = nodeForClass(targetClass, project);
        Map<String, CallGraphNode> nodes = new LinkedHashMap<>();
        nodes.put(target.id(), target);
        Map<EdgeKey, Integer> edges = new LinkedHashMap<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        for (PsiClass psiInterface : targetClass.getInterfaces()) {
            if (!scope.includes(fileIndex, psiInterface)) {
                continue;
            }
            CallGraphNode interfaceNode = nodeForClass(psiInterface, project);
            nodes.putIfAbsent(interfaceNode.id(), interfaceNode);
            edges.put(new EdgeKey(target.id(), interfaceNode.id()), 1);
        }
        return result(target, nodes, edges);
    }

    private RelationshipResult inheritingClasses(Project project, PsiClass targetClass) {
        CallGraphNode target = nodeForClass(targetClass, project);
        Map<String, CallGraphNode> nodes = new LinkedHashMap<>();
        nodes.put(target.id(), target);
        Map<EdgeKey, Integer> edges = new LinkedHashMap<>();
        ClassInheritorsSearch.search(
                        targetClass,
                        GlobalSearchScope.projectScope(project),
                        true
                )
                .forEach(inheritor -> {
                    ProgressManager.checkCanceled();
                    CallGraphNode child = nodeForClass(inheritor, project);
                    nodes.putIfAbsent(child.id(), child);
                    edges.put(new EdgeKey(child.id(), target.id()), 1);
                    return true;
                });
        return result(target, nodes, edges);
    }

    private CallGraph projectCallGraph(Project project, AnalysisScope scope) {
        return IncrementalProjectCache.getInstance(project).snapshot(scope).completeCallGraph();
    }

    private record Selection(PsiMethod method, PsiClass psiClass) {
    }

    private record SourceRange(int startOffset, int endOffset) {
        private boolean contains(int offset) {
            return startOffset >= 0 && offset >= startOffset && offset <= endOffset;
        }

        private int length() {
            return endOffset - startOffset;
        }
    }

    private record EdgeKey(String sourceId, String targetId) {
    }

    private static final class SelectionHolder {
        private final int offset;
        private PsiMethod method;
        private int methodLength = Integer.MAX_VALUE;
        private PsiClass psiClass;
        private int classLength = Integer.MAX_VALUE;

        private SelectionHolder(int offset) {
            this.offset = offset;
        }

        private void considerMethod(PsiMethod candidate, PsiElement source) {
            SourceRange range = sourceRange(source);
            if (range.contains(offset) && range.length() < methodLength) {
                method = candidate;
                methodLength = range.length();
            }
        }

        private void considerClass(PsiClass candidate, PsiElement source) {
            SourceRange range = sourceRange(source);
            if (range.contains(offset) && range.length() < classLength) {
                psiClass = candidate;
                classLength = range.length();
            }
        }
    }

}
