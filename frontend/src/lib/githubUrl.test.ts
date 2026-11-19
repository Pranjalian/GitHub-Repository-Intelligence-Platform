import { parseGitHubUrl } from './githubUrl'

// These cases are copied from the backend GitHubUrlParserTests so both sides accept and reject the same inputs.
const SUPPORTED = [
  'https://github.com/spring-projects/spring-petclinic',
  'https://github.com/spring-projects/spring-petclinic/',
  'https://github.com/spring-projects/spring-petclinic.git',
  'https://www.github.com/spring-projects/spring-petclinic',
  'http://github.com/spring-projects/spring-petclinic',
  'github.com/spring-projects/spring-petclinic',
  'www.github.com/spring-projects/spring-petclinic',
  '  https://GitHub.com/spring-projects/spring-petclinic  ',
  'https://github.com/spring-projects/spring-petclinic#readme',
]

const REJECTED = [
  '',
  '   ',
  'https://gitlab.com/o/r',
  'https://github.com.evil.com/o/r',
  'https://evil.com/github.com/o/r',
  'https://notgithub.com/o/r',
  'https://user@github.com/o/r',
  'https://user:pw@github.com/o/r',
  'user@github.com/o/r',
  'https://github.com:8443/o/r',
  'github.com:80/o/r',
  'file:///etc/passwd',
  'ftp://github.com/o/r',
  'javascript:alert(1)',
  'ssh://git@github.com/o/r',
  'https://github.com/o',
  'https://github.com/',
  'https://github.com',
  'https://github.com/../r',
  'https://github.com/o/..',
  'https://github.com/o/.',
  'https://github.com/-bad/r',
  'https://github.com/o_o/r',
  'https://github.com/o/r r',
  'https://github.com/o/r?ref=x',
  'https://github.com/o/r/blob/main/README.md',
  'https://github.com/o/r/issues',
  'https://github.com/o/r/tree',
  'https://github.com/o/r/tree/../../x',
  'https://github.com/o/r/tree/a/../b',
  'https://github.com/o/r/tree/ma%2Fin%00',
  'https://github.com\\@evil.com/o/r',
  'https://github.com/o/.git',
  'https://github.com/%2e%2e/r',
  'https://github.com/o/r%00',
  `https://github.com/${'a'.repeat(40)}/r`,
  `https://github.com/o/${'r'.repeat(600)}`,
]

describe('parseGitHubUrl', () => {
  it.each(SUPPORTED)('accepts %j and normalizes to the same repository', (url) => {
    expect(parseGitHubUrl(url)).toEqual({ ok: true, owner: 'spring-projects', repo: 'spring-petclinic', branch: null })
  })

  it.each(REJECTED)('rejects %j', (url) => {
    expect(parseGitHubUrl(url).ok).toBe(false)
  })

  it('rejects null and undefined', () => {
    expect(parseGitHubUrl(null).ok).toBe(false)
    expect(parseGitHubUrl(undefined).ok).toBe(false)
  })

  it('records the branch of a /tree/ URL', () => {
    expect(parseGitHubUrl('https://github.com/o/r/tree/main')).toEqual({ ok: true, owner: 'o', repo: 'r', branch: 'main' })
    expect(parseGitHubUrl('https://github.com/o/r/tree/feature/login-page')).toEqual({
      ok: true,
      owner: 'o',
      repo: 'r',
      branch: 'feature/login-page',
    })
  })

  it('allows dots, underscores and dashes in repository names and 39-character owners', () => {
    expect(parseGitHubUrl('https://github.com/o/my.repo_v2-x')).toMatchObject({ ok: true, repo: 'my.repo_v2-x' })
    expect(parseGitHubUrl(`https://github.com/${'a'.repeat(39)}/r`).ok).toBe(true)
  })
})
