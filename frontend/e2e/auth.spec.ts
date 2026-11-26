import { expect, test } from '@playwright/test'

/**
 * Step 8.4: the login against a stack started with AUTH_ENABLED=true. Skipped unless the account is given:
 *   E2E_AUTH_USERNAME=operator E2E_AUTH_PASSWORD='the password' pnpm e2e e2e/auth.spec.ts
 * (the password is the one whose BCrypt hash the stack was started with). Fails on any console error or CSP violation.
 */
const USER = process.env.E2E_AUTH_USERNAME
const PASSWORD = process.env.E2E_AUTH_PASSWORD

test.skip(!USER || !PASSWORD, 'set E2E_AUTH_USERNAME and E2E_AUTH_PASSWORD to run the login check')

test('sign in, reload keeps the session, sign out returns to the login, no CSP errors', async ({ page }) => {
  const problems: string[] = []
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    // The browser itself logs "Failed to load resource ... 401" for the deliberately wrong password; that one is
    // expected. Anything else, including a 401 from any other URL (the session endpoint is always 200), is a problem.
    if (m.location().url.endsWith('/api/v1/auth/login') && m.text().includes('401')) return
    problems.push(`console error: ${m.text().slice(0, 200)} @ ${m.location().url}`)
  })
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`))
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`))
  })

  // signed out: any page goes to the login and remembers where it was going
  await page.goto('/repositories/new')
  await expect(page).toHaveURL(/\/login\?next=%2Frepositories%2Fnew$/)
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)

  // wrong password: the generic message, focus on the password field, field cleared
  await page.getByLabel('Username', { exact: true }).fill(USER!)
  await page.getByLabel('Password', { exact: true }).fill('definitely-not-the-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toHaveText(/Invalid username or password/)
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused()
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('')

  // right password: lands on the page that was wanted
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/repositories\/new$/)
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

  // the session cookie is not readable by script, and nothing secret is in browser storage
  expect(await page.evaluate(() => document.cookie)).not.toContain('REPOINTEL_SESSION')
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(PASSWORD!)

  // reload keeps the session
  await page.reload()
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

  // sign out
  await page.getByRole('button', { name: /Account menu/ }).click()
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)

  expect(problems).toEqual([])
})

test('a session that ends while the app is open returns to /login once, then back to the page the user was heading to', async ({ page, context }) => {
  await page.goto('/settings/ai')
  await page.getByLabel('Username', { exact: true }).fill(USER!)
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/settings\/ai$/)

  // the server forgets the session (idle or absolute timeout, a restart): simulate by dropping the cookie
  const cookies = await context.cookies()
  await context.clearCookies({ name: 'REPOINTEL_SESSION' })
  expect(cookies.some((c) => c.name === 'REPOINTEL_SESSION' && c.httpOnly)).toBe(true)

  // An in-app navigation (no reload) whose data request is now a 401: the dashboard's repository list.
  await page.getByRole('link', { name: 'Dashboard' }).first().click()
  await expect(page).toHaveURL(/\/login\?expired=1&next=%2F$/)
  await expect(page.getByText('Your session ended. Sign in again.')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)

  await page.getByLabel('Username', { exact: true }).fill(USER!)
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD!)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/$/) // back where the user was heading, and the data loads again
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
})
