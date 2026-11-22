import { useState, type FormEvent } from 'react'
import IndexStatusPanel from '../components/rag/IndexStatusPanel'
import SearchResultsList from '../components/rag/SearchResultsList'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import { useSearch } from '../hooks/queries'
import type { SearchParams, SearchType } from '../types/api'

const FILE_TYPES = ['', 'SOURCE', 'TEST', 'CONFIG', 'BUILD', 'DOCS', 'RESOURCE']

/** `/repositories/:repositoryId/search?snapshot=`: deterministic symbol and text search, always available. */
export default function SearchPage() {
  return (
    <SnapshotPageFrame title="Search">
      {({ repositoryId, snapshotId }) => <SearchView repositoryId={repositoryId} snapshotId={snapshotId} />}
    </SnapshotPageFrame>
  )
}

function SearchView({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const [text, setText] = useState('')
  const [type, setType] = useState<SearchType>('text')
  const [language, setLanguage] = useState('')
  const [module, setModule] = useState('')
  const [fileType, setFileType] = useState('')
  const [params, setParams] = useState<SearchParams | null>(null)
  const search = useSearch(repositoryId, snapshotId, params)

  function submit(event: FormEvent) {
    event.preventDefault()
    const q = text.trim()
    if (q) {
      setParams({ q, type, language: language.trim(), module: module.trim(), fileType })
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-600">
        Deterministic search: no AI model is involved. <strong>Symbol</strong> finds exact identifiers (a class, method or
        field, e.g. <code>OwnerController</code> or <code>Owner.getPets</code>) right after the analysis.{' '}
        <strong>Text</strong> searches the snapshot's index (all words must match; identifiers also match by their
        camelCase parts).
      </p>
      <IndexStatusPanel repositoryId={repositoryId} snapshotId={snapshotId} />
      <form onSubmit={submit} className="space-y-2 rounded border border-slate-200 bg-white p-3" role="search">
        <div className="flex flex-wrap gap-2">
          <label className="sr-only" htmlFor="search-query">
            Search query
          </label>
          <input
            id="search-query"
            value={text}
            onChange={(event) => setText(event.target.value)}
            maxLength={500}
            placeholder={type === 'symbol' ? 'Identifier, e.g. OwnerController' : 'Words to find, e.g. owner validation'}
            className="min-w-0 flex-1 rounded border border-slate-300 px-3 py-1.5 text-sm"
          />
          <label className="sr-only" htmlFor="search-type">
            Search type
          </label>
          <select
            id="search-type"
            value={type}
            onChange={(event) => setType(event.target.value as SearchType)}
            className="rounded border border-slate-300 px-2 py-1.5 text-sm"
          >
            <option value="text">Text</option>
            <option value="symbol">Symbol</option>
          </select>
          <button type="submit" className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700">
            Search
          </button>
        </div>
        <div className="flex flex-wrap gap-2 text-xs">
          <label className="flex items-center gap-1">
            Language
            <input
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
              placeholder="any"
              className="w-28 rounded border border-slate-300 px-2 py-1"
            />
          </label>
          <label className="flex items-center gap-1">
            Module
            <input
              value={module}
              onChange={(event) => setModule(event.target.value)}
              placeholder="any"
              className="w-28 rounded border border-slate-300 px-2 py-1"
            />
          </label>
          <label className="flex items-center gap-1">
            File type
            <select
              value={fileType}
              onChange={(event) => setFileType(event.target.value)}
              className="rounded border border-slate-300 px-2 py-1"
            >
              {FILE_TYPES.map((value) => (
                <option key={value} value={value}>
                  {value === '' ? 'any' : value}
                </option>
              ))}
            </select>
          </label>
        </div>
      </form>

      {search.isFetching && <p role="status" className="text-sm text-slate-600">Searching…</p>}
      {search.error && (
        <p role="alert" className="text-sm text-red-700">
          {search.error.message}
        </p>
      )}
      {search.data && !search.isFetching && (
        <section aria-label="Search results" className="space-y-2">
          {search.data.status !== 'OK' && (
            <p role="note" className="rounded bg-amber-50 p-2 text-sm text-amber-900">
              {search.data.reason}
            </p>
          )}
          {search.data.status === 'OK' && search.data.reason && <p className="text-xs text-slate-600">{search.data.reason}</p>}
          {search.data.status === 'OK' && (
            <p className="text-xs text-slate-600">
              {search.data.results.length === 0
                ? `No results for "${search.data.query}".`
                : `${search.data.results.length} result(s) at commit ${search.data.commitSha.slice(0, 7)}.`}
            </p>
          )}
          <SearchResultsList results={search.data.results} />
        </section>
      )}
    </div>
  )
}
