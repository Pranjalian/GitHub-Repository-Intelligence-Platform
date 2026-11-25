import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Build check for Step 7.1 / 7.14: the production bundle (`pnpm build` first) must not reach any external
 * origin. The nginx CSP only allows the app's own origin, so a CDN font, script or icon would silently fail.
 * Skipped when there is no dist/ (a plain `pnpm test` before a build).
 */
const DIST = resolve(__dirname, '../../dist')
const built = existsSync(join(DIST, 'index.html'))

const EXTERNAL_ASSET_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com', 'cdn.tailwindcss.com', 'cdn.jsdelivr.net', 'unpkg.com', 'cdnjs.cloudflare.com', 'code.jquery.com']

describe.skipIf(!built)('production bundle', () => {
  const assets = built ? readdirSync(join(DIST, 'assets')) : []

  it('index.html loads only same-origin scripts and styles', () => {
    const html = readFileSync(join(DIST, 'index.html'), 'utf-8')
    const urls = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1])
    expect(urls.length).toBeGreaterThan(0)
    expect(urls.filter((u) => /^(https?:)?\/\//.test(u))).toEqual([])
  })

  it('stylesheets reference no external URL, and the fonts are emitted as local assets', () => {
    const css = assets.filter((a) => a.endsWith('.css')).map((a) => readFileSync(join(DIST, 'assets', a), 'utf-8'))
    expect(css.length).toBeGreaterThan(0)
    for (const text of css) {
      expect([...text.matchAll(/url\(\s*["']?(https?:)?\/\/[^)]+\)/g)].map((m) => m[0])).toEqual([])
      expect(text).not.toMatch(/@import\s+(url\()?["']?https?:/)
    }
    expect(assets.some((a) => /geist.*\.woff2$/i.test(a))).toBe(true)
    expect(assets.some((a) => /jetbrains-mono.*\.woff2$/i.test(a))).toBe(true)
    expect(css.join('')).toMatch(/Geist Variable/)
  })

  it('no script or stylesheet mentions a CDN, font or icon host', () => {
    for (const name of assets.filter((a) => /\.(js|css)$/.test(a))) {
      const text = readFileSync(join(DIST, 'assets', name), 'utf-8')
      for (const host of EXTERNAL_ASSET_HOSTS) expect(text.includes(host), `${name} mentions ${host}`).toBe(false)
    }
  })

  it('does not use Material Symbols', () => {
    for (const name of assets.filter((a) => /\.(js|css)$/.test(a))) {
      expect(readFileSync(join(DIST, 'assets', name), 'utf-8').includes('Material Symbols')).toBe(false)
    }
  })
})
