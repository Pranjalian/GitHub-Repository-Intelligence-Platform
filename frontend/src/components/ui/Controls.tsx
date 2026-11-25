import { useId, useRef, useState, type InputHTMLAttributes, type KeyboardEvent, type ReactNode, type SelectHTMLAttributes } from 'react'
import Icon from './icons'

/** Roving-tabindex radio group: arrow keys move and select. */
export function SegmentedControl<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: T
  options: { value: T; label: ReactNode }[]
  onChange: (value: T) => void
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  function onKeyDown(event: KeyboardEvent, index: number) {
    const delta = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (!delta) return
    event.preventDefault()
    const next = (index + delta + options.length) % options.length
    onChange(options[next].value)
    refs.current[next]?.focus()
  }
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded border border-line-strong bg-canvas p-0.5">
      {options.map((option, i) => {
        const selected = option.value === value
        return (
          <button
            key={option.value}
            ref={(el) => {
              refs.current[i] = el
            }}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(option.value)}
            onKeyDown={(e) => onKeyDown(e, i)}
            className={`h-7 rounded-sm px-3 text-body-md ${selected ? 'bg-raised font-medium text-fg' : 'text-fg-2 hover:text-fg'}`}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

export interface TabItem {
  id: string
  label: ReactNode
  count?: ReactNode
  disabled?: boolean
}

/** Tabs with automatic activation and arrow-key navigation; the panel is rendered by the caller with `aria-labelledby`. */
export function Tabs({ label, tabs, active, onChange, idPrefix = 'tab' }: { label: string; tabs: TabItem[]; active: string; onChange: (id: string) => void; idPrefix?: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const enabled = tabs.filter((t) => !t.disabled)
  function onKeyDown(event: KeyboardEvent, id: string) {
    const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    if (!delta) return
    event.preventDefault()
    const index = enabled.findIndex((t) => t.id === id)
    const next = enabled[(index + delta + enabled.length) % enabled.length]
    onChange(next.id)
    refs.current[tabs.findIndex((t) => t.id === next.id)]?.focus()
  }
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line">
      {tabs.map((tab, i) => {
        const selected = tab.id === active
        return (
          <button
            key={tab.id}
            ref={(el) => {
              refs.current[i] = el
            }}
            id={`${idPrefix}-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={`${idPrefix}-panel-${tab.id}`}
            tabIndex={selected ? 0 : -1}
            disabled={tab.disabled}
            onClick={() => onChange(tab.id)}
            onKeyDown={(e) => onKeyDown(e, tab.id)}
            className={`-mb-px inline-flex h-9 items-center gap-2 whitespace-nowrap border-b-2 px-3 text-body-md disabled:opacity-50 ${selected ? 'border-primary font-medium text-fg' : 'border-transparent text-fg-2 hover:text-fg'}`}
          >
            {tab.label}
            {tab.count !== undefined && <span className="rounded bg-raised px-1.5 font-mono text-label-sm text-fg-2">{tab.count}</span>}
          </button>
        )
      })}
    </div>
  )
}

export function FilterChip({ selected, onClick, children, count }: { selected: boolean; onClick: () => void; children: ReactNode; count?: number }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={`inline-flex h-7 items-center gap-1.5 rounded border px-2 text-body-md ${selected ? 'border-primary bg-[color-mix(in_srgb,var(--c-primary)_14%,transparent)] text-fg' : 'border-line-strong text-fg-2 hover:bg-raised'}`}
    >
      {children}
      {count !== undefined && <span className="font-mono text-label-sm text-muted">{count}</span>}
    </button>
  )
}

const fieldClass = 'h-8 rounded border border-line-strong bg-canvas px-2 text-body-md text-fg placeholder:text-muted'

export function Select({ label, children, className, ...rest }: { label: string } & SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <label className="inline-flex items-center gap-2 text-body-md text-fg-2">
      <span className="sr-only sm:not-sr-only">{label}</span>
      <select aria-label={label} className={`${fieldClass} ${className ?? ''}`} {...rest}>
        {children}
      </select>
    </label>
  )
}

export function Checkbox({ label, hint, ...rest }: { label: string; hint?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const id = useId()
  const hintId = `${id}-hint`
  return (
    <div className="flex items-start gap-2">
      <input id={id} type="checkbox" aria-describedby={hint ? hintId : undefined} className="mt-0.5 size-4 accent-[var(--c-primary)]" {...rest} />
      <div className="text-body-md">
        <label htmlFor={id}>{label}</label>
        {hint && (
          <span id={hintId} className="block text-body-sm text-muted">
            {hint}
          </span>
        )}
      </div>
    </div>
  )
}

/** Text input with an optional leading icon and a trailing keyboard hint (shown only where the shortcut is real). */
export function Input({ icon, hint, className, inputRef, 'aria-label': ariaLabel, ...rest }: { icon?: string; hint?: string; inputRef?: React.Ref<HTMLInputElement> } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className={`relative flex items-center ${className ?? ''}`}>
      {icon && <Icon name={icon} size={14} className="pointer-events-none absolute left-2 text-muted" />}
      <input ref={inputRef} aria-label={ariaLabel} className={`${fieldClass} w-full ${icon ? 'pl-7' : ''} ${hint ? 'pr-8' : ''}`} {...rest} />
      {hint && <kbd className="pointer-events-none absolute right-2 rounded border border-line-strong px-1 font-mono text-label-sm text-muted">{hint}</kbd>}
    </div>
  )
}

/** Text appears on hover and keyboard focus of the trigger; the trigger is described by it. */
export function Tooltip({ text, children }: { text: string; children: ReactNode }) {
  const id = useId()
  return (
    <span className="group relative inline-flex" aria-describedby={id}>
      {children}
      <span
        id={id}
        role="tooltip"
        className="pointer-events-none absolute bottom-full left-1/2 z-30 mb-1 hidden w-max max-w-64 -translate-x-1/2 rounded border border-line-strong bg-overlay px-2 py-1 text-body-sm text-fg shadow-overlay group-focus-within:block group-hover:block"
      >
        {text}
      </span>
    </span>
  )
}

export function Accordion({
  title,
  right,
  defaultOpen = false,
  children,
}: {
  title: ReactNode
  right?: ReactNode
  defaultOpen?: boolean
  children: ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  const id = useId()
  return (
    <div className="rounded border border-line bg-canvas">
      <h3>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={id}
          onClick={() => setOpen((v) => !v)}
          className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-body-md"
        >
          <span className="flex min-w-0 items-center gap-2">
            <Icon name={open ? 'chevron-down' : 'chevron-right'} size={14} className="text-muted" />
            {title}
          </span>
          {right}
        </button>
      </h3>
      {open && (
        <div id={id} className="border-t border-line px-3 py-2">
          {children}
        </div>
      )}
    </div>
  )
}
