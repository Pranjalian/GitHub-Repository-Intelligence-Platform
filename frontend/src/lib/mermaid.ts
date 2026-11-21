import type { Mermaid, MermaidConfig } from 'mermaid'

/**
 * Mermaid settings for architecture diagrams. Labels are repository-derived and untrusted: `strict`
 * encodes HTML in labels and disables click handlers, and labels are SVG text rather than HTML.
 */
export const MERMAID_CONFIG: MermaidConfig = {
  startOnLoad: false,
  securityLevel: 'strict',
  theme: 'neutral',
  flowchart: { htmlLabels: false },
}

let loading: Promise<Mermaid> | null = null

/** Mermaid, loaded on first use (it is large) and initialised once with {@link MERMAID_CONFIG}. */
export function getMermaid(): Promise<Mermaid> {
  if (!loading) {
    loading = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize(MERMAID_CONFIG)
      return mermaid
    })
  }
  return loading
}
