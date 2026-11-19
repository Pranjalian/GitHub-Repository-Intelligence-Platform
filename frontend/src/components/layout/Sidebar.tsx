import { NavLink } from 'react-router'

const items = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/repositories/new', label: 'Add Repository', end: false },
]

export default function Sidebar() {
  return (
    <nav aria-label="Main" className="w-56 shrink-0 border-r border-slate-200 bg-white p-4">
      <ul className="space-y-1">
        {items.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `block rounded px-3 py-2 text-sm font-medium ${
                  isActive ? 'bg-slate-900 text-white' : 'text-slate-700 hover:bg-slate-100'
                }`
              }
            >
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
