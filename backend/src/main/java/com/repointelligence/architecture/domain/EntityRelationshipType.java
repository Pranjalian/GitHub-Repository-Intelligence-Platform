package com.repointelligence.architecture.domain;

/** JPA association annotations, plus the repository-to-entity mapping read from repository generics. */
public enum EntityRelationshipType { ONE_TO_MANY, MANY_TO_ONE, ONE_TO_ONE, MANY_TO_MANY, REPOSITORY_FOR }
