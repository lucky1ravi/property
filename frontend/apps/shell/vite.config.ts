import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import federation from '@originjs/vite-plugin-federation';
import path from 'node:path';

// Host application. Remotes are loaded from REMOTE_BASE at runtime, so each domain
// team can deploy its micro-frontend independently of the shell.
const base = process.env.REMOTE_BASE ?? '';
const remote = (name: string) => `${base}/remotes/${name}/assets/remoteEntry.js`;

export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'shell',
      remotes: {
        leasing: remote('leasing'),
        payments: remote('payments'),
        maintenance: remote('maintenance'),
        broker: remote('broker'),
        admin: remote('admin'),
        insights: remote('insights'),
      },
      shared: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query', 'framer-motion'],
    }),
  ],
  resolve: { alias: { '@propertyos/ui': path.resolve(__dirname, '../../packages/ui/src/index.ts') } },
  build: { target: 'esnext', outDir: 'dist', emptyOutDir: true },
  server: { port: 5000 },
});
