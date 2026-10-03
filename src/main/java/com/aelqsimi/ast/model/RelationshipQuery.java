package com.aelqsimi.ast.model;

public enum RelationshipQuery {
    CALLERS(true, "relationship.query.callers"),
    CALLEES(true, "relationship.query.callees"),
    DEPENDENT_CLASSES(false, "relationship.query.dependent.classes"),
    IMPLEMENTED_INTERFACES(false, "relationship.query.implemented.interfaces"),
    INHERITING_CLASSES(false, "relationship.query.inheriting.classes");

    private final boolean methodRequired;
    private final String messageKey;

    RelationshipQuery(boolean methodRequired, String messageKey) {
        this.methodRequired = methodRequired;
        this.messageKey = messageKey;
    }

    public boolean methodRequired() {
        return methodRequired;
    }

    public String messageKey() {
        return messageKey;
    }
}
