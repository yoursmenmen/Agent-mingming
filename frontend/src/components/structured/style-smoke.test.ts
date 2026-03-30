import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const styleCss = readFileSync(join(process.cwd(), 'src/style.css'), 'utf-8')

describe('structured card style smoke', () => {
  it('declares sakura soft-glass tokens', () => {
    expect(styleCss).toContain('--structured-glass-bg')
    expect(styleCss).toContain('--structured-glass-border')
    expect(styleCss).toContain('--structured-accent-ring')
  })

  it('contains structured card shell class hooks', () => {
    expect(styleCss).toContain('.message .structured-card-host .structured-card-shell')
    expect(styleCss).toContain('.message .structured-card-host .structured-card-shell::before')
  })

  it('contains structured entry animation and mobile rule', () => {
    expect(styleCss).toContain('@keyframes structured-card-enter')
    expect(styleCss).toContain('@media (max-width: 720px)')
    expect(styleCss).toContain('.message .structured-card-host .structured-grid')
  })
})
