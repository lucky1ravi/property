// Writes docs/api/<service>.md and docs/api/README.md from the controllers, so the endpoint reference cannot drift from the code.
//   node scripts/generate-api-docs.js           rewrite the pages
//   node scripts/generate-api-docs.js --check   exit 1 when a page is out of date (CI runs this)
// It reads source only. For the live, authoritative contract use the OpenAPI spec each service serves.
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'docs', 'api');
const CHECK = process.argv.includes('--check');

const SERVICES = [
  { key: 'identity', title: 'Identity', what: 'Sign-in, users, roles and permissions, companies, plans, configuration, audit, the public website content and the platform operator console.' },
  { key: 'leasing', title: 'Leasing', what: 'Owners, properties, units, amenities, listings, leads, showings, applications, leases, reviews, reports and the public homes search.' },
  { key: 'billing', title: 'Billing', what: 'Invoices, payments, deposits, expenses, ledger, owner statements, tax forms, and what companies pay PropertyOS.' },
  { key: 'maintenance', title: 'Maintenance', what: 'Work orders, vendors, vendor ratings, preventive maintenance and repair reports.' },
  { key: 'workflow', title: 'Workflow', what: 'Automation rules, workflow definitions, notifications and preferences, conversations, and the operator delivery log.' },
  { key: 'ai', title: 'AI', what: 'The assistant, the AI studio tools, guardrails and the AI audit.' },
  { key: 'document', title: 'Document', what: 'The document library, versions, photos and moderation, e-signature and document requirements.' },
];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'target', 'test'].includes(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out); else if (p.endsWith('.java')) out.push(p);
  }
  return out;
}
const strs = (s) => [...s.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
const firstSentence = (doc) => {
  const text = doc.replace(/\/\*\*|\*\//g, '').split('\n').map((l) => l.replace(/^\s*\*\s?/, '')).filter((l) => l.trim() && !l.trim().startsWith('@')).join(' ').replace(/<[^>]+>/g, '').replace(/\{@code ([^}]*)\}/g, '$1').replace(/\{@link ([^}]*)\}/g, '$1').replace(/\s+/g, ' ').trim();
  const m = /^(.+?[.!?])(\s|$)/.exec(text);
  return (m ? m[1] : text).replace(/\|/g, '\\|');
};

function endpoints(serviceKey) {
  const dir = path.join(ROOT, 'backend', `${serviceKey}-service`, 'src', 'main', 'java');
  const rows = [];
  for (const f of walk(dir)) {
    const src = fs.readFileSync(f, 'utf8');
    if (!/@(Get|Post|Put|Patch|Delete|Request)Mapping/.test(src)) continue;
    const cls = path.basename(f, '.java');
    const classAt = src.search(/\n(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s/);
    const head = classAt > 0 ? src.slice(0, classAt) : '';
    const baseM = [...head.matchAll(/@RequestMapping\(([^)]*)\)/g)].pop();
    const bases = baseM ? strs(baseM[1]) : [''];
    const classPerm = (/@RequiresPermission\("([^"]+)"\)/.exec(head) || [])[1];
    const re = /@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\(([^)]*)\))?/g;
    let m;
    while ((m = re.exec(src))) {
      if (classAt > 0 && m.index < classAt) continue;
      let method = m[1].toUpperCase();
      const args = m[2] || '';
      if (method === 'REQUEST') { const mm = /method\s*=\s*(?:RequestMethod\.)?(\w+)/.exec(args); method = mm ? mm[1] : 'ANY'; }
      let paths = strs(args.replace(/(consumes|produces|params)\s*=\s*[^,)]*/g, ''));
      if (paths.length === 0) paths = [''];
      const rest = src.slice(m.index);
      const sig = /\bpublic\s+[^;{]*?(\w+)\s*\(/.exec(rest);
      const window = sig ? rest.slice(0, sig.index) : rest.slice(0, 400);
      const perm = (/@RequiresPermission\("([^"]+)"\)/.exec(window) || [])[1] || classPerm;
      const before = src.slice(0, m.index);
      const docEnd = before.lastIndexOf('*/');
      let summary = '';
      if (docEnd > -1 && /^\s*(@[\w.]+(\([^)]*\))?\s*)*$/.test(before.slice(docEnd + 2))) summary = firstSentence(before.slice(before.lastIndexOf('/**', docEnd), docEnd + 2));
      for (const b of bases) for (const p of paths) {
        const full = (b + p).replace(/\/+/g, '/');
        if (!full.startsWith('/api/')) continue; // /internal/** is service to service only
        rows.push({ method, path: full, perm, summary, handler: `${cls}.${sig ? sig[1] : '?'}` });
      }
    }
  }
  const order = { GET: 0, POST: 1, PUT: 2, PATCH: 3, DELETE: 4 };
  return rows.sort((a, b) => a.path.localeCompare(b.path) || (order[a.method] ?? 9) - (order[b.method] ?? 9));
}

const access = (r) => (r.path.includes('/public/') ? 'open' : r.perm ? `\`${r.perm}\`` : 'signed-in');
const groupOf = (svc, p) => {
  const rest = p.replace(new RegExp(`^/api/${svc.apiBase}/?`), '');
  const first = rest.split('/')[0] || 'root';
  return first.startsWith('{') ? 'by id' : first;
};

const pages = {};
const summary = [];
for (const svc of SERVICES) {
  const rows = endpoints(svc.key);
  svc.apiBase = (rows[0] ? rows[0].path.split('/')[2] : svc.key);
  const open = rows.filter((r) => r.path.includes('/public/')).length;
  const op = rows.filter((r) => r.path.includes('/platform')).length;
  summary.push({ svc, count: rows.length, open, op });
  const groups = new Map();
  for (const r of rows) { const g = groupOf(svc, r.path); if (!groups.has(g)) groups.set(g, []); groups.get(g).push(r); }
  let md = `# ${svc.title} service API\n\n${svc.what}\n\n`;
  md += `| | |\n| --- | --- |\n| Base path | \`/api/${svc.apiBase}\` through the gateway (port 8080) |\n| Live contract | \`/api/${svc.apiBase}/public/v3/api-docs\` (OpenAPI 3), browsable in Swagger UI at \`/swagger-ui.html\` |\n| Endpoints | **${rows.length}** (${open} open to visitors, ${op} for platform operators) |\n| Source | \`backend/${svc.key}-service\` |\n\n`;
  md += '> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.\n\n';
  md += '**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.\n\n';
  md += '## Contents\n\n' + [...groups.keys()].map((g) => `- [${g}](#${g.toLowerCase().replace(/[^a-z0-9\s-]/g, '').replace(/\s/g, '-')}) (${groups.get(g).length})`).join('\n') + '\n';
  for (const [g, list] of groups) {
    md += `\n## ${g}\n\n| Method | Path | Access | What it does | Handler |\n| --- | --- | --- | --- | --- |\n`;
    for (const r of list) md += `| ${r.method} | \`${r.path}\` | ${access(r)} | ${r.summary || '&nbsp;'} | \`${r.handler}\` |\n`;
  }
  pages[`${svc.key}.md`] = md;
}

const total = summary.reduce((s, x) => s + x.count, 0);
let idx = '# API reference\n\nEvery capability of PropertyOS is a REST endpoint behind one gateway. This section lists them all.\n\n';
idx += '```mermaid\nflowchart LR\n  C[Browser or client] -->|"/api/&lt;service&gt;/**"| GW[API gateway :8080]\n';
for (const { svc } of summary) idx += `  GW --> ${svc.key.toUpperCase().slice(0, 3)}["${svc.key} /api/${svc.apiBase}"]\n`;
idx += '```\n\n';
idx += `| Service | What it serves | Endpoints | Page |\n| --- | --- | --- | --- |\n`;
for (const { svc, count } of summary) idx += `| ${svc.key} | ${svc.what} | ${count} | [${svc.key}.md](${svc.key}.md) |\n`;
idx += `| **Total** | | **${total}** | |\n\n`;
idx += '## How to use it\n\n1. Sign in: `POST /api/identity/public/login` with an email and password returns a JWT.\n2. Send it as `Authorization: Bearer <token>`.\n3. Browse and try every endpoint in Swagger UI at `/swagger-ui.html` on the gateway (click **Authorize**).\n\nConventions (errors, paging, filtering, idempotency, rate limits) are in the [API guide](../API.md). These pages are generated by `scripts/generate-api-docs.js`.\n';
pages['README.md'] = idx;

let stale = 0;
for (const [name, content] of Object.entries(pages)) {
  const file = path.join(OUT, name);
  const next = content.replace(/\r\n/g, '\n');
  const cur = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : '';
  if (cur !== next) { stale++; if (!CHECK) fs.writeFileSync(file, next); else console.log(`out of date: docs/api/${name}`); }
}
if (CHECK) { if (stale) { console.log(`${stale} API page(s) out of date. Run: node scripts/generate-api-docs.js`); process.exit(1); } console.log(`API pages are up to date (${total} endpoints).`); }
else console.log(`Wrote ${Object.keys(pages).length} pages, ${total} endpoints (${stale} changed).`);
