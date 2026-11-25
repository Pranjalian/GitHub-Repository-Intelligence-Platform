const PALETTE = ['#6366f1', '#38bdf8', '#a855f7', '#10b981', '#fbbf24', '#f97316', '#94a3b8']

export const chartColor = (index: number) => PALETTE[index % PALETTE.length]

export interface Slice {
  label: string
  value: number
}

/** Donut of shares (inline SVG, no library). The centre shows the unit, e.g. "files". */
export function Donut({ slices, unit, total }: { slices: Slice[]; unit: string; total: number }) {
  const radius = 42
  const circumference = 2 * Math.PI * radius
  const sum = slices.reduce((acc, s) => acc + s.value, 0)
  let offset = 0
  return (
    <svg viewBox="0 0 120 120" role="img" aria-label={`${total} ${unit}: ${slices.map((s) => `${s.label} ${s.value}`).join(', ')}`} className="mx-auto size-40">
      <circle cx="60" cy="60" r={radius} fill="none" stroke="var(--c-overlay)" strokeWidth="14" />
      {sum > 0 &&
        slices.map((slice, i) => {
          const length = (slice.value / sum) * circumference
          const el = (
            <circle
              key={slice.label}
              cx="60"
              cy="60"
              r={radius}
              fill="none"
              stroke={chartColor(i)}
              strokeWidth="14"
              strokeDasharray={`${length} ${circumference - length}`}
              strokeDashoffset={-offset}
              transform="rotate(-90 60 60)"
            />
          )
          offset += length
          return el
        })}
      <text x="60" y="58" textAnchor="middle" className="fill-[var(--c-fg)] text-[16px] font-semibold">
        {total.toLocaleString()}
      </text>
      <text x="60" y="72" textAnchor="middle" className="fill-[var(--c-muted)] text-[8px] uppercase">
        {unit}
      </text>
    </svg>
  )
}

/** Horizontal stacked bar; segments are labelled by the caller's legend, the bar itself has a text alternative. */
export function SegmentedBar({ segments, label }: { segments: { label: string; value: number; color?: string }[]; label: string }) {
  const sum = segments.reduce((acc, s) => acc + s.value, 0)
  return (
    <div role="img" aria-label={`${label}: ${segments.map((s) => `${s.label} ${s.value}`).join(', ')}`} className="flex h-2 w-full overflow-hidden rounded bg-overlay">
      {sum > 0 &&
        segments.map((s, i) => <div key={s.label} style={{ width: `${(s.value / sum) * 100}%`, background: s.color ?? chartColor(i) }} />)}
    </div>
  )
}

export function BarList({ rows, unit }: { rows: Slice[]; unit: string }) {
  const max = Math.max(1, ...rows.map((r) => r.value))
  return (
    <ul className="space-y-1.5">
      {rows.map((row, i) => (
        <li key={row.label} className="grid grid-cols-[8rem_1fr_auto] items-center gap-2 text-body-md">
          <span className="truncate font-mono text-body-sm text-fg-2">{row.label}</span>
          <span className="h-1.5 rounded bg-overlay">
            <span className="block h-full rounded" style={{ width: `${(row.value / max) * 100}%`, background: chartColor(i) }} />
          </span>
          <span className="font-mono text-body-sm text-fg-2">
            {row.value} {unit}
          </span>
        </li>
      ))}
    </ul>
  )
}
