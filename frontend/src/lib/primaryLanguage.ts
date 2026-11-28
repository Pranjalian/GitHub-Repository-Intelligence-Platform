/**
 * The "primary language" of a snapshot. The language detector measures bytes per file extension, so stylesheets,
 * markup and data files compete with source code (spring-petclinic: CSS 54.4%, Java 27.5%). Those are skipped here;
 * reported shares are not changed. If only such languages exist, the largest of them is used.
 *
 * Mirrors backend/src/main/java/com/repointelligence/repository/application/PrimaryLanguage.java (the dashboard
 * rows use the backend's choice, this report card uses this one). Keep the two lists in step.
 */
export const NOT_PROGRAMMING = new Set([
  'CSS',
  'SCSS',
  'Less',
  'HTML',
  'JSON',
  'YAML',
  'XML',
  'TOML',
  'Properties',
  'INI',
  'Markdown',
  'reStructuredText',
  'AsciiDoc',
])

interface LanguageLike {
  name: string
  sharePercent?: number | string | null
}

const shareOf = (t: LanguageLike) => Number(t.sharePercent ?? 0) || 0

/** Largest share first; on a tie the alphabetically first name, like the backend. */
function byShareThenName(a: LanguageLike, b: LanguageLike): number {
  return shareOf(b) - shareOf(a) || a.name.localeCompare(b.name)
}

export function primaryLanguage<T extends LanguageLike>(languages: T[]): T | undefined {
  const programming = languages.filter((t) => !NOT_PROGRAMMING.has(t.name)).sort(byShareThenName)
  return programming[0] ?? [...languages].sort(byShareThenName)[0]
}
