// Compares every API call in the frontend with the endpoints the backend serves, by HTTP method and path.
// Run from the repo root:  node scripts/check-endpoints.js     (exits 1 when a call has no matching endpoint)
// It reads source only, so it cannot see query parameters, request bodies, or what a running service has deployed.
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');

function walk(dir, ok, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'dist', 'target', '.git'].includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, ok, out);
    else if (ok(p)) out.push(p);
  }
  return out;
}

// ---------- backend: every @Get/Post/Put/Patch/Delete/RequestMapping, with the class-level prefix ----------
const backend = [];
const strVals = (s) => [...s.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
for (const f of walk(path.join(ROOT, 'backend'), (p) => /\.java$/.test(p) && /src[\\/]main/.test(p))) {
  const src = fs.readFileSync(f, 'utf8');
  if (!/@(Get|Post|Put|Patch|Delete|Request)Mapping/.test(src)) continue;
  const classAt = src.search(/\n(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s/);
  const clsM = [...(classAt > 0 ? src.slice(0, classAt) : '').matchAll(/@RequestMapping\(([^)]*)\)/g)].pop();
  const bases = clsM ? strVals(clsM[1]) : [''];
  const re = /@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\(([^)]*)\))?/g;
  let m;
  while ((m = re.exec(src))) {
    if (classAt > 0 && m.index < classAt) continue;
    let method = m[1].toUpperCase();
    const args = m[2] || '';
    if (method === 'REQUEST') {
      const mm = /method\s*=\s*(?:RequestMethod\.)?(\w+)/.exec(args);
      method = mm ? mm[1] : 'ANY';
    }
    let paths = strVals(args.replace(/(consumes|produces|params)\s*=\s*[^,)]*/g, ''));
    if (paths.length === 0) paths = [''];
    for (const b of bases) for (const p of paths) backend.push({ method, path: (b + p).replace(/\/+/g, '/') || '/' });
  }
}
const toRe = (p) => new RegExp('^' + p.replace(/\{[^}/]+\}/g, '{}').replace(/\/+$/, '').replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\\\{\\\}|\{\}/g, '[^/]+') + '$');
const served = backend.map((b) => ({ ...b, re: toRe(b.path) }));

// ---------- frontend ----------
// Reads a string or template literal starting at src[i]; each ${...} becomes \u0001.
function readLiteral(src, i) {
  const q = src[i];
  let out = '';
  let j = i + 1;
  while (j < src.length) {
    const c = src[j];
    if (c === '\\') { out += src[j + 1]; j += 2; continue; }
    if (c === q) return { text: out, end: j + 1 };
    if (q === '`' && c === '$' && src[j + 1] === '{') {
      let depth = 1;
      j += 2;
      while (j < src.length && depth > 0) {
        const d = src[j];
        if (d === '"' || d === "'" || d === '`') { j = readLiteral(src, j).end; continue; }
        if (d === '{') depth++;
        else if (d === '}') depth--;
        j++;
      }
      out += '\u0001';
      continue;
    }
    out += c;
    j++;
  }
  return { text: out, end: j };
}

const calls = [];
for (const f of walk(path.join(ROOT, 'frontend'), (p) => /\.(tsx|ts)$/.test(p) && !/\.d\.ts$/.test(p))) {
  const src = fs.readFileSync(f, 'utf8');
  const consts = {};
  for (const m of src.matchAll(/const\s+([A-Za-z_]\w*)\s*=\s*(['`])(\/api\/[^'`]*)\2/g)) consts[m[1]] = m[3].split('?')[0].replace(/\$\{[^}]*\}/g, '{}');
  const rel = path.relative(ROOT, f).replace(/\\/g, '/');
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  const callRe = /\b(?:(api|http)\.(get|post|put|patch|del|delete)|(useApi|usePagedApi))\s*(<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*/g;
  let m;
  while ((m = callRe.exec(src))) {
    const method = m[3] ? 'GET' : m[2] === 'del' ? 'DELETE' : m[2].toUpperCase();
    let i = m.index + m[0].length;
    if (!/['"`]/.test(src[i])) {
      // cond ? '/api/x' : null
      const q = /^[^'"`;]*?\?\s*(?=['"`])/.exec(src.slice(i, i + 300));
      if (q) i += q[0].length;
    }
    if (!/['"`]/.test(src[i])) {
      const id = /^([A-Za-z_]\w*)/.exec(src.slice(i));
      if (id && consts[id[1]]) calls.push({ method, path: consts[id[1]], file: rel, line: lineOf(m.index) });
      else calls.push({ method, path: null, raw: src.slice(i, i + 50).split('\n')[0], file: rel, line: lineOf(m.index) });
      continue;
    }
    let text = readLiteral(src, i).text;
    const lead = /^\$\{\s*([A-Za-z_]\w*)\s*\}/.exec(src.slice(i + 1));
    if (text.startsWith('\u0001') && lead && consts[lead[1]]) text = consts[lead[1]] + text.slice(1);
    if (!text.startsWith('/api/')) continue;
    // an interpolation straight after a path (not after "/") is a query string; one after "/" is an id
    let p = text.split('?')[0].replace(/([^/])\u0001+$/, '$1').replace(/\u0001/g, '{}').replace(/\/+$/, '');
    calls.push({ method, path: p, dynamic: /\u0001/.test(text.split('?')[0].replace(/\/\u0001/g, '')), file: rel, line: lineOf(m.index) });
  }
}

// ---------- compare ----------
const missing = [], wrong = [], byHand = [];
const seen = new Set();
for (const c of calls) {
  if (c.path === null) { byHand.push(c); continue; }
  if (c.dynamic) { byHand.push({ ...c, raw: c.path }); continue; }
  const key = c.method + c.path + c.file;
  if (seen.has(key)) continue;
  seen.add(key);
  // A {} in the call is an id, or a word the code fills in (owners, properties, units...), so match wildcards both ways.
  const probe = c.path.replace(/\{\}/g, 'x');
  const callRe = toRe(c.path);
  const hit = (b) => b.re.test(probe) || callRe.test(b.path.replace(/\{[^}/]+\}/g, 'x'));
  if (served.some((b) => (b.method === c.method || b.method === 'ANY') && hit(b))) continue;
  const other = served.filter(hit);
  if (other.length) wrong.push({ ...c, has: [...new Set(other.map((b) => b.method))].join('/') });
  else missing.push(c);
}
const show = (c) => `  ${c.method.padEnd(6)} ${c.path}   ${c.file}:${c.line}${c.has ? '   (the backend has: ' + c.has + ')' : ''}`;
console.log(`${served.length} backend endpoints, ${calls.length} frontend calls.`);
console.log(`\nNo backend endpoint for these (${missing.length}):`);
missing.forEach((c) => console.log(show(c)));
console.log(`\nBackend has the path but not that method (${wrong.length}):`);
wrong.forEach((c) => console.log(show(c)));
console.log(`\nBuilt from variables, so not checked (${byHand.length}) - review by hand if they change:`);
byHand.forEach((c) => console.log(`  ${c.method.padEnd(6)} ${(c.raw ?? '').slice(0, 60)}   ${c.file}:${c.line}`));
const bad = missing.length + wrong.length;
console.log(bad === 0 ? '\nOK: every checked frontend call has a backend endpoint with that method.' : '\nMISMATCHES FOUND.');
process.exit(bad === 0 ? 0 : 1);
