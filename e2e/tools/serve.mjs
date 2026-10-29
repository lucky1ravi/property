// Serves the built web app on :4173 (single-page-app fallback) and forwards /api/* to the gateway on :8080, like CloudFront does in AWS.
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../../frontend/dist', import.meta.url)));
const API = { host: 'localhost', port: Number(process.env.GATEWAY_PORT || 8080) };
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };

http.createServer((req, res) => {
  if (req.url.startsWith('/api/') || req.url.startsWith('/actuator')) {
    const p = http.request({ ...API, path: req.url, method: req.method, headers: req.headers }, (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
    p.on('error', () => { res.writeHead(502); res.end('gateway down'); });
    return req.pipe(p);
  }
  const clean = decodeURIComponent(req.url.split('?')[0]);
  let file = join(root, clean);
  if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) file = join(root, clean.startsWith('/remotes/') ? clean : 'index.html');
  if (!existsSync(file) || statSync(file).isDirectory()) file = join(root, 'index.html');
  res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
  createReadStream(file).pipe(res);
}).listen(4173, () => console.log('web on 4173'));
