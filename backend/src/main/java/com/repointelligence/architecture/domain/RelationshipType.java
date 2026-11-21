package com.repointelligence.architecture.domain;

/** How a source symbol refers to a target, as read from the code. */
public enum RelationshipType { IMPORTS, INJECTS, CALLS, EXTENDS, IMPLEMENTS, FIELD_TYPE, PARAM_TYPE, RETURN_TYPE }
