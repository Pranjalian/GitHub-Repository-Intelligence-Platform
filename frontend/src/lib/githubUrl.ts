/**
 * Client-side mirror of the backend GitHubUrlParser: the same accepted and rejected inputs, so the
 * form can fail fast. The backend remains the authority; this never replaces its validation.
 */
export type ParsedGitHubUrl =
  | { ok: true; owner: string; repo: string; branch: string | null }
  | { ok: false; error: string }

const OWNER = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/
const REPO = /^[A-Za-z0-9._-]{1,100}$/
const BRANCH_SEGMENT = /^[A-Za-z0-9._@+-]{1,100}$/
const MAX_LENGTH = 500
// characters java.net.URI rejects outright
const ILLEGAL_URI_CHARS = /[\\^`{|}<>"]/
const WHITESPACE = /\s/
const hasControlChars = (value: string): boolean =>
  [...value].some((char) => {
    const code = char.charCodeAt(0)
    return code <= 0x1f || (code >= 0x7f && code <= 0x9f)
  })
const URL_SHAPE = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/

const invalid = (error: string): ParsedGitHubUrl => ({ ok: false, error })

export function parseGitHubUrl(input: string | null | undefined): ParsedGitHubUrl {
  if (input == null || input.trim() === '') {
    return invalid('Repository URL must not be empty.')
  }
  let url = input.trim()
  if (url.length > MAX_LENGTH || WHITESPACE.test(url) || hasControlChars(url)) {
    return invalid('Repository URL contains invalid characters.')
  }
  if (!url.includes('://')) {
    url = `https://${url}`
  }
  if (ILLEGAL_URI_CHARS.test(url)) {
    return invalid('Repository URL is malformed.')
  }
  const match = URL_SHAPE.exec(url)
  if (!match) {
    return invalid('Repository URL is malformed.')
  }
  const [, rawScheme, authority, rawPath, query] = match
  const scheme = rawScheme.toLowerCase()
  if (scheme !== 'https' && scheme !== 'http') {
    return invalid('Only http(s) GitHub URLs are supported.')
  }
  if (authority.includes('@')) {
    return invalid('Repository URL must not contain user information.')
  }
  if (authority.includes(':')) {
    return invalid('Repository URL must not specify a port.')
  }
  const host = authority.toLowerCase()
  if (host !== 'github.com' && host !== 'www.github.com') {
    return invalid('Only github.com repositories are supported.')
  }
  if (query !== undefined) {
    return invalid('Repository URL must not contain a query string.')
  }

  let path: string
  try {
    path = decodeURIComponent(rawPath)
  } catch {
    return invalid('Repository URL is malformed.')
  }
  const segments = path.replace(/^\/+|\/+$/g, '').split('/')
  if (segments.length < 2) {
    return invalid('Repository URL must look like https://github.com/{owner}/{repo}.')
  }
  const owner = segments[0]
  const repo = segments[1].endsWith('.git') ? segments[1].slice(0, -4) : segments[1]
  if (!OWNER.test(owner)) {
    return invalid('Invalid GitHub owner name.')
  }
  if (!REPO.test(repo) || repo === '.' || repo === '..') {
    return invalid('Invalid GitHub repository name.')
  }

  let branch: string | null = null
  if (segments.length > 2) {
    if (segments[2] !== 'tree' || segments.length < 4) {
      return invalid('Only repository root and /tree/{branch} URLs are supported.')
    }
    const branchSegments = segments.slice(3)
    for (const segment of branchSegments) {
      if (segment === '.' || segment === '..' || !BRANCH_SEGMENT.test(segment)) {
        return invalid('Invalid branch name.')
      }
    }
    branch = branchSegments.join('/')
  }
  return { ok: true, owner, repo, branch }
}
