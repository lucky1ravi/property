// Rebuilds the shell and every remote whenever their code (or packages/ui) changes, and serves the assembled
// site on http://localhost:4173. Run from frontend/:  npm run dev
// The module-federation plugin has no dev server for remotes, so this uses `vite build --watch` instead.
// Refresh the browser after a rebuild finishes (there is no hot reload).
import { spawn } from 'node:child_process';
import { cpSync, existsSync, mkdirSync } from 'node:fs';

const remotes = ['leasing', 'payments', 'documents', 'maintenance', 'broker', 'admin', 'insights'];
const apps = ['shell', ...remotes];
const PORT = process.env.PORT ?? '4173';
const built = new Set();
let timer = null;
let server = null;

function assemble() {
  mkdirSync('dist/remotes', { recursive: true });
  cpSync('apps/shell/dist', 'dist', { recursive: true });
  for (const r of remotes) cpSync(`apps/${r}/dist`, `dist/remotes/${r}`, { recursive: true });
  console.log(`\n[dev] ${new Date().toLocaleTimeString()} site updated. Refresh the browser.\n`);
  if (!server) {
    server = spawn('npx', ['--yes', 'serve', '-s', 'dist', '-l', PORT], { stdio: 'inherit', shell: true });
    console.log(`[dev] serving http://localhost:${PORT}`);
  }
}

// Wait for the first build of every app, then reassemble after each later build (batched, since one edit in
// packages/ui rebuilds every app).
function onBuilt(app) {
  built.add(app);
  if (built.size < apps.length) { console.log(`[dev] first build: ${built.size}/${apps.length} (${app})`); return; }
  clearTimeout(timer);
  timer = setTimeout(() => { try { assemble(); } catch (e) { console.error('[dev] could not assemble:', e.message); } }, 1000);
}

const children = apps.map((app) => {
  const p = spawn('npm', ['run', 'build', '-w', `@propertyos/${app}`, '--', '--watch'], { shell: true });
  const onData = (d) => {
    const text = d.toString();
    if (/error/i.test(text) && !/built in/.test(text)) process.stdout.write(`[${app}] ${text}`);
    if (/built in/.test(text)) onBuilt(app);
  };
  p.stdout.on('data', onData);
  p.stderr.on('data', onData);
  p.on('exit', (code) => { if (code) console.error(`[dev] ${app} watcher stopped (exit ${code})`); });
  return p;
});

const stop = () => { children.forEach((c) => c.kill()); server?.kill(); process.exit(0); };
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
if (!existsSync('apps/shell')) { console.error('Run this from the frontend/ folder.'); stop(); }
console.log(`[dev] building ${apps.length} apps; the first build takes a minute...`);
