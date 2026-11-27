import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * vercel.json must keep what nginx.conf enforces (the same CSP and headers) and route /api to the backend
 * before the SPA fallback. The Railway domain placeholder must be replaced before a real deployment.
 */
const root = resolve(__dirname, '../..')
const vercel = JSON.parse(readFileSync(resolve(root, 'vercel.json'), 'utf-8')) as {
  headers: { source: string; headers: { key: string; value: string }[] }[]
  rewrites: { source: string; destination: string }[]
}
const nginx = readFileSync(resolve(root, 'nginx.conf'), 'utf-8')

function nginxHeader(name: string): string {
  const m = nginx.match(new RegExp(`add_header ${name} "([^"]*)"`))
  if (!m) throw new Error(`nginx.conf has no ${name}`)
  return m[1]
}

describe('vercel.json', () => {
  const all = vercel.headers.find((h) => h.source === '/(.*)')!.headers
  const get = (key: string) => all.find((h) => h.key === key)?.value

  it.each(['Content-Security-Policy', 'X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy'])(
    'sends the same %s as nginx.conf',
    (name) => {
      expect(get(name)).toBe(nginxHeader(name))
    },
  )

  it('routes /api to the backend before the SPA fallback, and the fallback never swallows /api or /assets', () => {
    const apiIdx = vercel.rewrites.findIndex((r) => r.source === '/api/:path*')
    const spaIdx = vercel.rewrites.findIndex((r) => r.destination === '/index.html')
    expect(apiIdx).toBeGreaterThanOrEqual(0)
    expect(apiIdx).toBeLessThan(spaIdx)
    expect(vercel.rewrites[apiIdx].destination).toMatch(/^https:\/\/[^/]+\/api\/:path\*$/)
    const spa = new RegExp(`^${vercel.rewrites[spaIdx].source}$`)
    expect(spa.test('/repositories/1')).toBe(true)
    expect(spa.test('/login')).toBe(true)
    expect(spa.test('/api/v1/repositories')).toBe(false)
    expect(spa.test('/assets/index.js')).toBe(false)
  })

  it('contains no secret-looking value', () => {
    expect(JSON.stringify(vercel)).not.toMatch(/secret|token|password|api[_-]?key/i)
  })
})
