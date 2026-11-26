import { describe, expect, it } from 'vitest'
import { safeNext } from './safeNext'

describe('safeNext', () => {
  it.each([
    '/',
    '/repositories/1?tab=quality',
    '/repositories/abc/arch?snapshot=s1',
    '/settings/ai',
    '/repositories/1#findings',
  ])('keeps the in-app path %s', (path) => {
    expect(safeNext(path)).toBe(path)
  })

  it.each([
    ['protocol-relative', '//evil.com'],
    ['protocol-relative with path', '//evil.com/x'],
    ['absolute https', 'https://evil.com'],
    ['absolute http', 'http://evil.com/repositories'],
    ['backslash host', '/' + String.fromCharCode(92) + 'evil.com'],
    ['mixed slashes', '/' + String.fromCharCode(92) + '/evil.com'],
    ['javascript scheme', 'javascript:alert(1)'],
    ['data scheme', 'data:text/html,<script>1</script>'],
    ['encoded protocol-relative', '/%2F%2Fevil.com'],
    ['encoded slash host', '/%2Fevil.com'],
    ['encoded backslash', '/%5Cevil.com'],
    ['double slash after decode', '/%2F/evil.com'],
    ['bad percent encoding', '/%E0%A4%A'],
    ['whitespace', '/ //evil.com'],
    ['tab', '/\t/evil.com'],
    ['newline', '/\n//evil.com'],
    ['no leading slash', 'evil.com'],
    ['relative', '../evil'],
    ['empty', ''],
    ['the login page itself', '/login?next=%2F'],
    ['too long', '/' + 'a'.repeat(3000)],
  ])('falls back to / for %s', (_label, value) => {
    expect(safeNext(value)).toBe('/')
  })

  it('falls back for null and undefined', () => {
    expect(safeNext(null)).toBe('/')
    expect(safeNext(undefined)).toBe('/')
  })
})
