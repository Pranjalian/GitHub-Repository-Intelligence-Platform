import { useTheme, type ThemeChoice } from '../../lib/theme'
import { IconButton } from '../ui/Button'

const NEXT: Record<ThemeChoice, ThemeChoice> = { dark: 'light', light: 'system', system: 'dark' }
const ICONS: Record<ThemeChoice, string> = { dark: 'moon', light: 'sun', system: 'monitor' }

/** Cycles dark, light, system. Used in the header and on the sign-in page (works before login). */
export default function ThemeToggle() {
  const { choice, setChoice } = useTheme()
  return <IconButton icon={ICONS[choice]} label={`Theme: ${choice}. Switch to ${NEXT[choice]}`} onClick={() => setChoice(NEXT[choice])} />
}
