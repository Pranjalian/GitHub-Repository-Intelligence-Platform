/** Mirrors the backend ExecutionStatus enum (Problem Statement section 12.3 honest labels). */
export type ExecutionStatus =
  | 'COMPLETED'
  | 'COMPLETED_WITH_WARNINGS'
  | 'NOT_CONFIGURED'
  | 'NOT_SUPPORTED'
  | 'UNAVAILABLE'
  | 'SKIPPED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED'
  | 'NOT_ANALYZED'
