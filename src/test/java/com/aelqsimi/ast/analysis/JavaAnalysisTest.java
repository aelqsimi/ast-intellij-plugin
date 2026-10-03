package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.AstAnalysisResult;
import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class JavaAnalysisTest extends BasePlatformTestCase {
    public void testJavaStructureAndCalls() {
        myFixture.configureByText(
                "Sample.java",
                """
                        package demo;

                        class Sample {
                            private int value = 1;

                            void first() {
                                second();
                            }

                            void second() {
                            }
                        }
                        """
        );

        AstAnalysisResult structure = ReadAction.computeBlocking(() ->
                new UastAnalyzer().analyze(myFixture.getFile()));
        CallGraph calls = ReadAction.computeBlocking(() ->
                new CallGraphAnalyzer().analyze(myFixture.getFile()));

        assertNotNull(structure);
        List<AstNode> nodes = flatten(structure.root());
        assertTrue(nodes.stream().anyMatch(node ->
                node.kind() == AstNode.Kind.CLASS && node.name().equals("Sample")));
        assertTrue(nodes.stream().anyMatch(node ->
                node.kind() == AstNode.Kind.FIELD && node.name().equals("value")));
        assertTrue(nodes.stream().anyMatch(node ->
                node.kind() == AstNode.Kind.METHOD && node.name().equals("first()")));
        assertTrue(nodes.stream().anyMatch(node ->
                node.kind() == AstNode.Kind.CALL && node.name().equals("second()")));

        String firstId = calls.nodes().stream()
                .filter(node -> node.label().equals("Sample.first()"))
                .findFirst()
                .orElseThrow()
                .id();
        String secondId = calls.nodes().stream()
                .filter(node -> node.label().equals("Sample.second()"))
                .findFirst()
                .orElseThrow()
                .id();
        assertTrue(calls.edges().stream().anyMatch(edge ->
                edge.sourceId().equals(firstId) && edge.targetId().equals(secondId)));
    }

    private static List<AstNode> flatten(AstNode node) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(node),
                node.children().stream().flatMap(child -> flatten(child).stream())
        ).toList();
    }
}
