import { describe, expect, it } from 'vitest'
import { primaryLanguage } from './primaryLanguage'

const lang = (name: string, sharePercent: number | string | null) => ({ name, sharePercent })

describe('primaryLanguage', () => {
  it('skips stylesheets and markup even when they have the largest share (spring-petclinic)', () => {
    const result = primaryLanguage([lang('CSS', 54.4), lang('Java', 27.5), lang('HTML', 6)])
    expect(result?.name).toBe('Java')
  })

  it('skips data formats and accepts shares sent as strings', () => {
    expect(primaryLanguage([lang('JSON', '60.0'), lang('TypeScript', '30.0')])?.name).toBe('TypeScript')
  })

  it('treats a missing share as zero', () => {
    expect(primaryLanguage([lang('Java', null), lang('CSS', 90)])?.name).toBe('Java')
    expect(primaryLanguage([lang('Java', null), lang('Shell', 0.5)])?.name).toBe('Shell')
  })

  it('falls back to the largest non-programming language when nothing else exists', () => {
    expect(primaryLanguage([lang('Markdown', 30), lang('CSS', 70)])?.name).toBe('CSS')
  })

  it('breaks ties alphabetically and returns undefined for no languages', () => {
    expect(primaryLanguage([lang('Go', 50), lang('C', 50)])?.name).toBe('C')
    expect(primaryLanguage([])).toBeUndefined()
  })
})
