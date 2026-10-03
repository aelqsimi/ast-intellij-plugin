package com.aelqsimi.ast.export;

import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import junit.framework.TestCase;

import java.util.List;

public final class GraphExporterTest extends TestCase {
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
        assertTrue(exported.contains("-->|×2|"));
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
        assertTrue(exported.endsWith("}\n"));
    }

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
                List.of(new CallGraphEdge("Sample#first()", "Sample#second()", 2))
        );
    }
}
