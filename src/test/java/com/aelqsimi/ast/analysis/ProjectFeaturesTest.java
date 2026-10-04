package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class ProjectFeaturesTest extends BasePlatformTestCase {
    private static void assertEdge(CallGraph graph, String sourceId, String targetId) {
        assertTrue(graph.edges().stream().anyMatch(edge ->
                edge.sourceId().equals(sourceId) && edge.targetId().equals(targetId)));
    }

    private static void assertGraphContainsLabel(CallGraph graph, String label) {
        assertNotNull(graph);
        assertTrue(graph.nodes().stream().anyMatch(node -> node.label().equals(label)));
    }

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
                        class GrandParent {}
                        class Parent extends GrandParent {}
                        public class Target extends Parent implements Contract {
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
        int targetOffset = targetFile.getText().indexOf("Target extends");

        RelationshipResult callers = analyze(analyzer, targetFile, calledOffset, RelationshipQuery.CALLERS, dependencies);
        RelationshipResult callees = analyze(analyzer, targetFile, callerOffset, RelationshipQuery.CALLEES, dependencies);
        RelationshipResult interfaces = analyze(
                analyzer,
                targetFile,
                targetOffset,
                RelationshipQuery.IMPLEMENTED_INTERFACES,
                dependencies
        );
        RelationshipResult parents = analyze(
                analyzer,
                targetFile,
                targetOffset,
                RelationshipQuery.PARENT_CLASSES,
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
        assertTrue(interfaces.graph().edges().stream().allMatch(edge -> edge.label().equals("implements")));
        assertGraphContainsLabel(parents.graph(), "Parent");
        assertGraphContainsLabel(parents.graph(), "GrandParent");
        assertEquals(2, parents.relationCount());
        assertTrue(parents.graph().edges().stream().allMatch(edge -> edge.label().equals("extends")));
        assertGraphContainsLabel(inheritors.graph(), "Child");
        assertTrue(inheritors.graph().edges().stream().allMatch(edge -> edge.label().equals("extends")));
        assertGraphContainsLabel(dependents.graph(), "Consumer");
        assertTrue(dependents.graph().edges().stream().allMatch(edge -> edge.label().equals("depends on")));
        assertTrue(callers.graph().edges().stream().allMatch(edge -> edge.label().equals("calls")));
    }

    public void testJdkTypesAreExcludedFromProjectDependencies() {
        myFixture.addFileToProject(
                "src/demo/UsesJdk.java",
                """
                        package demo;
                        class UsesJdk {
                            String value;
                        }
                        """
        );

        IncrementalProjectCache.Snapshot snapshot = ReadAction.computeBlocking(() ->
                IncrementalProjectCache.getInstance(getProject()).snapshot(
                        AnalysisScope.PROJECT_AND_DEPENDENCIES
                ));

        assertFalse(snapshot.dependencies().classGraph().nodes().stream().anyMatch(node ->
                node.id().equals("java.lang.String")));
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
}
