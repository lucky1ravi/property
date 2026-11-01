// Checks every relative link in the Markdown files: the target file exists, and a #section anchor matches a heading in it.
//   node scripts/check-doc-links.js        exits 1 and lists each broken link
// External (http, mailto) links are not followed.
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'target', 'dist', '.git', 'test-results', 'playwright-report', '.run'].includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else if (p.endsWith('.md')) out.push(p);
  }
  return out;
}

// GitHub's rule for a heading's anchor: lower-case, drop punctuation, spaces to hyphens.
const slug = (h) => h.trim().toLowerCase().replace(/`/g, '').replace(/[^\p{L}\p{N}\s-]/gu, '').replace(/\s/g, '-');
const anchors = new Map();
function anchorsOf(file) {
  if (!anchors.has(file)) {
    const text = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').replace(/```[\s\S]*?```/g, '');
    const set = new Set();
    const seen = {};
    for (const m of text.matchAll(/^#{1,6}\s+(.+?)\s*#*$/gm)) {
      let s = slug(m[1].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1'));
      if (seen[s] !== undefined) { seen[s]++; s = `${s}-${seen[s]}`; } else seen[s] = 0;
      set.add(s);
    }
    anchors.set(file, set);
  }
  return anchors.get(file);
}

let bad = 0, total = 0;
for (const file of walk(ROOT)) {
  const text = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').replace(/```[\s\S]*?```/g, '');
  const rel = path.relative(ROOT, file).replace(/\\/g, '/');
  for (const m of text.matchAll(/\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
    const href = m[1];
    if (/^(https?:|mailto:|#?$)/.test(href) && !href.startsWith('#')) continue;
    total++;
    const [pathPart, hash] = href.split('#');
    const target = pathPart ? path.resolve(path.dirname(file), decodeURIComponent(pathPart)) : file;
    const line = text.slice(0, m.index).split('\n').length;
    if (!fs.existsSync(target)) { bad++; console.log(`${rel}:${line}  missing file  ${href}`); continue; }
    if (hash && target.endsWith('.md') && !anchorsOf(target).has(hash.toLowerCase())) { bad++; console.log(`${rel}:${line}  no such section  ${href}`); }
  }
}
console.log(`${total} links checked, ${bad} broken.`);
process.exit(bad ? 1 : 0);
