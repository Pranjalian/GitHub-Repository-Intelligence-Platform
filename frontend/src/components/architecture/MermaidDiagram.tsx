import { useEffect, useRef, useState } from 'react'
import { getMermaid } from '../../lib/mermaid'

/**
 * Renders backend-generated Mermaid source. The source is placed as text content and Mermaid renders it
 * in strict mode (see lib/mermaid); this component never sets HTML itself. The source stays available
 * below the diagram, which also serves when rendering fails.
 */
export default function MermaidDiagram({ title, source }: { title: string; source: string }) {
  const container = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    const element = container.current
    if (!element) {
      return
    }
    let active = true
    element.removeAttribute('data-processed')
    element.textContent = source
    getMermaid()
      .then((mermaid) => mermaid.run({ nodes: [element] }))
      .then(() => {
        if (active) {
          setFailed(null)
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setFailed(error instanceof Error ? error.message : 'Unknown error')
        }
      })
    return () => {
      active = false
    }
  }, [source])

  return (
    <figure className="rounded border border-slate-200 bg-white p-3" aria-label={title}>
      <figcaption className="mb-2 text-sm font-semibold">{title}</figcaption>
      <div ref={container} className="mermaid overflow-x-auto" data-testid="mermaid-diagram" />
      {failed && (
        <p role="alert" className="mt-2 text-sm text-red-700">
          The diagram could not be rendered ({failed}). Its source is below.
        </p>
      )}
      <details className="mt-2 text-xs">
        <summary className="cursor-pointer text-slate-500">Mermaid source</summary>
        <pre className="mt-1 overflow-x-auto whitespace-pre bg-slate-50 p-2">{source}</pre>
      </details>
    </figure>
  )
}
