import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const styleCss = readFileSync(resolve(__dirname, 'style.css'), 'utf8').replace(/\r\n/g, '\n')
const ragPanelVue = readFileSync(resolve(__dirname, 'components/RagPanel.vue'), 'utf8').replace(/\r\n/g, '\n')

describe('workspace layout regression', () => {
  it('keeps page scrolling locked to internal panels on desktop', () => {
    expect(styleCss).toContain('body {\n  margin: 0;\n  min-width: 320px;\n  min-height: 100vh;\n  overflow: hidden;\n}')
    expect(styleCss).toContain('.app-shell {\n  position: relative;\n  width: 100%;\n  height: 100dvh;')
    expect(styleCss).toContain('.chat-panel {\n  position: relative;\n  display: flex;\n  flex-direction: column;\n  min-height: 0;\n  height: 100%;')
  })

  it('uses a more compact desktop typography and spacing baseline', () => {
    expect(styleCss).toContain('h2 {\n  font-size: 16px;')
    expect(styleCss).toContain('.panel {\n  border-radius: 22px;\n  padding: 16px;')
    expect(styleCss).toContain('.timeline-card {\n  padding: 10px;')
  })

  it('shrinks the inspector rail and navigation buttons on desktop', () => {
    expect(styleCss).toContain('.workspace {\n  display: grid;\n  grid-template-columns: minmax(0, 1fr) 54px;')
    expect(styleCss).toContain('.inspector-shell {\n  display: flex;\n  gap: 8px;\n  min-width: 54px;\n  width: 54px;')
    expect(styleCss).toContain('.inspector-nav {\n  flex: 0 0 54px;')
    expect(styleCss).toContain('.sidebar-toggle,\n.sidebar-tab {\n  width: 100%;\n  padding: 8px 6px;\n  font-size: 11px;')
  })
})

describe('rag panel interaction structure', () => {
  it('embeds expand toggles inside each section header instead of a shared quick-toggle strip', () => {
    expect(ragPanelVue).not.toContain('class="rag-quick-toggles"')
    expect(ragPanelVue).not.toContain('class="rag-source-preview"')
    expect(ragPanelVue).toContain('class="ghost-button rag-section-toggle"')
  })
})
