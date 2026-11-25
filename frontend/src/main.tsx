import '@fontsource-variable/geist'
import '@fontsource-variable/jetbrains-mono'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { applyTheme } from './lib/theme'
import './styles/globals.css'

// Before first paint: the CSP forbids an inline script in index.html.
applyTheme()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
