import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import federation from '@originjs/vite-plugin-federation';
import path from 'node:path';

// Remote micro-frontend: built and deployed independently, loaded by the shell at runtime.
export default defineConfig({
  base: '/remotes/insights/',
  plugins: [
    react(),
    federation({
      name: 'insights',
      filename: 'remoteEntry.js',
      exposes: { './App': './src/App.tsx' },
      shared: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query', 'framer-motion'],
    }),
  ],
  resolve: { alias: { '@propertyos/ui': path.resolve(__dirname, '../../packages/ui/src/index.ts') } },
  build: { target: 'esnext', cssCodeSplit: false, outDir: 'dist', emptyOutDir: true },
});
