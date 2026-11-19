import type { AnalysisRun, CapabilityMatrixRow, ConsolidatedReport, Repository, RunStatus, Snapshot } from '../types/api'

export const REPO_ID = '11111111-1111-1111-1111-111111111111'
export const SNAP_NEW = '22222222-2222-2222-2222-222222222222'
export const SNAP_OLD = '33333333-3333-3333-3333-333333333333'
export const RUN_ID = '44444444-4444-4444-4444-444444444444'
export const SHA_NEW = 'a'.repeat(40)
export const SHA_OLD = 'b'.repeat(40)

export function snapshot(id: string, sha: string, status: RunStatus | null, extra: Partial<Snapshot> = {}): Snapshot {
  return {
    id,
    commitSha: sha,
    branch: 'main',
    ingestionStatus: 'INGESTED',
    fileCount: 10,
    totalBytes: 1000,
    ingestedAt: '2026-11-19T10:00:00Z',
    createdAt: '2026-11-19T10:00:00Z',
    latestRun: status
      ? {
          id: RUN_ID,
          status,
          forceRescan: false,
          requestedAt: '2026-11-19T10:00:00Z',
          startedAt: '2026-11-19T10:00:01Z',
          completedAt: status === 'COMPLETED' ? '2026-11-19T10:01:00Z' : null,
          failureReason: null,
        }
      : null,
    ...extra,
  }
}

export function repository(latest: Snapshot | null = snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')): Repository {
  return {
    id: REPO_ID,
    host: 'github.com',
    owner: 'octo',
    name: 'demo',
    canonicalUrl: 'https://github.com/octo/demo',
    defaultBranch: 'main',
    visibility: 'public',
    createdAt: '2026-11-19T10:00:00Z',
    updatedAt: '2026-11-19T10:00:00Z',
    latestSnapshot: latest,
  }
}

export function run(status: RunStatus, extra: Partial<AnalysisRun> = {}): AnalysisRun {
  return {
    runId: RUN_ID,
    repositoryId: REPO_ID,
    snapshotId: SNAP_NEW,
    commitSha: SHA_NEW,
    status,
    failureReason: null,
    retryAfter: null,
    requestedAt: '2026-11-19T10:00:00Z',
    startedAt: null,
    completedAt: null,
    phase: 'QUEUED',
    executions: [],
    ...extra,
  }
}

export const matrix: CapabilityMatrixRow[] = [
  {
    technology: 'Java',
    category: 'STRUCTURE',
    analyzerId: 'generic-structure-analyzer',
    analyzerVersion: '1.0.0',
    capabilityLevel: 'GENERIC',
    executionStatus: 'COMPLETED',
    limitationReason: null,
  },
  {
    technology: 'Rust',
    category: 'CODE_QUALITY',
    analyzerId: null,
    analyzerVersion: null,
    capabilityLevel: 'UNSUPPORTED',
    executionStatus: 'NOT_SUPPORTED',
    limitationReason: 'no analyzer pack for Rust',
  },
  {
    technology: 'Java',
    category: 'SECURITY',
    analyzerId: 'semgrep',
    analyzerVersion: '1.0.0',
    capabilityLevel: 'BASIC',
    executionStatus: 'UNAVAILABLE',
    limitationReason: 'semgrep is not installed',
  },
]

export function report(snapshotId: string, sha: string): ConsolidatedReport {
  return {
    repository: { id: REPO_ID, owner: 'octo', name: 'demo', url: 'https://github.com/octo/demo' },
    snapshot: { id: snapshotId, commitSha: sha, branch: 'main', analyzedAt: '2026-11-19T10:01:00Z' },
    run: { id: RUN_ID, status: 'COMPLETED', completedAt: '2026-11-19T10:01:00Z' },
    technologies: [
      { name: 'Java', kind: 'LANGUAGE', version: null, confidence: 'LIKELY', sharePercent: 80, detectorId: 'x', evidence: [] },
      { name: 'Maven', kind: 'BUILD_TOOL', version: null, confidence: 'DETECTED', sharePercent: null, detectorId: 'x', evidence: [] },
    ],
    structure: {
      modules: [{ name: '(root)', path: '.', buildFilePath: 'pom.xml', ecosystem: 'MAVEN', responsibility: null, evidence: [] }],
      entryPoints: ['src/main/java/DemoApplication.java'],
      summary: { totalFiles: 12, totalBytes: 2048, filesByType: { source: 8, test: 2 }, extensionCounts: { '.java': 8 }, directoryTree: { src: 10 } },
    },
    capabilityMatrix: matrix,
    sections: [],
    limitations: [{ analyzerId: 'semgrep', category: 'SECURITY', status: 'UNAVAILABLE', reason: 'semgrep is not installed' }],
  }
}
