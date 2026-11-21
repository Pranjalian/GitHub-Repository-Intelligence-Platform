package com.repointelligence.architecture.domain;

/**
 * How a layer was assigned. {@code ANNOTATION}: a Spring/JPA stereotype annotation. {@code TYPE_HIERARCHY}:
 * extends a Spring Data repository interface. {@code API_SIGNATURE}: used as a request or response type of
 * an endpoint. {@code PACKAGE_HEURISTIC}: only the package name suggests it.
 */
public enum LayerMethod { ANNOTATION, TYPE_HIERARCHY, API_SIGNATURE, PACKAGE_HEURISTIC }
