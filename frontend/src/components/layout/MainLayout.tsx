import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useIsDesktop } from '../../lib/useMediaQuery'
import Header from './Header'
import Sidebar from './Sidebar'

const KEY = 'repointel.sidebar'

function readCollapsed(): boolean {
  try {
    return window.localStorage.getItem(KEY) === 'collapsed'
  } catch {
    return false
  }
}

/** Shell: skip link, sidebar (docked, or an overlay below 1280 px), header and the routed page. */
export default function MainLayout() {
  const desktop = useIsDesktop()
  const [collapsed, setCollapsed] = useState(readCollapsed)
  const location = useLocation()
  // The overlay menu is open for one path only: navigating anywhere closes it.
  const [openAt, setOpenAt] = useState<string | null>(null)
  const menuOpen = openAt === location.pathname
  const setMenuOpen = (open: boolean) => setOpenAt(open ? location.pathname : null)

  useEffect(() => {
    if (!menuOpen) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false)
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [menuOpen])

  function toggle() {
    setCollapsed((value) => {
      try {
        window.localStorage.setItem(KEY, value ? 'expanded' : 'collapsed')
      } catch {
        // storage blocked: the state still applies to this page view
      }
      return !value
    })
  }

  return (
    <div className="flex min-h-screen bg-canvas text-fg">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-primary focus:px-3 focus:py-2 focus:text-primary-fg">
        Skip to content
      </a>
      {desktop ? (
        <div className="sticky top-0 h-screen shrink-0">
          <Sidebar collapsed={collapsed} onToggle={toggle} />
        </div>
      ) : (
        menuOpen && (
          <>
            <div className="fixed inset-0 z-30 bg-black/50" aria-hidden="true" onClick={() => setMenuOpen(false)} />
            <div className="fixed inset-y-0 left-0 z-40 shadow-overlay" role="dialog" aria-modal="true" aria-label="Navigation">
              <Sidebar onNavigate={() => setMenuOpen(false)} />
            </div>
          </>
        )
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <Header onMenu={desktop ? undefined : () => setMenuOpen(true)} />
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
