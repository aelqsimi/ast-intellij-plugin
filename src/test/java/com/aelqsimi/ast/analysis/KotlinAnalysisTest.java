package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.AstAnalysisResult;
import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class KotlinAnalysisTest extends BasePlatformTestCase {
    public void testK2KotlinStructureAndCallsOnIdea2026_2() {
        assertTrue(
                "This compatibility test must run on the K2-only IntelliJ Platform 2026.2 or newer",
                ApplicationInfo.getInstance().getBuild().getBaselineVersion() >= 262
        );

        myFixture.configureByText(
                "Sample.kt",
                """
                        package demo

                        class Sample {
                            val value = 1

                            fun first() {
                                second()
                            }

                            fun second() = Unit
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
                node.kind() == AstNode.Kind.METHOD && node.name().equals("first()")));
        assertTrue(nodes.stream().anyMatch(node ->
                node.kind() == AstNode.Kind.CALL && node.name().equals("second()")));

        assertTrue(calls.nodes().stream().anyMatch(node -> node.label().equals("Sample.first()")));
        assertTrue(calls.nodes().stream().anyMatch(node -> node.label().equals("Sample.second()")));
        assertTrue(calls.edges().stream().anyMatch(edge -> {
            String source = calls.nodes().stream()
                    .filter(node -> node.id().equals(edge.sourceId()))
                    .findFirst()
                    .orElseThrow()
                    .label();
            String target = calls.nodes().stream()
                    .filter(node -> node.id().equals(edge.targetId()))
                    .findFirst()
                    .orElseThrow()
                    .label();
            return source.equals("Sample.first()") && target.equals("Sample.second()");
        }));
    }

    private static List<AstNode> flatten(AstNode node) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(node),
                node.children().stream().flatMap(child -> flatten(child).stream())
        ).toList();
    }
}
