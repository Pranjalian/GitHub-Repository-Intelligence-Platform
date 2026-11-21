import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import mermaid from 'mermaid'
import { describe, expect, it } from 'vitest'

/**
 * Diagrams the backend generates for its Spring Boot evaluation fixture (golden files kept in sync by
 * ArchitectureE2eTests) must parse with the Mermaid version the dashboard renders with.
 */
const GOLDEN = join(__dirname, '../../../backend/src/test/resources/golden/spring-boot-architecture')

describe('backend-generated Mermaid', () => {
  const files = readdirSync(GOLDEN).filter((name) => name.endsWith('.mmd'))

  it('has golden diagrams to check', () => {
    expect(files).toEqual(expect.arrayContaining(['apiFlow.mmd', 'entities.mmd', 'layers.mmd']))
  })

  it('rejects broken source, so a pass means something', async () => {
    await expect(mermaid.parse('flowchart LR\n  n1["unterminated')).rejects.toThrow()
  })

  it.each(files)('%s parses', async (name) => {
    const source = readFileSync(join(GOLDEN, name), 'utf8')
    await expect(mermaid.parse(source)).resolves.toBeTruthy()
  })
})
