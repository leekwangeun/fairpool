import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // PGlite ships its own wasm; the demo database also reads the migration from ../supabase
  optimizeDeps: { exclude: ['@electric-sql/pglite'] },
  server: { host: true, fs: { allow: ['..'] } },
});
