import type { ButtonHTMLAttributes, ReactNode } from 'react'
import Icon from './icons'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'ai'

const variants: Record<Variant, string> = {
  primary: 'bg-primary text-primary-fg hover:brightness-110 border border-transparent',
  secondary: 'border border-line-strong text-fg hover:bg-raised',
  ghost: 'border border-transparent text-fg-2 hover:bg-raised hover:text-fg',
  danger: 'border border-[color:var(--t-failed)] text-[color:var(--t-failed-fg)] hover:bg-[color-mix(in_srgb,var(--t-failed)_12%,transparent)]',
  ai: 'tone tone-ai hover:brightness-110',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  busy?: boolean
  icon?: string
  children?: ReactNode
}

/** `busy` disables the button and shows a spinner; the label should already read "Analyzing..." or similar. */
export default function Button({ variant = 'secondary', busy, icon, children, className, disabled, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex h-8 items-center justify-center gap-1.5 rounded px-3 text-body-md font-medium disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${className ?? ''}`}
      {...rest}
    >
      {busy ? <Icon name="spinner" size={14} /> : icon ? <Icon name={icon} size={14} /> : null}
      {children}
    </button>
  )
}

export function IconButton({
  icon,
  label,
  className,
  type = 'button',
  ...rest
}: { icon: string; label: string } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      className={`inline-flex size-8 items-center justify-center rounded text-fg-2 hover:bg-raised hover:text-fg disabled:opacity-50 ${className ?? ''}`}
      {...rest}
    >
      <Icon name={icon} size={16} />
    </button>
  )
}
