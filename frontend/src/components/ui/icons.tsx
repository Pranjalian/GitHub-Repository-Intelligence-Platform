import type { SVGProps } from 'react'

/**
 * The local icon set: inline SVG, 24x24 grid, stroke currentColor. Material Symbols (a Google font) is not
 * used because the CSP only allows the app's own origin. Icons are decorative (aria-hidden) unless a
 * `title` is given.
 */
const P: Record<string, string> = {
  logo: 'M4 5h16v14H4z M8 10l3 2-3 2 M13 15h4',
  dashboard: 'M4 4h7v7H4z M13 4h7v4h-7z M13 10h7v10h-7z M4 13h7v7H4z',
  add: 'M12 5v14 M5 12h14',
  tune: 'M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 4v6 M9 14v6',
  lock: 'M6 11h12v9H6z M8 11V8a4 4 0 018 0v3',
  commit: 'M12 9a3 3 0 100 6 3 3 0 000-6z M3 12h6 M15 12h6',
  copy: 'M9 9h10v11H9z M5 15V4h10',
  'check-circle': 'M12 3a9 9 0 100 18 9 9 0 000-18z M8 12l3 3 5-6',
  error: 'M12 3a9 9 0 100 18 9 9 0 000-18z M12 7v6 M12 16.5v.5',
  warning: 'M12 4l9 16H3z M12 10v4 M12 17v.5',
  info: 'M12 3a9 9 0 100 18 9 9 0 000-18z M12 11v6 M12 7.5v.5',
  hourglass: 'M7 3h10 M7 21h10 M7 3c0 5 5 5 5 9s-5 4-5 9 M17 3c0 5-5 5-5 9s5 4 5 9',
  spinner: 'M12 3a9 9 0 019 9',
  skip: 'M5 5l9 7-9 7z M18 5v14',
  search: 'M11 4a7 7 0 100 14 7 7 0 000-14z M20 20l-4-4',
  'search-index': 'M4 6h8 M4 10h6 M4 14h4 M14 12a4 4 0 100 8 4 4 0 000-8z M20 21l-3-3',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z M18 16l.8 2.2L21 19l-2.2.8L18 22l-.8-2.2L15 19l2.2-.8z',
  chat: 'M4 5h16v11H9l-5 4z',
  impact: 'M6 6a2 2 0 100 .01 M18 6a2 2 0 100 .01 M12 18a2 2 0 100 .01 M7.5 7.5l3.5 8 M16.5 7.5l-3.5 8',
  investigation: 'M4 6h10 M4 11h6 M4 16h5 M16 12a4 4 0 100 8 4 4 0 000-8z M22 22l-3-3',
  overview: 'M4 13l4-4 4 3 5-6 3 3 M4 20h16',
  dependencies: 'M12 3l8 4.5v9L12 21l-8-4.5v-9z M12 12l8-4.5 M12 12v9 M12 12L4 7.5',
  'code-quality': 'M9 8l-5 4 5 4 M15 8l5 4-5 4',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z',
  tests: 'M9 3h6 M10 3v6l-5 9a2 2 0 002 3h10a2 2 0 002-3l-5-9V3',
  architecture: 'M10 4h4v4h-4z M4 16h4v4H4z M16 16h4v4h-4z M12 8v4 M6 16v-4h12v4',
  'chevron-down': 'M6 9l6 6 6-6',
  'chevron-up': 'M6 15l6-6 6 6',
  'chevron-right': 'M9 6l6 6-6 6',
  'chevron-left': 'M15 6l-6 6 6 6',
  'arrow-right': 'M5 12h14 M13 6l6 6-6 6',
  'arrow-left': 'M19 12H5 M11 6l-6 6 6 6',
  'arrow-up': 'M12 19V5 M6 11l6-6 6 6',
  'arrow-down': 'M12 5v14 M6 13l6 6 6-6',
  'external-link': 'M14 4h6v6 M20 4l-9 9 M18 14v6H4V6h6',
  close: 'M6 6l12 12 M18 6L6 18',
  filter: 'M4 5h16l-6 8v6l-4-2v-4z',
  sort: 'M8 4v16 M4 8l4-4 4 4 M16 20V4 M12 16l4 4 4-4',
  group: 'M4 5h16 M4 12h16 M4 19h10',
  download: 'M12 4v12 M6 11l6 6 6-6 M5 20h14',
  refresh: 'M20 12a8 8 0 10-3 6.2 M20 5v5h-5',
  play: 'M7 5l12 7-12 7z',
  stop: 'M6 6h12v12H6z',
  moon: 'M20 14A8 8 0 0110 4a8 8 0 1010 10z',
  sun: 'M12 8a4 4 0 100 8 4 4 0 000-8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M5 19l1.5-1.5 M17.5 6.5L19 5',
  monitor: 'M3 5h18v11H3z M8 20h8 M12 16v4',
  github: 'M9 19c-4 1.3-4-2-6-2.5 M15 21v-3.5c0-1 .1-1.5-.5-2.2 3-.3 6-1.5 6-6.5a5 5 0 00-1.4-3.5 4.6 4.6 0 00-.1-3.5s-1.1-.3-3.5 1.3a12 12 0 00-6 0C7.1 3.1 6 3.4 6 3.4a4.6 4.6 0 00-.1 3.5A5 5 0 004.5 10.4c0 5 3 6.2 6 6.5-.6.7-.6 1.4-.5 2.1V21',
  key: 'M8 14a4 4 0 110-8 4 4 0 010 8z M11 11l9 9 M16 16l2-2 M19 19l2-2',
  database: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  globe: 'M12 3a9 9 0 100 18 9 9 0 000-18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18',
  link: 'M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1 M14 10a4 4 0 00-5.7 0l-3 3A4 4 0 0011 18.7l1-1',
  file: 'M6 3h8l4 4v14H6z M14 3v4h4',
  folder: 'M3 6h6l2 2h10v11H3z',
  package: 'M12 3l8 4v10l-8 4-8-4V7z M4 7l8 4 8-4 M12 11v10',
  class: 'M5 5h14v14H5z M9 9h6 M9 13h6',
  method: 'M7 7h10v10H7z M3 12h4 M17 12h4',
  interface: 'M12 4a8 8 0 100 16 8 8 0 000-16z M9 12h6',
  config: 'M12 8a4 4 0 100 8 4 4 0 000-8z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
  route: 'M6 18a2 2 0 100-4 2 2 0 000 4z M18 10a2 2 0 100-4 2 2 0 000 4z M8 16h5a3 3 0 003-3v-1 M16 8h-5a3 3 0 00-3 3v1',
  entity: 'M4 6h16v12H4z M4 10h16 M10 10v8',
  cycle: 'M17 5l3 3-3 3 M20 8H9a5 5 0 00-5 5 M7 19l-3-3 3-3 M4 16h11a5 5 0 005-5',
  expand: 'M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5',
  collapse: 'M9 4v5H4 M15 4v5h5 M9 20v-5H4 M15 20v-5h5',
  'zoom-in': 'M11 4a7 7 0 100 14 7 7 0 000-14z M20 20l-4-4 M11 8v6 M8 11h6',
  'zoom-out': 'M11 4a7 7 0 100 14 7 7 0 000-14z M20 20l-4-4 M8 11h6',
  fit: 'M4 4h6 M4 4v6 M20 4h-6 M20 4v6 M4 20h6 M4 20v-6 M20 20h-6 M20 20v-6',
  fullscreen: 'M4 9V4h5 M20 9V4h-5 M4 15v5h5 M20 15v5h-5',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 100 6 3 3 0 000-6z',
  tag: 'M3 12V4h8l10 10-8 8z M7.5 8.5h.01',
  clock: 'M12 3a9 9 0 100 18 9 9 0 000-18z M12 7v5l3 2',
  code: 'M9 8l-5 4 5 4 M15 8l5 4-5 4',
  user: 'M12 12a4 4 0 100-8 4 4 0 000 8z M4 21a8 8 0 0116 0',
  logout: 'M10 4H5v16h5 M16 8l4 4-4 4 M20 12H9',
  'eye-off': 'M3 3l18 18 M10.6 6.2A9.5 9.5 0 0112 6c6 0 10 6 10 6a17 17 0 01-3.2 3.8 M6.6 7.6A17 17 0 002 12s4 7 10 7a9.6 9.6 0 004.2-1 M9.9 9.9a3 3 0 004.2 4.2',
}

export type IconName = keyof typeof P | string

export default function Icon({
  name,
  size = 16,
  title,
  className,
  ...rest
}: { name: IconName; size?: number; title?: string } & Omit<SVGProps<SVGSVGElement>, 'name'>) {
  const d = P[name] ?? P.info
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden={title ? undefined : true}
      role={title ? 'img' : undefined}
      aria-label={title}
      className={`shrink-0 ${name === 'spinner' ? 'spin' : ''} ${className ?? ''}`}
      {...rest}
    >
      {d.split(' M').map((segment, i) => (
        <path key={i} d={i === 0 ? segment : `M${segment}`} />
      ))}
    </svg>
  )
}

export const ICON_NAMES = Object.keys(P)
