import { useCoverage, useTests } from '../../hooks/queries'
import type { CoverageRecord } from '../../types/api'
import ErrorState from '../ui/ErrorState'
import StatusLabel from '../ui/StatusLabel'

const percent = (covered: number | null, missed: number | null): string =>
  covered == null || missed == null || covered + missed === 0 ? '—' : `${Math.round((covered / (covered + missed)) * 100)}%`

/** Test inventory and coverage. Coverage appears only from a real report: otherwise a clear "unavailable" state, never 0%. */
export default function TestsSection({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const tests = useTests(repositoryId, snapshotId)
  const coverage = useCoverage(repositoryId, snapshotId)

  return (
    <div className="space-y-6">
      <section aria-label="Test inventory">
        <h3 className="text-base font-semibold">Tests</h3>
        {tests.isPending && <p role="status">Loading tests…</p>}
        {tests.error && <ErrorState title="Could not load tests" message={tests.error.message} />}
        {tests.data &&
          (tests.data.records.length === 0 ? (
            <p className="mt-2 text-sm text-slate-600">No tests were discovered in this repository.</p>
          ) : (
            <div className="mt-2 space-y-3 text-sm">
              <p>
                {tests.data.testClasses} test {tests.data.testClasses === 1 ? 'class or file' : 'classes or files'}
                {tests.data.testMethods != null && `, ${tests.data.testMethods} test methods`}
              </p>
              {tests.data.methodsByFramework && (
                <ul className="flex flex-wrap gap-2" aria-label="Methods by framework">
                  {Object.entries(tests.data.methodsByFramework).map(([framework, count]) => (
                    <li key={framework} className="rounded bg-slate-100 px-2 py-0.5">
                      {framework}: {count}
                    </li>
                  ))}
                </ul>
              )}
              <table className="w-full bg-white text-left">
                <thead className="border-b border-slate-200 text-xs text-slate-500">
                  <tr>
                    <th className="p-2">Test</th>
                    <th className="p-2">Methods</th>
                    <th className="p-2">Framework</th>
                    <th className="p-2">Tests (by naming convention)</th>
                  </tr>
                </thead>
                <tbody>
                  {tests.data.records.map((record) => (
                    <tr key={record.testFilePath + (record.testSymbol ?? '')} className="border-b border-slate-100">
                      <td className="p-2 font-mono text-xs">{record.testSymbol ?? record.testFilePath}</td>
                      <td className="p-2">{record.testMethodCount ?? '—'}</td>
                      <td className="p-2">{record.framework ?? '—'}</td>
                      <td className="p-2 font-mono text-xs">{record.productionSymbol ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {tests.data.untestedClasses.length > 0 && (
                <div>
                  <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                    No test found by naming convention
                  </h4>
                  <ul className="mt-1 font-mono text-xs">
                    {tests.data.untestedClasses.map((name) => (
                      <li key={name}>{name}</li>
                    ))}
                  </ul>
                  <p className="mt-1 text-xs text-slate-600">These classes may still be tested under another name.</p>
                </div>
              )}
            </div>
          ))}
      </section>

      <section aria-label="Coverage">
        <h3 className="text-base font-semibold">Coverage</h3>
        {coverage.isPending && <p role="status">Loading coverage…</p>}
        {coverage.error && <ErrorState title="Could not load coverage" message={coverage.error.message} />}
        {coverage.data && coverage.data.status !== 'COMPLETED' && (
          <div className="mt-2 flex items-center gap-2 text-sm">
            <StatusLabel status={coverage.data.status} />
            <span>{coverage.data.message}</span>
          </div>
        )}
        {coverage.data && coverage.data.status === 'COMPLETED' && (
          <div className="mt-2 space-y-2 text-sm">
            <p className="text-slate-600">{coverage.data.message}</p>
            <table className="w-full bg-white text-left">
              <thead className="border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="p-2">Scope</th>
                  <th className="p-2">Name</th>
                  <th className="p-2">Lines</th>
                  <th className="p-2">Branches</th>
                </tr>
              </thead>
              <tbody>
                {coverage.data.records.map((record: CoverageRecord) => (
                  <tr key={record.scope + record.name} className="border-b border-slate-100">
                    <td className="p-2">{record.scope.toLowerCase()}</td>
                    <td className="p-2 font-mono text-xs">{record.name}</td>
                    <td className="p-2">{percent(record.lineCovered, record.lineMissed)}</td>
                    <td className="p-2">{percent(record.branchCovered, record.branchMissed)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
