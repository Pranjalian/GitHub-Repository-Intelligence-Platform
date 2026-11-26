/**
 * The post-login destination comes from the URL (`/login?next=...`), so it is attacker-controlled: a crafted link
 * must never send a freshly signed-in user to another site. Only a path inside this app is accepted; everything else
 * becomes `/`.
 */
const FALLBACK = '/'
const MAX_LENGTH = 2000

/** Backslash, whitespace and control characters (checked by code point so the linter's control-character rule stays on). */
function hasUnsafeCharacter(value: string): boolean {
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0
    if (ch === '\\' || code <= 0x20 || code === 0x7f) return true
  }
  return false
}

export function safeNext(raw: string | null | undefined): string {
  if (!raw || raw.length > MAX_LENGTH) return FALLBACK
  // A single leading slash; not "//host" (protocol-relative), no backslash ("/\host" is read as "//host" by browsers),
  // no scheme, no control characters or whitespace.
  if (!raw.startsWith('/') || raw.startsWith('//') || hasUnsafeCharacter(raw)) return FALLBACK
  // Whatever would be decoded once more must be harmless too ("/%2F/host" is fine as a path, "%5C" is not).
  let decoded: string
  try {
    decoded = decodeURIComponent(raw)
  } catch {
    return FALLBACK
  }
  if (decoded.startsWith('//') || hasUnsafeCharacter(decoded)) return FALLBACK
  // Final check with the URL parser: the result must still resolve to this origin.
  try {
    const base = 'http://app.invalid'
    if (new URL(raw, base).origin !== base) return FALLBACK
  } catch {
    return FALLBACK
  }
  // Never bounce back to the login page itself.
  if (raw === '/login' || raw.startsWith('/login?') || raw.startsWith('/login/')) return FALLBACK
  return raw
}
