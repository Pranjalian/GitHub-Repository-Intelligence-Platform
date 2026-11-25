import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import IndexStatusPanel from '../components/rag/IndexStatusPanel'
import SearchResultsList from '../components/rag/SearchResultsList'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import { Badge, Button, EmptyState, Input, SegmentedControl, Select } from '../components/ui'
import { Notice, SkeletonRows, UnavailableState } from '../components/ui/Layout'
import { useIndexStatus, useSearch } from '../hooks/queries'
import { isIndexSearchable, type SearchParams, type SearchType } from '../types/api'

const FILE_TYPES = ['', 'SOURCE', 'TEST', 'CONFIG', 'BUILD', 'DOCS', 'RESOURCE']
const FILE_TYPE_LABEL: Record<string, string> = { '': 'All', SOURCE: 'Source', TEST: 'Test', CONFIG: 'Config', BUILD: 'Build', DOCS: 'Docs', RESOURCE: 'Resource' }
const SOURCE_LABEL = { SYMBOL_GRAPH: 'symbol graph', CHUNK_NAMES: 'chunk names', KEYWORD_INDEX: 'keyword index' } as const

/** `/repositories/:repositoryId/search?snapshot=`: deterministic symbol and text search, always available. */
export default function SearchPage() {
  return (
    <SnapshotPageFrame title="Code Search" subtitle="deterministic: no AI model is involved">
      {({ repositoryId, snapshotId, commitSha }) => <SearchView repositoryId={repositoryId} snapshotId={snapshotId} commitSha={commitSha} />}
    </SnapshotPageFrame>
  )
}

function SearchView({ repositoryId, snapshotId, commitSha }: { repositoryId: string; snapshotId: string; commitSha: string }) {
  const [urlParams, setUrlParams] = useSearchParams()
  const q = urlParams.get('q') ?? ''
  const type = (urlParams.get('type') === 'symbol' ? 'symbol' : 'text') as SearchType
  const language = urlParams.get('lang') ?? ''
  const module = urlParams.get('module') ?? ''
  const fileType = urlParams.get('ftype') ?? ''
  const [draft, setDraft] = useState(q)
  const params: SearchParams | null = q ? { q, type, language, module, fileType } : null
  const search = useSearch(repositoryId, snapshotId, params)
  const index = useIndexStatus(repositoryId, snapshotId)
  const indexed = isIndexSearchable(index.data?.status)

  function set(next: Record<string, string>) {
    const copy = new URLSearchParams(urlParams)
    for (const [key, value] of Object.entries(next)) {
      if (value === '' || (key === 'type' && value === 'text')) copy.delete(key)
      else copy.set(key, value)
    }
    setUrlParams(copy, { replace: true })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const text = draft.trim()
    if (text) set({ q: text })
  }

  const data = search.data
  const symbolCount = data?.results.length ?? 0

  return (
    <div className="space-y-4">
      <IndexStatusPanel repositoryId={repositoryId} snapshotId={snapshotId} />
      <form onSubmit={submit} className="space-y-3 rounded-lg border border-line bg-surface p-3" role="search">
        <div className="flex flex-wrap items-center gap-2">
          <label className="sr-only" htmlFor="search-query">
            Search query
          </label>
          <Input
            id="search-query"
            icon="search"
            hint="/"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            maxLength={500}
            placeholder={type === 'symbol' ? 'Identifier, e.g. OwnerController' : 'Words to find, e.g. owner validation'}
            className="min-w-0 flex-1"
          />
          <SegmentedControl<SearchType> label="Search type" value={type} onChange={(v) => set({ type: v })} options={[{ value: 'symbol', label: 'Symbol' }, { value: 'text', label: 'Text' }]} />
          <Button type="submit" variant="primary" icon="search">
            Search
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-body-md">
          <Input aria-label="Language" placeholder="Lang: any" value={language} onChange={(e) => set({ lang: e.target.value })} className="w-32" />
          <Input aria-label="Module" placeholder="Module: any" value={module} onChange={(e) => set({ module: e.target.value })} className="w-32" />
          <Select label="File type" value={fileType} onChange={(e) => set({ ftype: e.target.value })}>
            {FILE_TYPES.map((value) => (
              <option key={value} value={value}>
                {FILE_TYPE_LABEL[value]}
              </option>
            ))}
          </Select>
        </div>
        <p className="text-body-sm text-muted">
          <strong>Symbol</strong> finds exact identifiers (a class, method or field, e.g. <code className="font-mono">OwnerController</code> or <code className="font-mono">Owner.getPets</code>) right after the analysis. <strong>Text</strong> searches the snapshot&apos;s index (all words must match; identifiers also match by their camelCase parts).
        </p>
      </form>

      {search.isFetching && <SkeletonRows rows={2} label="Searching" />}
      {search.error && (
        <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">
          {search.error.message}
        </p>
      )}
      {!q && !indexed && index.data && (
        <UnavailableState title="Not indexed" reason="Symbol search works from the analysis right away. Text search needs the snapshot index: use Index this snapshot above." />
      )}
      {!q && (
        <EmptyState icon="search" title="Search this commit" description="Type an identifier or words, choose Symbol or Text, and search. Results link to GitHub at this exact commit." />
      )}
      {data && !search.isFetching && (
        <section aria-label="Search results" className="space-y-2">
          {data.status !== 'OK' && <Notice tone="warning"><span role="note">{data.reason}</span></Notice>}
          {data.status === 'OK' && data.reason && <p className="text-body-sm text-muted">{data.reason}</p>}
          {data.status === 'OK' && (
            <p className="flex flex-wrap items-center gap-2 text-body-md text-fg-2">
              {symbolCount === 0 ? (
                <>No results for &quot;{data.query}&quot;. Try fewer words, an exact identifier, or switch between Symbol and Text.</>
              ) : (
                <>
                  Showing {symbolCount} {data.type === 'SYMBOL' ? 'symbol' : 'text'} {symbolCount === 1 ? 'match' : 'matches'} for <code className="font-mono">{data.query}</code>
                </>
              )}
              <Badge tone="neutral">Commit {(data.commitSha ?? commitSha).slice(0, 7).toUpperCase()}</Badge>
              {data.source && <Badge tone="info">{SOURCE_LABEL[data.source]}</Badge>}
            </p>
          )}
          <SearchResultsList results={data.results} terms={data.terms} />
        </section>
      )}
    </div>
  )
}
