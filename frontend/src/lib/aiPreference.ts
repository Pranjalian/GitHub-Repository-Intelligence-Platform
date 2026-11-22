/** The user's preferred AI provider, kept per browser. Storage can be unavailable; then there is no preference. */
const KEY = 'repointel.ai.preferredProvider'

export function readPreferredProvider(): string | null {
  try {
    return window.localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function writePreferredProvider(provider: string | null): void {
  try {
    if (provider) {
      window.localStorage.setItem(KEY, provider)
    } else {
      window.localStorage.removeItem(KEY)
    }
  } catch {
    // no storage: the server's default provider is used
  }
}
