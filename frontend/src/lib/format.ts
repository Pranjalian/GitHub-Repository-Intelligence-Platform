export const shortSha = (sha: string | null | undefined): string => (sha ? sha.slice(0, 7) : '')

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return 'Not yet'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return 'Unknown'
  }
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toFixed(1)} ${units[unit]}`
}

/** "2 hours ago" style text; the absolute time belongs in a title attribute. */
export function relativeTime(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return 'not yet'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return 'unknown'
  const seconds = Math.round((then - now) / 1000)
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31536000],
    ['month', 2592000],
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
  ]
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) return formatter.format(Math.round(seconds / size), unit)
  }
  return 'just now'
}

/** 18400 -> "18.4s", 250 -> "250 ms", 125000 -> "2m 5s". Null stays null: no duration is never 0. */
export function formatDuration(ms: number | null | undefined): string | null {
  if (ms == null) return null
  if (ms < 1000) return `${ms} ms`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const whole = Math.round(seconds)
  return `${Math.floor(whole / 60)}m ${whole % 60}s`
}

/** Shortens a long path in the middle, keeping the start and the file name: "src/main/.../OwnerController.java". */
export function middleTruncate(text: string, max = 48): string {
  if (text.length <= max) return text
  const keepEnd = Math.ceil((max - 3) * 0.6)
  const keepStart = max - 3 - keepEnd
  return `${text.slice(0, keepStart)}...${text.slice(text.length - keepEnd)}`
}
