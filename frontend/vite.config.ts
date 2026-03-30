import { defineConfig } from 'vite'
import type { InlineConfig } from 'vitest/node'
import vue from '@vitejs/plugin-vue'

type ViteConfigWithVitest = {
  test: InlineConfig
}

const config: Parameters<typeof defineConfig>[0] & ViteConfigWithVitest = {
  plugins: [vue()],
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/health': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
}

export default defineConfig(config)
