import { useState } from 'react'

export default function CopyButton({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
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
      aria-label={`${label} ${value}`}
      className="rounded border border-slate-300 px-2 py-0.5 text-xs text-slate-700 hover:bg-slate-100"
    >
      {copied ? 'Copied' : label}
    </button>
  )
}
