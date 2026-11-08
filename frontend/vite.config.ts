import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080', '/v1': 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], include: ['src/test/**/*.test.{ts,tsx}'], css: false, reporters: ['default', 'junit'], outputFile: { junit: '../test_run/reports/frontend.xml' } },
})
