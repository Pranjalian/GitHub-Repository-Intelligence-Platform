import { useEffect, useRef, type ReactNode } from 'react'
import Icon from './icons'
import { IconButton } from './Button'

export interface Step {
  id: string
  label: string
  state: 'done' | 'active' | 'pending'
  detail?: ReactNode
}

/** Ordered stepper: the active step has aria-current="step" and pulses (not under reduced motion). */
export function Stepper({ steps, label }: { steps: Step[]; label: string }) {
  return (
    <ol aria-label={label} className="flex flex-wrap items-center gap-x-2 gap-y-2">
      {steps.map((step, i) => (
        <li key={step.id} aria-current={step.state === 'active' ? 'step' : undefined} className="flex items-center gap-2">
          <span
            className={`flex size-6 items-center justify-center rounded-full border text-label-md ${
              step.state === 'done'
                ? 'tone tone-success'
                : step.state === 'active'
                  ? 'tone tone-running pulse'
                  : 'border-line-strong text-muted'
            }`}
          >
            {step.state === 'done' ? <Icon name="check-circle" size={12} /> : i + 1}
          </span>
          <span className={`text-body-md ${step.state === 'pending' ? 'text-muted' : 'text-fg'}`}>
            {step.label}
            {step.state === 'done' && <span className="sr-only"> (done)</span>}
            {step.state === 'active' && <span className="sr-only"> (in progress)</span>}
          </span>
          {step.detail && <span className="text-body-sm text-muted">{step.detail}</span>}
          {i < steps.length - 1 && <span aria-hidden="true" className="mx-1 h-px w-6 bg-line-strong" />}
        </li>
      ))}
    </ol>
  )
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Right-hand inspector. `overlay` (below 1280 px) renders it as a modal sheet with a focus trap and a
 * scrim; docked it is a plain aside. Esc closes; focus returns to `returnFocusTo` on close.
 */
export function Drawer({
  open,
  onClose,
  title,
  children,
  overlay = false,
  returnFocusTo,
  ariaLabel = 'Inspector',
}: {
  open: boolean
  onClose: () => void
  title: ReactNode
  children: ReactNode
  overlay?: boolean
  returnFocusTo?: HTMLElement | null
  ariaLabel?: string
}) {
  const ref = useRef<HTMLElement>(null)
  // Latest callbacks without re-running the focus effect on every parent render.
  const closeRef = useRef(onClose)
  const returnRef = useRef(returnFocusTo)
  useEffect(() => {
    closeRef.current = onClose
    returnRef.current = returnFocusTo
  })

  useEffect(() => {
    if (!open) return
    const node = ref.current
    node?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation()
        closeRef.current()
        return
      }
      if (event.key === 'Tab' && overlay && node) {
        const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE))
        if (items.length === 0) return
        const first = items[0]
        const last = items[items.length - 1]
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      returnRef.current?.focus()
    }
  }, [open, overlay])

  if (!open) return null
  const aside = (
    <aside
      ref={ref}
      role={overlay ? 'dialog' : 'complementary'}
      aria-modal={overlay || undefined}
      aria-label={ariaLabel}
      className={`flex flex-col border-l border-line bg-surface ${overlay ? 'fixed inset-y-0 right-0 z-40 w-[min(480px,100vw)] shadow-overlay' : 'w-[420px] shrink-0 self-start'}`}
    >
      <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-2">
        <div className="min-w-0 font-mono text-label-md text-fg-2">{title}</div>
        <IconButton icon="close" label="Close inspector" onClick={onClose} />
      </div>
      <div className="overflow-y-auto p-4">{children}</div>
    </aside>
  )
  return overlay ? (
    <>
      <div className="fixed inset-0 z-30 bg-black/50" onClick={onClose} aria-hidden="true" />
      {aside}
    </>
  ) : (
    aside
  )
}
