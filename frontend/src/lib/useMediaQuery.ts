import { useEffect, useState } from 'react'

/** True while the media query matches. In environments without matchMedia (tests) it returns `fallback`. */
export function useMediaQuery(query: string, fallback = true): boolean {
  const [matches, setMatches] = useState(() => (typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : fallback))
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia(query)
    const listener = () => setMatches(mq.matches)
    listener()
    mq.addEventListener('change', listener)
    return () => mq.removeEventListener('change', listener)
  }, [query])
  return matches
}

/** Desktop layout (>= 1280px): docked sidebar and inspector. Below it both become overlays. */
export const useIsDesktop = () => useMediaQuery('(min-width: 1280px)', true)
