package com.repointelligence.architecture.domain;

/**
 * Whether the target of an edge is known. {@code RESOLVED}: exactly one symbol of the snapshot.
 * {@code UNRESOLVED}: not in the snapshot (typically a third-party type; only its name is kept).
 * {@code AMBIGUOUS}: several symbols match and none was picked. Targets are never guessed.
 */
public enum EdgeResolution { RESOLVED, UNRESOLVED, AMBIGUOUS }
