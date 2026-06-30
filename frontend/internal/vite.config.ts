import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:18090', changeOrigin: true },
      '/v1': { target: 'http://localhost:18090', changeOrigin: true },
    },
  },
})
