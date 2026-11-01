#!/usr/bin/env node
/**
 * Checks the database migrations before they are deployed.
 *
 * Every service keeps its own Flyway migrations in backend/<service>/src/main/resources/db/migration and applies them itself when it starts.
 * What goes wrong in a real environment is never the SQL (the integration tests run all of it): it is a migration that was edited after it
 * had already been applied (Flyway then refuses to start, because the checksum no longer matches), a number used twice, or a gap.
 *
 *   node scripts/check-migrations.js                 versions are unique and run 1, 2, 3 ... with no gap; prints the inventory
 *   node scripts/check-migrations.js --base <ref>    also fails if a migration that exists at <ref> (for example the last deployed
 *                                                    tag, deployed/identity) was changed, renamed or deleted since
 *
 * Exit code 1 on any problem, so it can run in CI before a deploy.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const root = path.resolve(__dirname, '..');
const backend = path.join(root, 'backend');
const baseIdx = process.argv.indexOf('--base');
const base = baseIdx > 0 ? process.argv[baseIdx + 1] : null;

const problems = [];
const inventory = [];

for (const service of fs.readdirSync(backend).sort()) {
  const dir = path.join(backend, service, 'src', 'main', 'resources', 'db', 'migration');
  if (!fs.existsSync(dir)) continue;
  const versions = new Map();
  for (const file of fs.readdirSync(dir).sort()) {
    if (!file.endsWith('.sql') && !file.endsWith('.java')) continue;
    const m = /^V(\d+)__([a-z0-9_]+)\.sql$/.exec(file);
    if (!m) { problems.push(`${service}: "${file}" does not look like V<number>__<name_in_lowercase>.sql, so Flyway would ignore or reject it`); continue; }
    const version = Number(m[1]);
    if (versions.has(version)) problems.push(`${service}: version ${version} is used by both ${versions.get(version)} and ${file}`);
    versions.set(version, file);
    inventory.push({ service, version, name: m[2].replace(/_/g, ' ') });
  }
  const sorted = [...versions.keys()].sort((a, b) => a - b);
  sorted.forEach((v, i) => { if (v !== i + 1) problems.push(`${service}: expected version ${i + 1} but found ${v} (a gap, or a migration that is missing from this branch)`); });
}

if (base) {
  const git = (args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' });
  try { git(['rev-parse', '--verify', `${base}^{commit}`]); } catch { console.error(`Cannot find "${base}". Fetch tags first (git fetch --tags).`); process.exit(2); }
  const changes = git(['diff', '--name-status', '-M', base, '--', 'backend/*/src/main/resources/db/migration']).trim().split('\n').filter(Boolean);
  for (const line of changes) {
    const [status, ...files] = line.split('\t');
    if (status === 'A') continue; // a new migration is exactly what a release adds
    problems.push(`A migration that was already released was ${status === 'M' ? 'changed' : status === 'D' ? 'deleted' : 'renamed'}: ${files.join(' -> ')}. Flyway would refuse to start. Add a new migration instead.`);
  }
}

const byService = new Map();
for (const i of inventory) byService.set(i.service, [...(byService.get(i.service) ?? []), i]);
for (const [service, list] of byService) list.sort((a, b) => a.version - b.version);
for (const [service, list] of byService) console.log(`${service.padEnd(20)} ${list.length} migration${list.length === 1 ? '' : 's'}, latest V${list[list.length - 1].version} (${list[list.length - 1].name})`);
if (problems.length) {
  console.error(`\n${problems.length} problem${problems.length === 1 ? '' : 's'}:`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`\nAll migrations are numbered without gaps or repeats${base ? ` and none released at ${base} was altered` : ''}.`);
