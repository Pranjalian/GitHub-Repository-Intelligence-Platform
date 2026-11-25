import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { getMermaid } from '../../lib/mermaid'
import { useTheme } from '../../lib/theme'
import { IconButton } from '../ui/Button'
import CopyButton from '../ui/CopyButton'
import Icon from '../ui/icons'

const MIN = 0.25
const MAX = 4

/**
 * Renders backend-generated Mermaid source. The source is placed as text content and Mermaid renders it
 * in strict mode (see lib/mermaid); this component never sets HTML itself. The card has a keyboard-operable
 * toolbar (zoom, fit, full screen, copy source), drag and wheel pan/zoom, and the source stays available
 * below the diagram, which also serves when rendering fails. The diagram re-renders when the theme changes.
 */
export default function MermaidDiagram({ title, source, note, icon = 'architecture' }: { title: string; source: string; note?: string; icon?: string }) {
  const container = useRef<HTMLDivElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [view, setView] = useState({ scale: 1, x: 0, y: 0 })
  const [full, setFull] = useState(false)
  const { resolved } = useTheme()

  useEffect(() => {
    const element = container.current
    if (!element) {
      return
    }
    let active = true
    element.removeAttribute('data-processed')
    element.textContent = source
    getMermaid(resolved)
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
  }, [source, resolved])

  const zoom = useCallback((factor: number) => setView((v) => ({ ...v, scale: Math.min(MAX, Math.max(MIN, v.scale * factor)) })), [])

  useEffect(() => {
    const element = stage.current
    if (!element) return
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return // plain scrolling keeps scrolling the page
      event.preventDefault()
      zoom(event.deltaY < 0 ? 1.1 : 0.9)
    }
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  }, [zoom])

  useEffect(() => {
    if (!full) return
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && setFull(false)
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [full])

  function down(event: ReactPointerEvent) {
    drag.current = { x: event.clientX, y: event.clientY, ox: view.x, oy: view.y }
    ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
  }
  function move(event: ReactPointerEvent) {
    const d = drag.current
    if (d) setView((v) => ({ ...v, x: d.ox + event.clientX - d.x, y: d.oy + event.clientY - d.y }))
  }

  return (
    <figure
      aria-label={title}
      className={`flex flex-col rounded-lg border border-line bg-surface ${full ? 'fixed inset-0 z-50 m-0 rounded-none' : ''}`}
    >
      <figcaption className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-3 py-2">
        <span className="flex items-center gap-2 text-body-md font-semibold">
          <Icon name={icon} size={16} className="text-fg-2" />
          {title}
          {note && <span className="font-normal text-body-sm text-muted">{note}</span>}
        </span>
        <span className="flex items-center gap-1" role="toolbar" aria-label={`${title} diagram controls`}>
          <IconButton icon="zoom-out" label="Zoom out" onClick={() => zoom(1 / 1.25)} />
          <IconButton icon="zoom-in" label="Zoom in" onClick={() => zoom(1.25)} />
          <IconButton icon="fit" label="Fit" onClick={() => setView({ scale: 1, x: 0, y: 0 })} />
          <IconButton icon={full ? 'collapse' : 'fullscreen'} label={full ? 'Exit full screen' : 'Full screen'} onClick={() => setFull((v) => !v)} />
          <CopyButton value={source} label="Copy source" labelOnly />
        </span>
      </figcaption>
      <div
        ref={stage}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={() => (drag.current = null)}
        className={`dot-grid relative cursor-grab overflow-hidden active:cursor-grabbing ${full ? 'flex-1' : 'h-80'}`}
        aria-label={`${title} diagram canvas. Drag to pan; Control and wheel to zoom.`}
      >
        <div
          ref={container}
          className="mermaid inline-block origin-top-left p-4"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }}
          data-testid="mermaid-diagram"
        />
      </div>
      {failed && (
        <p role="alert" className="px-3 py-2 text-body-md text-[color:var(--t-failed-fg)]">
          The diagram could not be rendered ({failed}). Its source is below.
        </p>
      )}
      <details className="border-t border-line px-3 py-2 text-body-sm">
        <summary className="cursor-pointer text-muted">Mermaid source</summary>
        <pre className="mt-1 overflow-x-auto whitespace-pre rounded bg-code p-2 font-mono text-code-md">{source}</pre>
      </details>
    </figure>
  )
}
