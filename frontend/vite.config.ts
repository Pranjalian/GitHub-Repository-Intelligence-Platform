/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Small font files are inlined as data: URIs by default, and the nginx CSP (default-src 'self', no font-src)
    // blocks data: fonts. Fonts must always be emitted as files served from the app origin.
    assetsInlineLimit: (file) => (/\.(woff2?|ttf|otf)$/i.test(file) ? false : undefined),
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    css: false,
    // the Playwright smoke test is a separate, optional command (pnpm e2e)
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
})
