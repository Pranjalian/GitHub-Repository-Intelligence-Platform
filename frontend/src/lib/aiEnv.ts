/**
 * The real environment variables for enabling AI, shown on the AI Settings page. A test checks that
 * every name here also appears in README.md, so the page and the README cannot drift apart.
 */
export const AI_ENV = {
  enabled: 'AI_ENABLED',
  ollamaEnabled: 'AI_PROVIDERS_OLLAMA_ENABLED',
  ollamaModel: 'OLLAMA_CHAT_MODEL',
  defaultProvider: 'AI_DEFAULT_PROVIDER',
  geminiEnabled: 'AI_PROVIDERS_GEMINI_ENABLED',
  geminiKey: 'GEMINI_API_KEY',
  geminiModel: 'GEMINI_CHAT_MODEL',
  geminiAck: 'AI_PROVIDERS_GEMINI_DATA_HANDLING_ACKNOWLEDGED',
  maxCalls: 'AI_MAX_CALLS_PER_ANALYSIS',
} as const

export const OLLAMA_COMMANDS = ['ollama serve', 'ollama pull <model>']

export const LOCAL_ENV_LINES = [`${AI_ENV.enabled}=true`, `${AI_ENV.ollamaEnabled}=true`, `${AI_ENV.ollamaModel}=<model you pulled>`]

export const HOSTED_ENV_LINES = [
  `${AI_ENV.enabled}=true`,
  `${AI_ENV.defaultProvider}=gemini`,
  `${AI_ENV.geminiEnabled}=true`,
  `${AI_ENV.geminiKey}=<your key>`,
  `${AI_ENV.geminiModel}=<model id>`,
  `${AI_ENV.geminiAck}=true`,
]

export const QUICKSTART = [...OLLAMA_COMMANDS, '', '# .env', ...LOCAL_ENV_LINES].join('\n')
