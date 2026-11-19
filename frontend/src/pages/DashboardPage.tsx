import { useState } from 'react'
import { Link } from 'react-router'
import EmptyState from '../components/ui/EmptyState'
import ErrorState from '../components/ui/ErrorState'
import RunStatusLabel from '../components/ui/RunStatusLabel'
import { useRepositories } from '../hooks/queries'
import { formatDateTime, shortSha } from '../lib/format'

const PAGE_SIZE = 20

export default function DashboardPage() {
  const [page, setPage] = useState(0)
  const { data, error, isPending } = useRepositories(page, PAGE_SIZE)

  if (isPending) {
    return <p role="status">Loading repositories…</p>
  }
  if (error) {
    return <ErrorState title="Could not load repositories" message={error.message} />
  }
  if (data.totalElements === 0) {
    return (
      <EmptyState
        title="No repositories analyzed yet"
        description="Submit a public GitHub repository to see its technology stack, structure and findings."
        action={
          <Link to="/repositories/new" className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white">
            Add a repository
          </Link>
        }
      />
    )
  }

  return (
    <div>
      <h2 className="text-xl font-semibold">Repositories</h2>
      <table className="mt-4 w-full bg-white text-left text-sm">
        <thead className="border-b border-slate-200 text-xs text-slate-500">
          <tr>
            <th className="p-2">Repository</th>
            <th className="p-2">URL</th>
            <th className="p-2">Latest commit</th>
            <th className="p-2">Last analyzed</th>
            <th className="p-2">Status</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((repository) => {
            const snapshot = repository.latestSnapshot
            return (
              <tr key={repository.id} className="border-b border-slate-100">
                <td className="p-2 font-medium">
                  <Link to={`/repositories/${repository.id}`} className="text-blue-700 hover:underline">
                    {repository.owner}/{repository.name}
                  </Link>
                </td>
                <td className="p-2">
                  <a href={repository.canonicalUrl} className="text-slate-600 hover:underline" rel="noreferrer">
                    {repository.canonicalUrl}
                  </a>
                </td>
                <td className="p-2 font-mono text-xs">{snapshot ? shortSha(snapshot.commitSha) : '—'}</td>
                <td className="p-2">{formatDateTime(snapshot?.latestRun?.completedAt)}</td>
                <td className="p-2">
                  <RunStatusLabel status={snapshot?.latestRun?.status} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {data.totalPages > 1 && (
        <div className="mt-3 flex items-center gap-3 text-sm">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
            className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Previous
          </button>
          <span>
            Page {page + 1} of {data.totalPages}
          </span>
          <button
            type="button"
            disabled={page + 1 >= data.totalPages}
            onClick={() => setPage(page + 1)}
            className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
