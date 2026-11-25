import { expect, test } from '@playwright/test'

/** Step 7.14 item 8: the shell, dashboard, report, findings drawer and a diagram card at 1440, 1024 and 390 px. No page-level horizontal scroll. */
const SIZES = [
  { width: 1440, height: 900 },
  { width: 1024, height: 800 },
  { width: 390, height: 844 },
]

for (const size of SIZES) {
  test(`no horizontal page scroll at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await page.goto('/')
    const link = page.getByRole('list', { name: 'Repositories' }).getByRole('link').first()
    await expect(link).toBeVisible()
    const href = (await link.getAttribute('href'))!
    for (const path of ['/', href, `${href}?tab=code-quality`, `${href}/arch`, `${href}/impact`, '/settings/ai']) {
      await page.goto(path)
      await page.waitForLoadState('networkidle')
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} overflows by ${overflow}px`).toBeLessThanOrEqual(1)
    }
    if (size.width < 1280) {
      // below 1280 px the sidebar is an overlay opened from the header
      await page.goto('/')
      await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
      await page.getByRole('button', { name: 'Open navigation' }).click()
      await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
    }
  })
}
