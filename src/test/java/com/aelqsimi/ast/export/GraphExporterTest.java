package com.aelqsimi.ast.export;

import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public final class GraphExporterTest extends TestCase {
    private static CallGraph sampleGraph() {
        return new CallGraph(
                List.of(
                        new CallGraphNode(
                                "Sample#first()",
                                "Sample.first()",
                                CallGraphNode.Kind.INTERNAL,
                                null,
                                0,
                                10
                        ),
                        new CallGraphNode(
                                "Sample#second()",
                                "Sample.second()",
                                CallGraphNode.Kind.INTERNAL,
                                null,
                                11,
                                20
                        )
                ),
                List.of(new CallGraphEdge("Sample#first()", "Sample#second()", 2, "calls"))
        );
    }

    public void testStructureIsExportedAsValidJsonShape() {
        AstNode root = new AstNode(
                AstNode.Kind.FILE,
                "Sample\".java",
                0,
                20,
                List.of(new AstNode(AstNode.Kind.CLASS, "Sample", 0, 20, List.of()))
        );

        String exported = GraphExporter.exportStructure(root, GraphExporter.Format.JSON);

        assertTrue(exported.startsWith("{\n"));
        assertTrue(exported.contains("\"type\": \"structure\""));
        assertTrue(exported.contains("\"name\": \"Sample\\\".java\""));
        assertTrue(exported.contains("\"kind\": \"CLASS\""));
        assertTrue(exported.endsWith("}\n"));
    }

    public void testDirectedGraphIsExportedAsMermaid() {
        CallGraph graph = sampleGraph();

        String exported = GraphExporter.exportDirectedGraph(
                graph,
                "Calls",
                GraphExporter.Format.MERMAID
        );

        assertTrue(exported.contains("flowchart LR"));
        assertTrue(exported.contains("Sample.first()"));
        assertTrue(exported.contains("Sample.second()"));
        assertTrue(exported.contains("-->|calls ×2|"));
    }

    public void testDirectedGraphIsExportedAsGraphviz() {
        String exported = GraphExporter.exportDirectedGraph(
                sampleGraph(),
                "Calls",
                GraphExporter.Format.GRAPHVIZ
        );

        assertTrue(exported.startsWith("digraph \"Calls\""));
        assertTrue(exported.contains("rankdir=LR"));
        assertTrue(exported.contains("->"));
        assertTrue(exported.contains("label=\"calls ×2\""));
        assertTrue(exported.endsWith("}\n"));
    }

    public void testDirectedGraphJsonContainsTheEdgeLabel() {
        String exported = GraphExporter.exportDirectedGraph(
                sampleGraph(),
                "Calls",
                GraphExporter.Format.JSON
        );

        assertTrue(exported.contains("\"label\": \"calls\""));
    }

    public void testLargeDirectedGraphIsExportedAsSelfRenderingMermaidHtml() {
        String exported = GraphExporter.exportDirectedGraph(
                sampleGraph(),
                "Calls <large>",
                GraphExporter.Format.MERMAID_HTML
        );

        assertTrue(exported.startsWith("<!doctype html>"));
        assertTrue(exported.contains("mermaid@12.1.0"));
        assertTrue(exported.contains("maxEdges: 1000"));
        assertTrue(exported.contains("nodeSpacing: 20"));
        assertTrue(exported.contains("flowchart LR"));
        assertTrue(exported.contains("Calls &lt;large&gt;"));
    }

    public void testMermaidHtmlRaisesEdgeLimitAboveExportedEdgeCount() {
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 1_100; index++) {
            edges.add(new CallGraphEdge("Sample#first()", "Sample#second()", 1));
        }
        CallGraph sample = sampleGraph();
        String exported = GraphExporter.exportDirectedGraph(
                new CallGraph(sample.nodes(), edges),
                "Large calls",
                GraphExporter.Format.MERMAID_HTML
        );

        assertTrue(exported.contains("maxEdges: 1200"));
    }
}
