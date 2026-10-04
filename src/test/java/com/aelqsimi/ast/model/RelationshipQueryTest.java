package com.aelqsimi.ast.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RelationshipQueryTest {
    @Test
    public void methodAndClassQueriesFormSeparateGroups() {
        List<RelationshipQuery> methodQueries = Arrays.stream(RelationshipQuery.values())
                .filter(RelationshipQuery::methodRequired)
                .toList();
        List<RelationshipQuery> classQueries = Arrays.stream(RelationshipQuery.values())
                .filter(query -> !query.methodRequired())
                .toList();

        assertEquals(List.of(RelationshipQuery.CALLERS, RelationshipQuery.CALLEES), methodQueries);
        assertEquals(
                List.of(
                        RelationshipQuery.DEPENDENT_CLASSES,
                        RelationshipQuery.PARENT_CLASSES,
                        RelationshipQuery.IMPLEMENTED_INTERFACES,
                        RelationshipQuery.INHERITING_CLASSES
                ),
                classQueries
        );
    }
}
