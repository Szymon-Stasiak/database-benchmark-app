import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure(proxy) {
          // Vite installs its own noisy 'error' listener AFTER this configure()
          // runs (see vite/dist/node/chunks/node.js). Defer our override so we
          // wipe it and reply with a friendly 503 instead of the ECONNREFUSED
          // stack-trace spam during Spring Boot warm-up.
          queueMicrotask(() => {
            proxy.removeAllListeners('error')
            proxy.on('error', (err, _req, res) => {
              if (res && !res.headersSent && 'writeHead' in res) {
                try {
                  res.writeHead(503, { 'Content-Type': 'application/json' })
                  res.end(
                    JSON.stringify({
                      error: 'backend_unavailable',
                      message: 'Backend is still starting or unreachable',
                      code: (err as NodeJS.ErrnoException).code ?? 'UNKNOWN',
                    }),
                  )
                } catch {
                  // socket already closed — nothing we can do
                }
              }
            })
          })
        },
      },
    },
  },
})
