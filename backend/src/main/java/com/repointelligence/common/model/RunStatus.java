package com.repointelligence.common.model;

/** Lifecycle state of an analysis run. */
public enum RunStatus { QUEUED, INGESTING, ANALYZING, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, CANCELLED }
