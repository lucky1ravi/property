// Checks every ```mermaid diagram in the given Markdown files with the real Mermaid parser, in a real browser, and names each one that
// would not draw (GitHub shows "Unable to render" for those). A semicolon inside a sequence-diagram message is the usual cause: Mermaid
// reads it as the end of a statement.
//   node tools/check-diagrams.mjs JOURNEYS.md ../docs/architecture/HLD.md ...
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { chromium } from '@playwright/test';

const require = createRequire(import.meta.url);
const files = process.argv.slice(2);
if (files.length === 0) { console.error('Give at least one Markdown file.'); process.exit(2); }
const mermaidSource = readFileSync(require.resolve('mermaid/dist/mermaid.min.js'), 'utf8');
const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent('<html><body></body></html>');
await page.addScriptTag({ content: mermaidSource });
await page.evaluate(() => window.mermaid.initialize({ startOnLoad: false }));
let total = 0, bad = 0;
for (const file of files) {
  const md = readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  for (const m of md.matchAll(/```mermaid\n([\s\S]*?)```/g)) {
    total++;
    const line = md.slice(0, m.index).split('\n').length + 1;
    const err = await page.evaluate(async (code) => { try { await window.mermaid.parse(code); return null; } catch (e) { return String(e.message ?? e).split('\n').slice(0, 3).join(' | '); } }, m[1]);
    if (err) { bad++; console.error(`${file}:${line}  ${m[1].split('\n')[0]}\n    ${err}`); }
  }
}
await browser.close();
console.log(`${total} diagram${total === 1 ? '' : 's'} checked, ${bad} that would not draw.`);
process.exit(bad ? 1 : 0);
