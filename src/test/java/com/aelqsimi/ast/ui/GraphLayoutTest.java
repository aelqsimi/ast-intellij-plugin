package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import org.junit.Test;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class GraphLayoutTest {
    private static AstNode astTree() {
        List<AstNode> methods = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            AstNode call = new AstNode(
                    AstNode.Kind.CALL,
                    "call" + index,
                    index * 20 + 5,
                    index * 20 + 10,
                    List.of()
            );
            methods.add(new AstNode(
                    AstNode.Kind.METHOD,
                    "method" + index,
                    index * 20,
                    index * 20 + 15,
                    List.of(call)
            ));
        }
        AstNode type = new AstNode(AstNode.Kind.CLASS, "Sample", 0, 100, methods);
        return new AstNode(AstNode.Kind.FILE, "Sample.java", 0, 100, List.of(type));
    }

    @Test
    public void astLayoutCanBeCalculatedOffTheEdtAndCulledToTheViewport() throws Exception {
        AstNode root = astTree();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AstGraphLayout> future = executor.submit(() -> AstGraphLayout.calculate(root));
            AstGraphLayout layout = future.get();

            assertNotNull(layout);
            assertEquals(12, layout.nodes().size());
            assertEquals(11, layout.edges().size());
            Rectangle viewport = layout.nodes().getFirst().bounds();
            long visibleNodes = layout.nodes().stream()
                    .filter(node -> node.bounds().intersects(viewport))
                    .count();
            assertEquals(1, visibleNodes);
            assertTrue(layout.logicalSize().width > AstGraphLayout.NODE_WIDTH);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void callGraphLayoutCanBeCalculatedOffTheEdtAndCulledToTheViewport() throws Exception {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index * 10,
                    index * 10 + 5
            ));
            edges.add(new CallGraphEdge(
                    "method-" + index,
                    "method-" + ((index + 1) % 12),
                    1
            ));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CallGraphLayout> future = executor.submit(() ->
                    CallGraphLayout.calculate(new CallGraph(nodes, edges))
            );
            CallGraphLayout layout = future.get();

            assertNotNull(layout);
            assertEquals(12, layout.nodes().size());
            assertEquals(12, layout.edges().size());
            Rectangle viewport = layout.nodes().getFirst().bounds();
            long visibleNodes = layout.nodes().stream()
                    .filter(node -> node.bounds().intersects(viewport))
                    .count();
            assertTrue(visibleNodes >= 1);
            assertTrue(visibleNodes < layout.nodes().size());
            assertFalse(layout.logicalSize().equals(CallGraphLayout.empty().logicalSize()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void astFocusKeepsTheSelectedNeighborhoodTogether() {
        AstNode root = astTree();
        AstNode selected = root.children().getFirst();
        AstGraphLayout layout = AstGraphLayout.calculate(root);

        AstGraphLayout.Focus focus = layout.focus(selected);

        assertEquals(layout.nodes().size(), focus.layout().nodes().size());
        assertEquals(7, focus.nodes().size());
        AstGraphLayout.Node selectedVisual = focus.layout().nodes().stream()
                .filter(node -> node.node() == selected)
                .findFirst()
                .orElseThrow();
        assertEquals(
                focus.layout().logicalSize().width / 2 - AstGraphLayout.NODE_WIDTH / 2,
                selectedVisual.x()
        );
        assertTrue(focus.bounds().contains(selectedVisual.bounds()));
    }

    @Test
    public void callGraphFocusHighlightsAndCompactsDirectNeighbors() {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index * 10,
                    index * 10 + 5
            ));
            edges.add(new CallGraphEdge(
                    "method-" + index,
                    "method-" + ((index + 1) % 12),
                    1
            ));
        }
        CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(nodes, edges));

        CallGraphLayout.Focus focus = layout.focus("method-0");

        assertEquals(Set.of("method-0", "method-1", "method-11"), focus.nodeIds());
        assertEquals(layout.nodes().size(), focus.layout().nodes().size());
        CallGraphLayout.Node selected = focus.layout().nodes().stream()
                .filter(node -> node.node().id().equals("method-0"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                focus.layout().logicalSize().width / 2 - CallGraphLayout.NODE_WIDTH / 2,
                selected.x()
        );
        assertTrue(focus.bounds().contains(selected.bounds()));
    }

    @Test
    public void largeCallGraphUsesCompactConcentricRings() {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index,
                    index + 1
            ));
            if (index > 0) {
                edges.add(new CallGraphEdge("method-0", "method-" + index, 1));
            }
        }

        CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(nodes, edges));
        CallGraphLayout.Node hub = layout.nodes().stream()
                .filter(node -> node.node().id().equals("method-0"))
                .findFirst()
                .orElseThrow();

        assertEquals(layout.logicalSize().width / 2 - CallGraphLayout.NODE_WIDTH / 2, hub.x());
        assertEquals(layout.logicalSize().height / 2 - CallGraphLayout.NODE_HEIGHT / 2, hub.y());
        assertTrue("The old single ring was wider than 48,000 px", layout.logicalSize().width < 10_000);
        assertTrue("The old single ring was taller than 48,000 px", layout.logicalSize().height < 10_000);
    }
}
