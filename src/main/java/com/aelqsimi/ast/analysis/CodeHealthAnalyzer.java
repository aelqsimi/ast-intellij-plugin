package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class CodeHealthAnalyzer {
    public static final int DEFAULT_CLASS_LINE_THRESHOLD = IncrementalProjectCache.CLASS_LINE_THRESHOLD;
    public static final int DEFAULT_METHOD_COMPLEXITY_THRESHOLD =
            IncrementalProjectCache.METHOD_COMPLEXITY_THRESHOLD;

    private static void addCycles(
            CallGraph graph,
            CodeHealthIssue.Kind kind,
            List<CodeHealthIssue> issues
    ) {
        Map<String, CallGraphNode> nodes = new LinkedHashMap<>();
        for (CallGraphNode node : graph.nodes()) {
            if (node.kind() == CallGraphNode.Kind.INTERNAL) {
                nodes.put(node.id(), node);
            }
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> adjacency.put(id, new LinkedHashSet<>()));
        for (CallGraphEdge edge : graph.edges()) {
            if (nodes.containsKey(edge.sourceId()) && nodes.containsKey(edge.targetId())) {
                adjacency.get(edge.sourceId()).add(edge.targetId());
            }
        }

        for (List<String> cycle : stronglyConnectedComponents(adjacency)) {
            if (cycle.size() < 2) {
                continue;
            }
            List<String> labels = cycle.stream().map(nodes::get).map(CallGraphNode::label).toList();
            CallGraphNode navigation = cycle.stream()
                    .map(nodes::get)
                    .filter(node -> node.file() != null && node.startOffset() >= 0)
                    .findFirst()
                    .orElse(nodes.get(cycle.getFirst()));
            issues.add(new CodeHealthIssue(
                    kind,
                    String.join(" ↔ ", labels),
                    cycle.size(),
                    0,
                    labels,
                    navigation.file(),
                    navigation.startOffset(),
                    navigation.endOffset()
            ));
        }
    }

    private static List<List<String>> stronglyConnectedComponents(Map<String, Set<String>> adjacency) {
        TarjanState state = new TarjanState(adjacency);
        adjacency.keySet().forEach(node -> {
            if (!state.indices.containsKey(node)) {
                state.visit(node);
            }
        });
        return state.components;
    }

    public CodeHealthReport analyze(@NotNull Project project, @NotNull DependencyAnalysis dependencies) {
        return analyze(project, dependencies, AnalysisScope.PROJECT_AND_DEPENDENCIES);
    }

    public CodeHealthReport analyze(
            @NotNull Project project,
            @NotNull DependencyAnalysis dependencies,
            @NotNull AnalysisScope scope
    ) {
        List<CodeHealthIssue> issues = new ArrayList<>(
                IncrementalProjectCache.getInstance(project).snapshot(scope).localHealthIssues()
        );
        addCycles(dependencies.classGraph(), CodeHealthIssue.Kind.CLASS_DEPENDENCY_CYCLE, issues);
        addCycles(dependencies.packageGraph(), CodeHealthIssue.Kind.PACKAGE_DEPENDENCY_CYCLE, issues);
        issues.sort(Comparator.comparing(CodeHealthIssue::kind).thenComparing(CodeHealthIssue::symbol));
        return new CodeHealthReport(
                DEFAULT_CLASS_LINE_THRESHOLD,
                DEFAULT_METHOD_COMPLEXITY_THRESHOLD,
                issues
        );
    }

    private static final class TarjanState {
        private final Map<String, Set<String>> adjacency;
        private final Map<String, Integer> indices = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final List<String> stack = new ArrayList<>();
        private final Set<String> onStack = new LinkedHashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private TarjanState(Map<String, Set<String>> adjacency) {
            this.adjacency = adjacency;
        }

        private void visit(String node) {
            ProgressManager.checkCanceled();
            indices.put(node, nextIndex);
            lowLinks.put(node, nextIndex);
            nextIndex++;
            stack.add(node);
            onStack.add(node);

            for (String neighbor : adjacency.getOrDefault(node, Set.of())) {
                if (!indices.containsKey(neighbor)) {
                    visit(neighbor);
                    lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(neighbor)));
                } else if (onStack.contains(neighbor)) {
                    lowLinks.put(node, Math.min(lowLinks.get(node), indices.get(neighbor)));
                }
            }

            if (!lowLinks.get(node).equals(indices.get(node))) {
                return;
            }
            List<String> component = new ArrayList<>();
            while (!stack.isEmpty()) {
                String member = stack.removeLast();
                onStack.remove(member);
                component.add(member);
                if (member.equals(node)) {
                    break;
                }
            }
            components.add(component);
        }
    }
}
