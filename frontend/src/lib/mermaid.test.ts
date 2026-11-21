import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import mermaid from 'mermaid'
import { describe, expect, it } from 'vitest'

/**
 * Diagrams the backend generates for its evaluation fixtures (golden files kept in sync by
 * ArchitectureE2eTests) must parse with the Mermaid version the dashboard renders with.
 */
const GOLDEN = join(__dirname, '../../../backend/src/test/resources/golden')

const diagrams = readdirSync(GOLDEN).flatMap((fixture) =>
  readdirSync(join(GOLDEN, fixture))
    .filter((name) => name.endsWith('.mmd'))
    .map((name) => `${fixture}/${name}`),
)

describe('backend-generated Mermaid', () => {
  it('has golden diagrams for every evaluation fixture', () => {
    expect(diagrams).toEqual(
      expect.arrayContaining([
        'spring-boot-architecture/apiFlow.mmd',
        'spring-boot-architecture/entities.mmd',
        'spring-boot-architecture/layers.mmd',
        'spring-multi-module/modules.mmd',
      ]),
    )
  })

  it('rejects broken source, so a pass means something', async () => {
    await expect(mermaid.parse('flowchart LR\n  n1["unterminated')).rejects.toThrow()
  })

  it.each(diagrams)('%s parses', async (name) => {
    const source = readFileSync(join(GOLDEN, name), 'utf8')
    await expect(mermaid.parse(source)).resolves.toBeTruthy()
  })
})
