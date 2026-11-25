import { useState } from 'react'
import { useCoverage, useTests } from '../../hooks/queries'
import type { CoverageRecord } from '../../types/api'
import Button from '../ui/Button'
import { SegmentedBar } from '../ui/Charts'
import ErrorState from '../ui/ErrorState'
import Icon from '../ui/icons'
import { Card, Notice, SkeletonRows, StatCard, UnavailableState } from '../ui/Layout'
import { DataTable } from '../ui/Data'
import StatusLabel from '../ui/StatusLabel'

const percent = (covered: number | null, missed: number | null): number | null =>
  covered == null || missed == null || covered + missed === 0 ? null : Math.round((covered / (covered + missed)) * 1000) / 10

function CoverageRow({ label, covered, missed }: { label: string; covered: number | null; missed: number | null }) {
  const value = percent(covered, missed)
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-body-md">
        <span>{label}</span>
        <span className="font-mono">{value === null ? 'Not reported' : `${value}%`}</span>
      </div>
      {value !== null && <SegmentedBar label={`${label} coverage`} segments={[{ label: 'covered', value: covered ?? 0, color: 'var(--t-success)' }, { label: 'missed', value: missed ?? 0, color: 'var(--c-overlay)' }]} />}
    </div>
  )
}

/** Test inventory and coverage. Coverage appears only from a real committed report: otherwise a clear "unavailable" state, never 0%. */
export default function TestsSection({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const tests = useTests(repositoryId, snapshotId)
  const coverage = useCoverage(repositoryId, snapshotId)
  const [open, setOpen] = useState<string | null>(null)

  return (
    <div className="space-y-5">
      <h2 className="text-headline-lg font-semibold tracking-tight">Tests & Coverage</h2>
      <section aria-label="Test inventory" className="space-y-3">
        <h3 className="text-headline-md font-semibold">Tests</h3>
        {tests.isPending && <SkeletonRows rows={2} label="Loading tests" />}
        {tests.error && <ErrorState title="Could not load tests" message={tests.error.message} action={<Button onClick={() => tests.refetch()}>Retry</Button>} />}
        {tests.data &&
          (tests.data.records.length === 0 ? (
            <UnavailableState title="No tests were discovered" reason="No test files were found in this repository." />
          ) : (
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-3">
                <StatCard label="Test classes" value={tests.data.testClasses ?? 'Not analyzed'} sub={tests.data.testClasses === 1 ? 'class or file' : 'classes or files'} />
                <StatCard label="Test methods" value={tests.data.testMethods ?? 'Not analyzed'} footer="Static inventory only; tests are not run" />
                <StatCard
                  label="Methods by framework"
                  value={
                    tests.data.methodsByFramework ? (
                      <ul className="space-y-0.5 text-body-md font-normal" aria-label="Methods by framework">
                        {Object.entries(tests.data.methodsByFramework).map(([framework, count]) => (
                          <li key={framework}>
                            {framework}: {count}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      'Not counted'
                    )
                  }
                />
              </div>
              <DataTable
                caption="Test inventory"
                rows={tests.data.records}
                rowKey={(r) => r.testFilePath + (r.testSymbol ?? '')}
                columns={[
                  {
                    key: 'test',
                    header: 'Test class',
                    cell: (r) => (
                      <span className="font-mono text-body-sm">
                        {r.testSymbol ?? r.testFilePath}
                        <span className="block text-muted">{r.testFilePath}</span>
                      </span>
                    ),
                  },
                  { key: 'fw', header: 'Framework', cell: (r) => r.framework ?? '—' },
                  {
                    key: 'm',
                    header: 'Methods',
                    cell: (r) => (
                      <button type="button" className="inline-flex items-center gap-1 text-primary-text hover:underline" aria-expanded={open === r.testFilePath} onClick={() => setOpen(open === r.testFilePath ? null : r.testFilePath)}>
                        <Icon name={open === r.testFilePath ? 'chevron-down' : 'chevron-right'} size={12} />
                        {r.testMethodCount ?? '—'}
                      </button>
                    ),
                  },
                  { key: 'prod', header: 'Tests (by naming convention)', cell: (r) => <span className="font-mono text-body-sm">{r.productionSymbol ?? '—'}</span> },
                ]}
              />
              {open && <p className="text-body-sm text-muted">Method names are not stored in the inventory; only the count per class is known.</p>}
              {tests.data.untestedClasses.length > 0 && (
                <Card title="Production classes without an obvious test (by naming convention)" tier={2}>
                  <ul className="columns-2 font-mono text-body-sm">
                    {tests.data.untestedClasses.map((name) => (
                      <li key={name}>{name}</li>
                    ))}
                  </ul>
                  <p className="mt-2 text-body-sm text-muted">These classes may still be tested under another name.</p>
                </Card>
              )}
            </div>
          ))}
      </section>

      <section aria-label="Coverage" className="space-y-3">
        <h3 className="text-headline-md font-semibold">Coverage</h3>
        {coverage.isPending && <SkeletonRows rows={1} label="Loading coverage" />}
        {coverage.error && <ErrorState title="Could not load coverage" message={coverage.error.message} action={<Button onClick={() => coverage.refetch()}>Retry</Button>} />}
        {coverage.data && coverage.data.status !== 'COMPLETED' && (
          <UnavailableState
            icon="eye"
            title="Coverage unavailable: no committed JaCoCo report"
            reason={coverage.data.message}
            action={<StatusLabel status={coverage.data.status} />}
          />
        )}
        {coverage.data && coverage.data.status === 'COMPLETED' && (
          <div className="space-y-3">
            <Notice>{coverage.data.message}</Notice>
            {(['BUNDLE', 'PACKAGE', 'CLASS'] as const).map((scope) => {
              const records = coverage.data.records.filter((r: CoverageRecord) => r.scope === scope)
              if (records.length === 0) return null
              const sum = (key: 'lineCovered' | 'lineMissed' | 'branchCovered' | 'branchMissed') => records.reduce((a, r) => a + (r[key] ?? 0), 0)
              return (
                <Card key={scope} title={`${scope.charAt(0)}${scope.slice(1).toLowerCase()} scope`} meta={`${records.length} record(s) · ${records[0].reportPath}`}>
                  <div className="grid gap-4 md:grid-cols-2">
                    <CoverageRow label="Lines" covered={sum('lineCovered')} missed={sum('lineMissed')} />
                    <CoverageRow label="Branches" covered={sum('branchCovered')} missed={sum('branchMissed')} />
                  </div>
                  <details className="mt-3">
                    <summary className="cursor-pointer text-body-md">Records</summary>
                    <ul className="mt-1 space-y-0.5 font-mono text-body-sm">
                      {records.map((r) => (
                        <li key={r.scope + r.name}>
                          {r.name}: lines {percent(r.lineCovered, r.lineMissed) ?? '—'}
                          {percent(r.lineCovered, r.lineMissed) !== null ? '%' : ''}, branches {percent(r.branchCovered, r.branchMissed) ?? '—'}
                          {percent(r.branchCovered, r.branchMissed) !== null ? '%' : ''}
                        </li>
                      ))}
                    </ul>
                  </details>
                </Card>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}
