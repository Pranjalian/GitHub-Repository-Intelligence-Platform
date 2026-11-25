import CopyButton from './CopyButton'

/**
 * Plain-text code with line numbers. Repository text and model output are only ever rendered as text
 * nodes here. `highlight` marks lines (1-based, relative to `startLine`); `diff` colours +/- lines.
 */
export default function CodeBlock({
  code,
  startLine = 1,
  highlight = [],
  diff,
  label,
  copy = true,
  lineNumbers = true,
  emphasize = [],
}: {
  code: string
  startLine?: number
  highlight?: number[]
  diff?: boolean
  label?: string
  copy?: boolean
  lineNumbers?: boolean
  /** Terms to emphasise inside lines (as <mark> elements; the text itself stays text). */
  emphasize?: string[]
}) {
  const lines = code.replace(/\n$/, '').split('\n')
  return (
    <figure className="overflow-hidden rounded-lg border border-line bg-code">
      {(label || copy) && (
        <figcaption className="flex items-center justify-between gap-2 border-b border-line px-3 py-1 font-mono text-label-sm text-muted">
          <span>{label}</span>
          {copy && <CopyButton value={code} label={diff ? 'Copy diff' : 'Copy'} />}
        </figcaption>
      )}
      <pre className="overflow-x-auto py-2 text-code-md">
        <code>
          {lines.map((line, i) => {
            const number = startLine + i
            const marked = highlight.includes(number)
            const tone = diff ? (line.startsWith('+') ? 'bg-[color-mix(in_srgb,var(--t-success)_14%,transparent)]' : line.startsWith('-') ? 'bg-[color-mix(in_srgb,var(--t-failed)_14%,transparent)]' : '') : ''
            return (
              <div key={i} className={`flex px-3 ${tone} ${marked ? 'bg-[color-mix(in_srgb,var(--t-warning)_16%,transparent)]' : ''}`} data-highlighted={marked || undefined}>
                {lineNumbers && <span aria-hidden="true" className="mr-3 inline-block w-8 shrink-0 select-none text-right text-muted">{number}</span>}
                {marked && <span aria-hidden="true" className="mr-1 select-none text-[color:var(--t-warning-fg)]">&gt;&gt;&gt;</span>}
                <span className="whitespace-pre">{emphasize.length > 0 ? markTerms(line, emphasize) : line || ' '}</span>
              </div>
            )
          })}
        </code>
      </pre>
    </figure>
  )
}

/** Splits a line on the terms (case-insensitive) and wraps matches in <mark>: React nodes only, never HTML. */
export function markTerms(line: string, terms: string[]) {
  const escaped = terms.filter((t) => t.length > 0).map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  if (escaped.length === 0) return line || ' '
  const parts = line.split(new RegExp(`(${escaped.join('|')})`, 'i'))
  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <mark key={i} className="rounded-sm bg-[color-mix(in_srgb,var(--t-warning)_35%,transparent)] px-0.5 text-inherit">
        {part}
      </mark>
    ) : (
      part
    ),
  )
}
