// Assembles one deployable folder: the shell at the root and each remote under /remotes/<name>/.
import { cpSync, rmSync, mkdirSync, existsSync } from 'node:fs';
const remotes = ['leasing', 'payments', 'maintenance', 'broker', 'admin', 'insights'];
rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/remotes', { recursive: true });
cpSync('apps/shell/dist', 'dist', { recursive: true });
for (const r of remotes) {
  const src = `apps/${r}/dist`;
  if (!existsSync(src)) throw new Error(`Missing build for ${r}`);
  cpSync(src, `dist/remotes/${r}`, { recursive: true });
}
console.log('Assembled dist/ with shell and', remotes.length, 'remotes');
