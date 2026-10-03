package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CodeHealthIssue;
import com.aelqsimi.ast.model.CodeHealthReport;
import com.aelqsimi.ast.model.DependencyAnalysis;
import com.aelqsimi.ast.model.RelationshipQuery;
import com.aelqsimi.ast.model.RelationshipResult;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class ProjectFeaturesTest extends BasePlatformTestCase {
    public void testClassAndPackageDependenciesAndCycles() {
        myFixture.addFileToProject(
                "src/alpha/Alpha.java",
                """
                        package alpha;
                        import beta.Beta;
                        class Alpha {
                            Beta beta;
                            void useBeta() { beta.ping(); }
                            void ping() {}
                        }
                        """
        );
        myFixture.addFileToProject(
                "src/beta/Beta.java",
                """
                        package beta;
                        import alpha.Alpha;
                        class Beta {
                            Alpha alpha;
                            void useAlpha() { alpha.ping(); }
                            void ping() {}
                        }
                        """
        );

        IncrementalProjectCache.Snapshot snapshot = ReadAction.computeBlocking(() ->
                IncrementalProjectCache.getInstance(getProject()).snapshot());
        DependencyAnalysis dependencies = snapshot.dependencies();

        assertEdge(dependencies.classGraph(), "alpha.Alpha", "beta.Beta");
        assertEdge(dependencies.classGraph(), "beta.Beta", "alpha.Alpha");
        assertEdge(dependencies.packageGraph(), "alpha", "beta");
        assertEdge(dependencies.packageGraph(), "beta", "alpha");

        CodeHealthReport health = ReadAction.computeBlocking(() ->
                new CodeHealthAnalyzer().analyze(getProject(), dependencies));
        assertTrue(health.issues().stream().anyMatch(issue ->
                issue.kind() == CodeHealthIssue.Kind.CLASS_DEPENDENCY_CYCLE));
        assertTrue(health.issues().stream().anyMatch(issue ->
                issue.kind() == CodeHealthIssue.Kind.PACKAGE_DEPENDENCY_CYCLE));
    }

    public void testComplexMethodAndLargeClassAreReported() {
        myFixture.addFileToProject(
                "src/demo/Complex.java",
                """
                        package demo;
                        class Complex {
                            void evaluate(boolean value) {
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                                if (value) {}
                            }
                        }
                        """
        );
        String largeClass = "package demo;\nclass Large {\n"
                + "    // padding\n".repeat(500)
                + "}\n";
        myFixture.addFileToProject("src/demo/Large.java", largeClass);

        IncrementalProjectCache.Snapshot snapshot = ReadAction.computeBlocking(() ->
                IncrementalProjectCache.getInstance(getProject()).snapshot());
        CodeHealthReport health = ReadAction.computeBlocking(() ->
                new CodeHealthAnalyzer().analyze(getProject(), snapshot.dependencies()));

        assertTrue(health.issues().stream().anyMatch(issue ->
                issue.kind() == CodeHealthIssue.Kind.COMPLEX_METHOD
                        && issue.value() > issue.threshold()));
        assertTrue(health.issues().stream().anyMatch(issue ->
                issue.kind() == CodeHealthIssue.Kind.LARGE_CLASS
                        && issue.value() > issue.threshold()));
    }

    public void testRelationshipQueries() {
        PsiFile targetFile = myFixture.addFileToProject(
                "src/demo/Target.java",
                """
                        package demo;
                        interface Contract {}
                        public class Target implements Contract {
                            public void caller() { called(); }
                            public void called() {}
                        }
                        class Child extends Target {}
                        class Consumer {
                            void invoke() { new Target().called(); }
                        }
                        """
        );

        IncrementalProjectCache.Snapshot snapshot = ReadAction.computeBlocking(() ->
                IncrementalProjectCache.getInstance(getProject()).snapshot());
        DependencyAnalysis dependencies = snapshot.dependencies();
        RelationshipAnalyzer analyzer = new RelationshipAnalyzer();
        int calledOffset = targetFile.getText().indexOf("called() {}");
        int callerOffset = targetFile.getText().indexOf("caller()");
        int targetOffset = targetFile.getText().indexOf("Target implements");

        RelationshipResult callers = analyze(analyzer, targetFile, calledOffset, RelationshipQuery.CALLERS, dependencies);
        RelationshipResult callees = analyze(analyzer, targetFile, callerOffset, RelationshipQuery.CALLEES, dependencies);
        RelationshipResult interfaces = analyze(
                analyzer,
                targetFile,
                targetOffset,
                RelationshipQuery.IMPLEMENTED_INTERFACES,
                dependencies
        );
        RelationshipResult inheritors = analyze(
                analyzer,
                targetFile,
                targetOffset,
                RelationshipQuery.INHERITING_CLASSES,
                dependencies
        );
        RelationshipResult dependents = analyze(
                analyzer,
                targetFile,
                targetOffset,
                RelationshipQuery.DEPENDENT_CLASSES,
                dependencies
        );

        assertNotNull(callers);
        assertEquals(2, callers.relationCount());
        assertNotNull(callees);
        assertEquals(1, callees.relationCount());
        assertGraphContainsLabel(interfaces.graph(), "Contract");
        assertGraphContainsLabel(inheritors.graph(), "Child");
        assertGraphContainsLabel(dependents.graph(), "Consumer");
    }

    private RelationshipResult analyze(
            RelationshipAnalyzer analyzer,
            PsiFile file,
            int offset,
            RelationshipQuery query,
            DependencyAnalysis dependencies
    ) {
        return ReadAction.computeBlocking(() ->
                analyzer.analyze(getProject(), file, offset, query, dependencies));
    }

    private static void assertEdge(CallGraph graph, String sourceId, String targetId) {
        assertTrue(graph.edges().stream().anyMatch(edge ->
                edge.sourceId().equals(sourceId) && edge.targetId().equals(targetId)));
    }

    private static void assertGraphContainsLabel(CallGraph graph, String label) {
        assertNotNull(graph);
        assertTrue(graph.nodes().stream().anyMatch(node -> node.label().equals(label)));
    }
}
