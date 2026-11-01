// A stand-in for an OpenAI-compatible model so the AI features can be tested end to end without a key or a network.
// It answers by keywords: the assistant gets tool calls, the studio gets small JSON documents, photos get a triage result.
import http from 'node:http';

const port = Number(process.env.FAKE_AI_PORT ?? 4599);
const completion = (content, toolCalls) => JSON.stringify({
  choices: [{ message: { role: 'assistant', content, ...(toolCalls ? { tool_calls: toolCalls } : {}) } }],
  usage: { prompt_tokens: 120, completion_tokens: 40 },
});
const calls = (...pairs) => pairs.map(([name, args], i) => ({ id: `call_${i}`, type: 'function', function: { name, arguments: args } }));
const lastUser = (messages) => [...messages].reverse().find((m) => m.role === 'user' && typeof m.content === 'string')?.content?.toLowerCase() ?? '';
const hasImage = (messages) => messages.some((m) => Array.isArray(m.content) && m.content.some((p) => p.type === 'image_url'));

function answer(req) {
  const messages = req.messages ?? [];
  const system = String(messages[0]?.content ?? '').toLowerCase();
  const user = lastUser(messages);
  if (user.includes('[model-error]')) return null;
  if (hasImage(messages)) {
    if (system.includes('maintenance coordinator')) {
      return completion(JSON.stringify({ category: 'PLUMBING', priority: 'NORMAL', safetyRisk: false, summary: 'A dripping pipe under the sink',
        likelyCause: 'Worn fitting', suggestedAction: 'Replace the fitting', estimateLow: 90, estimateHigh: 240, tenantAdvice: 'Place a bucket under the leak', confidence: 0.82 }));
    }
    return completion(JSON.stringify({ verdict: 'SAFE', confidence: 0.97, category: 'PROPERTY', labels: ['kitchen', 'counter'], reason: 'A clean kitchen.', quality: 88, caption: 'A bright kitchen' }));
  }
  if (req.tools) {
    const afterTool = messages[messages.length - 1]?.role === 'tool';
    // After a tool has run, the model writes its answer from what the tool returned, as a real one would.
    if (afterTool) return completion(`Here is what I found for you: ${String(messages[messages.length - 1].content ?? '').slice(0, 600)}`);
    if (user.includes('overdue') || user.includes('late')) return completion('', calls(['list_overdue_tenants', '{}']));
    if (user.includes('vacant')) return completion('', calls(['list_vacant_units', '{}']));
    if (user.includes('overview')) return completion('', calls(['portfolio_overview', '{}']));
    if (user.includes('propert')) return completion('', calls(['list_properties', '{}']));
    if (user.includes('balance') || user.includes('account')) return completion('', calls(['my_account', '{}']));
    return completion('I can help with your portfolio. Ask about vacancies, rent, repairs or leases.');
  }
  if (system.includes('write rental listing copy')) {
    // The copy mentions the home it was asked about, as the facts it is given are quoted back.
    const full = String(messages.filter((m) => m.role === 'user').map((m) => (typeof m.content === 'string' ? m.content : '')).pop() ?? '');
    const home = /"(?:pname|property_name|property)"\s*:\s*"([^"]+)"/.exec(full)?.[1];
    return completion(JSON.stringify({ title: home ? `${home}: sunny home near the park` : 'Sunny home near the park', description: `A bright home with new floors and a private balcony, close to shops and transit.${home ? ` Part of ${home}.` : ''}`, highlights: ['New floors', 'Private balcony'] }));
  }
  if (system.includes('fair housing act')) return completion(JSON.stringify({ issues: [] }));
  return completion(JSON.stringify({ summary: 'ok' }));
}

http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.startsWith('/v1/chat/completions')) { res.writeHead(404).end(); return; }
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    let out = null;
    try { out = answer(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch { /* falls through to a server error */ }
    res.writeHead(out ? 200 : 500, { 'Content-Type': 'application/json' }).end(out ?? '{"error":"fake model failure"}');
  });
}).listen(port, '127.0.0.1', () => console.log(`fake model on ${port}`));
