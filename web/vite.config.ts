import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The bundle builds into the service's static resources, so one jar serves the API and the UI.
export default defineConfig({
  plugins: [react()],
  build: { outDir: '../service/src/main/resources/static', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8090' },
      '/ws': { target: 'ws://127.0.0.1:8090', ws: true },
    },
  },
})
