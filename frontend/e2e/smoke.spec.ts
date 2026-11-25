import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'

/**
 * Opens the main screens in both themes against a running stack and fails on any console error, CSP
 * violation, request to an external origin, or font that did not come from the app's own origin.
 * jsdom cannot see these: MVP 2's black Mermaid diagrams were a CSP problem no unit test could catch.
 *
 * The stack must already contain an analysed Java repository (for example spring-petclinic).
 * Set E2E_SCREENSHOTS=<dir> to save a screenshot of every screen for a human to review.
 */
const SHOTS = process.env.E2E_SCREENSHOTS

async function watch(page: Page, origin: string) {
  const problems: string[] = []
  const fonts: string[] = []
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`console error: ${m.text().slice(0, 200)}`)
  })
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`))
  page.on('request', (r) => {
    const url = new URL(r.url())
    if (url.protocol.startsWith('http') && url.origin !== origin) problems.push(`external request: ${r.url()}`)
    if (/\.woff2?$/.test(url.pathname)) fonts.push(r.url())
  })
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`))
  })
  return { problems, fonts }
}

for (const theme of ['dark', 'light'] as const) {
  test(`main screens in the ${theme} theme`, async ({ page, baseURL }) => {
    const origin = new URL(baseURL!).origin
    const { problems, fonts } = await watch(page, origin)
    await page.addInitScript((t) => window.localStorage.setItem('repointel.theme', t), theme)
    if (SHOTS) mkdirSync(SHOTS, { recursive: true })
    const shot = async (name: string) => {
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${theme}-${name}.png`, fullPage: true })
    }

    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^((?!dark).)*$/)
    const row = page.getByRole('list', { name: 'Repositories' }).getByRole('link').first()
    await expect(row).toBeVisible()
    await shot('dashboard')
    const href = await row.getAttribute('href')
    expect(href).toMatch(/^\/repositories\/[0-9a-f-]{36}$/)

    await page.goto(href!)
    await expect(page.getByRole('region', { name: 'Capability matrix' })).toBeVisible()
    await shot('overview')
    for (const tab of ['dependencies', 'code-quality', 'security', 'tests']) {
      await page.goto(`${href}?tab=${tab}`)
      await expect(page.getByRole('main')).toBeVisible()
      await page.waitForLoadState('networkidle')
      await shot(`tab-${tab}`)
    }

    await page.goto(`${href}/arch`)
    await expect(page.getByRole('heading', { name: 'Application Architecture' })).toBeVisible()
    // a rendered Mermaid diagram is an <svg> inside the canvas; a CSP-blocked one renders solid black
    const svg = page.locator('[data-testid="mermaid-diagram"] svg').first()
    await expect(svg).toBeVisible({ timeout: 30_000 })
    const filled = await svg.evaluate((el) => {
      const rect = el.querySelector('rect, path')
      return rect ? getComputedStyle(rect).fill : 'none'
    })
    expect(filled).not.toBe('rgb(0, 0, 0)')
    await shot('architecture')

    await page.goto(`${href}/search`)
    await expect(page.getByRole('heading', { name: 'Code Search' })).toBeVisible()
    await shot('search')

    await page.goto(`${href}/impact`)
    await expect(page.getByRole('heading', { name: 'Change Impact Analysis' })).toBeVisible()
    await shot('impact')

    await page.goto('/settings/ai')
    await expect(page.getByText(/AI Insights:/)).toBeVisible()
    await shot('ai-settings')

    await page.goto('/does-not-exist')
    await expect(page.getByText('Page not found')).toBeVisible()

    expect(fonts.length, 'fonts were requested from the app origin').toBeGreaterThan(0)
    expect(problems).toEqual([])
  })
}
