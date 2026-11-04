import http from 'node:http';
import { createHmac } from 'node:crypto';
import type { AddressInfo } from 'node:net';

export interface Received { headers: http.IncomingHttpHeaders; body: string; json: any }

/**
 * A stand-in for the outside system a webhook calls (a CRM, Slack): it records every request and answers with the status you choose.
 * It listens on this machine, which the stack allows in tests only (PROPERTYOS_WEBHOOKS_ALLOW_PRIVATE_NETWORK, set by tools/local-stack.sh).
 */
export async function startReceiver(status = 200) {
  const got: Received[] = [];
  let answer = status;
  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      let json: any = null;
      try { json = JSON.parse(body); } catch { /* not JSON */ }
      got.push({ headers: req.headers, body, json });
      res.writeHead(answer); res.end();
    });
  });
  await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
  const port = (server.address() as AddressInfo).port;
  return {
    url: (path = '/hook') => `http://127.0.0.1:${port}${path}`,
    got,
    answerWith(s: number) { answer = s; },
    async close() { await new Promise<void>((ok) => server.close(() => ok())); },
  };
}

/** What a receiver does to check a delivery came from PropertyOS: HMAC-SHA256 of "timestamp.body" with the endpoint's secret. */
export function expectedSignature(secret: string, timestamp: string, body: string): string {
  return 'sha256=' + createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex');
}

/** What an ad platform does before posting to a campaign's inbound address: HMAC-SHA256 of the exact body with the campaign's secret. */
export function bodySignature(secret: string, body: string): string {
  return 'sha256=' + createHmac('sha256', secret).update(body).digest('hex');
}
