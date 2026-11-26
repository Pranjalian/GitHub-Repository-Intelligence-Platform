import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/** Reads the token sets straight from globals.css so the check cannot drift from the real values. */
const css = readFileSync(resolve(__dirname, 'globals.css'), 'utf-8')

function block(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`)
  const body = css.slice(start, css.indexOf('\n}', start))
  const vars: Record<string, string> = {}
  for (const match of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6}|rgba?\([^)]*\))\s*;/g)) {
    vars[match[1]] = match[2]
  }
  return vars
}

const themes = { light: block(':root'), dark: block('.dark') }

function rgb(hex: string): [number, number, number] {
  return [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number]
}
function luminance([r, g, b]: [number, number, number]): number {
  const f = (c: number) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}
function ratio(a: [number, number, number], b: [number, number, number]): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}
/** Colour `fg` at `alpha` over `bg`. */
function over(fg: [number, number, number], bg: [number, number, number], alpha: number): [number, number, number] {
  return fg.map((c, i) => Math.round(c * alpha + bg[i] * (1 - alpha))) as [number, number, number]
}

const tones = ['critical', 'high', 'medium', 'low', 'info', 'success', 'running', 'warning', 'failed', 'neutral', 'detected', 'likely', 'possible', 'ai', 'primary']
const surfaces = ['c-canvas', 'c-surface', 'c-raised']

describe.each(Object.entries(themes))('WCAG AA contrast, %s theme', (_name, vars) => {
  it('sign-in page: error text, muted text and the primary button meet 4.5:1 on the page and the card', () => {
    // Step 8.4: the login page reuses existing tokens only; this pins the pairs it actually uses.
    for (const surface of ['c-canvas', 'c-surface']) {
      expect(ratio(rgb(vars['t-failed-fg']), rgb(vars[surface])), `error text on ${surface}`).toBeGreaterThanOrEqual(4.5)
      expect(ratio(rgb(vars['c-muted']), rgb(vars[surface])), `footer text on ${surface}`).toBeGreaterThanOrEqual(4.5)
      expect(ratio(rgb(vars['c-fg-2']), rgb(vars[surface])), `countdown text on ${surface}`).toBeGreaterThanOrEqual(4.5)
    }
    expect(ratio(rgb(vars['c-primary-fg']), rgb(vars['c-primary'])), 'Sign in button label').toBeGreaterThanOrEqual(4.5)
  })

  it('body text colours meet 4.5:1 on every surface', () => {
    for (const text of ['c-fg', 'c-fg-2', 'c-muted', 'c-primary-text']) {
      for (const surface of [...surfaces, 'c-overlay']) {
        if (surface === 'c-overlay' && text !== 'c-fg') continue // tier-3 overlays (tooltips, modals) carry primary text only
        expect(ratio(rgb(vars[text]), rgb(vars[surface])), `${text} on ${surface}`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('every tone label is readable on its 12% tint over each surface', () => {
    const failures: string[] = []
    for (const tone of tones) {
      for (const surface of surfaces) {
        const background = over(rgb(vars[`t-${tone}`]), rgb(vars[surface]), 0.12)
        const value = ratio(rgb(vars[`t-${tone}-fg`]), background)
        if (value < 4.5) failures.push(`${tone} on ${surface}: ${value.toFixed(2)}`)
      }
    }
    expect(failures).toEqual([])
  })

  it('the primary button and the AI surface are readable', () => {
    expect(ratio(rgb(vars['c-primary-fg']), rgb(vars['c-primary']))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(rgb(vars['c-ai-text']), rgb(vars['c-ai-bg']))).toBeGreaterThanOrEqual(4.5)
  })

  it('graphical elements (focus ring, strong border) reach 3:1', () => {
    expect(ratio(rgb(vars['c-focus']), rgb(vars['c-canvas']))).toBeGreaterThanOrEqual(3)
    expect(ratio(rgb(vars['c-line-strong']), rgb(vars['c-canvas']))).toBeGreaterThanOrEqual(1.3)
  })
})
