import type { Mermaid, MermaidConfig } from 'mermaid'

export type MermaidTheme = 'light' | 'dark'

const FONT = '"Geist Variable", Geist, ui-sans-serif, system-ui, sans-serif'

/**
 * Mermaid settings for architecture diagrams. Labels are repository-derived and untrusted: `strict`
 * encodes HTML in labels and disables click handlers, and labels are SVG text rather than HTML. The
 * theme only changes colours (taken from the design tokens), never the security level.
 */
export function mermaidConfig(theme: MermaidTheme): MermaidConfig {
  const dark = theme === 'dark'
  return {
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    flowchart: { htmlLabels: false },
    themeVariables: {
      fontFamily: FONT,
      background: dark ? '#0f172a' : '#f8fafc',
      primaryColor: dark ? '#1e293b' : '#e2e8f0',
      primaryTextColor: dark ? '#f8fafc' : '#0f172a',
      primaryBorderColor: dark ? '#475569' : '#94a3b8',
      secondaryColor: dark ? '#0f172a' : '#f1f5f9',
      tertiaryColor: dark ? '#0f172a' : '#f8fafc',
      lineColor: '#475569',
      textColor: dark ? '#f8fafc' : '#0f172a',
      mainBkg: dark ? '#1e293b' : '#e2e8f0',
      nodeBorder: dark ? '#475569' : '#94a3b8',
      clusterBkg: dark ? '#0f172a' : '#f1f5f9',
      clusterBorder: dark ? '#334155' : '#cbd5e1',
      edgeLabelBackground: dark ? '#0f172a' : '#f8fafc',
    },
  }
}

/** The dark configuration, kept for tests that assert the security settings. */
export const MERMAID_CONFIG: MermaidConfig = mermaidConfig('dark')

let loading: Promise<Mermaid> | null = null

/** Mermaid, loaded on first use (it is large) and initialised for the given theme (cheap to repeat on a theme switch). */
export async function getMermaid(theme: MermaidTheme = 'dark'): Promise<Mermaid> {
  if (!loading) {
    loading = import('mermaid').then(({ default: mermaid }) => mermaid)
  }
  const mermaid = await loading
  mermaid.initialize(mermaidConfig(theme))
  return mermaid
}
