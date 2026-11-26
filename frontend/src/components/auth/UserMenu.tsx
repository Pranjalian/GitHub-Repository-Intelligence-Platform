import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useLogout, useSession } from '../../hooks/useSession'
import Icon from '../ui/icons'

/**
 * The signed-in user's menu in the header: the username and "Sign out". Rendered only when the server requires a
 * login and the caller is signed in (never in local use). Keyboard: Enter/Space opens, Escape closes and returns
 * focus to the button, and the menu closes when focus leaves it.
 */
export default function UserMenu() {
  const session = useSession()
  const logout = useLogout()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const panelId = useId()
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        button.current?.focus()
      }
    }
    const onPointer = (event: MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointer)
    }
  }, [open])

  if (!session.authRequired || !session.authenticated) return null

  function signOut() {
    setOpen(false)
    logout.mutate(undefined, { onSettled: () => navigate('/login', { replace: true }) })
  }

  return (
    <div
      ref={root}
      className="relative"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false)
      }}
    >
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={`Account menu, signed in as ${session.username ?? 'operator'}`}
        onClick={() => setOpen((value) => !value)}
        className="inline-flex h-8 items-center gap-1.5 rounded px-2 text-body-md text-fg-2 hover:bg-raised hover:text-fg"
      >
        <Icon name="user" size={16} />
        <span className="hidden max-w-32 truncate md:inline">{session.username}</span>
      </button>
      {open && (
        <div id={panelId} className="absolute right-0 top-full z-30 mt-1 w-56 rounded border border-line-strong bg-overlay p-1 shadow-overlay">
          <p className="truncate px-2 py-1.5 text-body-sm text-fg-2">
            Signed in as <span className="font-medium text-fg">{session.username}</span>
          </p>
          <button
            type="button"
            onClick={signOut}
            disabled={logout.isPending}
            className="flex h-8 w-full items-center gap-2 rounded px-2 text-body-md text-fg hover:bg-raised disabled:opacity-50"
          >
            <Icon name="logout" size={14} />
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
