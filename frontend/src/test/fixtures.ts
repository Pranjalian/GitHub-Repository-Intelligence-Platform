import type {
  AnalysisRun,
  ArchitectureReport,
  CapabilityMatrixRow,
  ConsolidatedReport,
  Repository,
  RunStatus,
  Snapshot,
} from '../types/api'

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

const SRC = `https://github.com/octo/demo/blob/${'a'.repeat(40)}/src/main/java/com/example/shop`

/** A Spring Boot architecture report shaped like the backend's for its evaluation fixture. */
export const architectureReport: ArchitectureReport = {
  repositoryId: REPO_ID,
  snapshotId: SNAP_NEW,
  runId: RUN_ID,
  commitSha: SHA_NEW,
  supported: true,
  status: 'COMPLETED_WITH_WARNINGS',
  analyzers: [{ id: 'spring-architecture-analyzer', version: '1.0.0', status: 'COMPLETED_WITH_WARNINGS', capabilityLevel: 'DEEP' }],
  unsupported: [],
  summary: {
    types: 27,
    methods: 60,
    constructors: 8,
    fields: 30,
    relationships: 210,
    resolvedRelationships: 120,
    unresolvedRelationships: 88,
    ambiguousRelationships: 2,
    endpoints: 2,
    layeredTypes: 26,
    entities: 2,
  },
  layers: [
    {
      layer: 'CONTROLLER',
      total: 1,
      classes: [
        {
          name: 'OrderController',
          fullyQualifiedName: 'com.example.shop.web.OrderController',
          filePath: 'src/main/java/com/example/shop/web/OrderController.java',
          line: 15,
          confidence: 'DETECTED',
          method: 'ANNOTATION',
          evidence: [{ path: 'src/main/java/com/example/shop/web/OrderController.java', line: 15, signal: '@RestController' }],
          sourceUrl: `${SRC}/web/OrderController.java#L15-L60`,
        },
      ],
    },
    {
      layer: 'SERVICE',
      total: 2,
      classes: [
        {
          name: 'OrderService',
          fullyQualifiedName: 'com.example.shop.service.OrderService',
          filePath: 'src/main/java/com/example/shop/service/OrderService.java',
          line: 12,
          confidence: 'DETECTED',
          method: 'ANNOTATION',
          evidence: [{ path: 'src/main/java/com/example/shop/service/OrderService.java', line: 12, signal: '@Service' }],
          sourceUrl: `${SRC}/service/OrderService.java#L12-L70`,
        },
        {
          name: 'PriceCalculator',
          fullyQualifiedName: 'com.example.shop.service.PriceCalculator',
          filePath: 'src/main/java/com/example/shop/service/PriceCalculator.java',
          line: 3,
          confidence: 'LIKELY',
          method: 'PACKAGE_HEURISTIC',
          evidence: [{ path: 'src/main/java/com/example/shop/service/PriceCalculator.java', line: 3, signal: 'package com.example.shop.service' }],
          sourceUrl: null,
        },
      ],
    },
  ],
  endpoints: [
    {
      httpMethod: 'POST',
      route: '/api/orders',
      routeResolved: true,
      controller: 'com.example.shop.web.OrderController',
      handler: 'OrderController.create(CreateOrderRequest)',
      requestType: 'com.example.shop.web.CreateOrderRequest',
      responseType: 'com.example.shop.web.OrderResponse',
      filePath: 'src/main/java/com/example/shop/web/OrderController.java',
      line: 34,
      sourceUrl: `${SRC}/web/OrderController.java#L34`,
    },
    {
      httpMethod: 'GET',
      route: '/api/orders/{ExternalRoutes.LEGACY}',
      routeResolved: false,
      controller: 'com.example.shop.web.OrderController',
      handler: 'OrderController.legacy()',
      requestType: null,
      responseType: 'String',
      filePath: 'src/main/java/com/example/shop/web/OrderController.java',
      line: 49,
      sourceUrl: `${SRC}/web/OrderController.java#L49`,
    },
  ],
  entities: [
    {
      name: 'Order',
      fullyQualifiedName: 'com.example.shop.domain.Order',
      table: 'orders',
      filePath: 'src/main/java/com/example/shop/domain/Order.java',
      line: 14,
      sourceUrl: `${SRC}/domain/Order.java#L14-L58`,
      relationships: [
        { field: 'customer', type: 'MANY_TO_ONE', target: 'com.example.shop.domain.Customer', targetResolved: true, attributes: { fetch: 'FetchType.LAZY' } },
        { field: 'lines', type: 'ONE_TO_MANY', target: 'com.example.shop.domain.OrderLine', targetResolved: true, attributes: { mappedBy: 'order' } },
      ],
      repositories: ['com.example.shop.repository.OrderRepository'],
    },
  ],
  injections: [
    {
      from: 'com.example.shop.web.OrderController',
      to: 'com.example.shop.service.OrderService',
      resolution: 'RESOLVED',
      confidence: 'DETECTED',
      via: 'constructor',
      member: 'orderService',
      filePath: 'src/main/java/com/example/shop/web/OrderController.java',
      line: 21,
      sourceUrl: `${SRC}/web/OrderController.java#L21`,
    },
    {
      from: 'com.example.shop.service.OrderService',
      to: 'org.springframework.web.client.RestTemplate',
      resolution: 'UNRESOLVED',
      confidence: 'DETECTED',
      via: 'constructor',
      member: 'rest',
      filePath: 'src/main/java/com/example/shop/service/OrderService.java',
      line: 30,
      sourceUrl: null,
    },
  ],
  diChains: [
    [
      { name: 'OrderController', fullyQualifiedName: 'com.example.shop.web.OrderController', layer: 'CONTROLLER' },
      { name: 'OrderService', fullyQualifiedName: 'com.example.shop.service.OrderService', layer: 'SERVICE' },
      { name: 'OrderRepository', fullyQualifiedName: 'com.example.shop.repository.OrderRepository', layer: 'REPOSITORY' },
      { name: 'Order', fullyQualifiedName: 'com.example.shop.domain.Order', layer: 'ENTITY' },
    ],
  ],
  moduleDependencies: [{ source: 'web', target: 'core', evidence: [{ path: 'web/pom.xml', line: 12, signal: 'depends on com.example:core (compile)' }] }],
  cycles: [
    {
      rule: 'injection-cycle',
      severity: 'MEDIUM',
      confidence: 'DETECTED',
      title: 'Circular dependency injection between AuditService, ReportService',
      description: 'These beans depend on each other through injection.',
      members: ['com.example.shop.service.AuditService', 'com.example.shop.service.ReportService'],
      edges: [
        { from: 'com.example.shop.service.AuditService', to: 'com.example.shop.service.ReportService', relationship: 'INJECTS', file: 'src/main/java/com/example/shop/service/AuditService.java', line: 10 },
        { from: 'com.example.shop.service.ReportService', to: 'com.example.shop.service.AuditService', relationship: 'INJECTS', file: 'src/main/java/com/example/shop/service/ReportService.java', line: 10 },
      ],
      filePath: 'src/main/java/com/example/shop/service/AuditService.java',
      line: 6,
      sourceUrl: null,
    },
  ],
  patterns: [
    {
      pattern: 'STRATEGY',
      confidence: 'LIKELY',
      summary: 'DiscountPolicy has 2 implementations injected together as a java.util.List',
      symbols: ['com.example.shop.service.DiscountPolicy'],
      evidence: {},
    },
    {
      pattern: 'FACTORY',
      confidence: 'POSSIBLE',
      summary: 'NotifierProvider.create returns Notifier and creates 2 different implementations depending on a branch',
      symbols: ['com.example.shop.service.NotifierProvider'],
      evidence: {},
    },
  ],
  diagrams: {
    layers: 'flowchart TB\n  subgraph L1["Controller"]\n    n1["OrderController"]\n  end\n',
    modules: 'flowchart LR\n  m1["web"]\n  m2["core"]\n  m1 --> m2\n',
    apiFlow: 'flowchart LR\n  f1["POST /api/orders"]\n  f2["OrderController"]\n  f1 --> f2\n',
    entities: 'erDiagram\n  Order {\n  }\n',
    notes: ['Layer diagram: 40 of 120 classes are shown (the most connected first).'],
  },
  limitations: [
    'There is no classpath: third-party types are not resolved.',
    '90 of 210 relationships are not resolved to a symbol of this commit; diagrams and chains use resolved ones only.',
  ],
}

/** What the backend returns for a repository without an architecture analyzer (e.g. React). */
export const unsupportedArchitecture: ArchitectureReport = {
  ...architectureReport,
  supported: false,
  status: 'NOT_SUPPORTED',
  analyzers: [],
  unsupported: ['JavaScript: no analyzer pack for JavaScript', 'TypeScript: no analyzer pack for TypeScript'],
  summary: { types: 0 },
  layers: [],
  endpoints: [],
  entities: [],
  injections: [],
  diChains: [],
  moduleDependencies: [],
  cycles: [],
  patterns: [],
  diagrams: { layers: null, modules: null, apiFlow: null, entities: null, notes: [] },
  limitations: ['Architecture not supported for TypeScript: no analyzer pack for TypeScript'],
}
