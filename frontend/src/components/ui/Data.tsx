import { type ReactNode } from 'react'
import Button from './Button'
import Icon from './icons'

export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  className?: string
}

/** Dense table: sticky header, hover tint, fixed row height so updates cause no layout shift. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  onRowClick,
  selectedKey,
  dense,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  caption: string
  onRowClick?: (row: T) => void
  selectedKey?: string | null
  dense?: boolean
}) {
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full border-collapse text-left text-body-md">
        <caption className="sr-only">{caption}</caption>
        <thead className="sticky top-0 bg-raised">
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={`whitespace-nowrap px-3 py-2 font-mono text-label-sm font-medium uppercase tracking-wider text-muted ${c.className ?? ''}`}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = rowKey(row)
            return (
              <tr
                key={key}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                aria-selected={selectedKey === key || undefined}
                className={`border-t border-line hover:bg-raised ${onRowClick ? 'cursor-pointer' : ''} ${selectedKey === key ? 'bg-raised' : ''} ${dense ? 'h-7' : 'h-8'}`}
              >
                {columns.map((c) => (
                  <td key={c.key} className={`px-3 py-1.5 align-middle ${c.className ?? ''}`}>
                    {c.cell(row)}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function Pagination({ page, totalPages, totalElements, shown, onPage }: { page: number; totalPages: number; totalElements: number; shown: number; onPage: (page: number) => void }) {
  if (totalElements === 0) return null
  const from = page * Math.max(shown, 1) + 1
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-2 text-body-md text-fg-2">
      <span>
        Showing {from}-{Math.min(from + shown - 1, totalElements)} of {totalElements}
      </span>
      <span className="flex items-center gap-2">
        <Button variant="secondary" disabled={page <= 0} onClick={() => onPage(page - 1)} icon="chevron-left">
          Prev
        </Button>
        <span aria-live="polite">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next
          <Icon name="chevron-right" size={14} />
        </Button>
      </span>
    </nav>
  )
}
