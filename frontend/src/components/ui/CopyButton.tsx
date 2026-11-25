import { useState } from 'react'
import Icon from './icons'

/** Copies `value`. `label` is the visible text; `compact` shows only the icon (still labelled for screen readers). */
export default function CopyButton({ value, label = 'Copy', compact, labelOnly }: { value: string; label?: string; compact?: boolean; labelOnly?: boolean }) {
  const [copied, setCopied] = useState(false)

  async function copy(event: React.MouseEvent) {
    event.stopPropagation()
    event.preventDefault()
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access can be denied; the value stays visible for manual copy.
    }
  }

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={labelOnly ? label : `${label} ${value}`}
      className="inline-flex h-6 items-center gap-1 rounded border border-line-strong px-1.5 text-body-sm text-fg-2 hover:bg-raised hover:text-fg"
    >
      <Icon name={copied ? 'check-circle' : 'copy'} size={12} />
      {!compact && (copied ? 'Copied' : label)}
    </button>
  )
}
