import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Send, X, ShieldCheck, Database, Download } from 'lucide-react';
import { api, AiMark, RichText, Button, Spinner, toastError, type Persona } from '@propertyos/ui';

interface Pending { id: string; tool: string; summary: string }
interface Msg {
  role: 'user' | 'assistant';
  content: string;
  tools?: string[];
  pending?: Pending;
  outcome?: 'confirmed' | 'cancelled';
  note?: string;
}
interface ChatReply { answer?: string; provider?: string; toolsUsed?: { name: string }[]; pendingAction?: Pending | null; suggestions?: string[] }

const SUGGESTIONS: Record<string, string[]> = {
  broker: ['What needs my attention today?', 'Which units are vacant over 20 days?', 'Who is at risk of paying late this month?'],
  manager: ['How is collection tracking this month?', 'Which work orders are breaching SLA?', 'Which leases end in the next 90 days?'],
  admin: ['Summarise portfolio performance this month', 'Which brokers have the most open items?', 'How many doors does each broker manage?'],
  agent: ['Which applications are missing documents?', 'What showings are booked this week?'],
  accountant: ['What is outstanding over 30 days?', 'Which owner payouts are due?'],
  owner: ['Why was my last payout lower?', 'What repairs are waiting for my approval?', 'How is my occupancy?'],
  tenant: ['What’s my balance?', 'When is my rent due?', 'What’s the status of my repair request?'],
  vendor: ['Which jobs are scheduled today?'],
};

/** Portfolio assistant. Answers come only from data the signed-in user can already see. */
export function Assistant({ open, onClose, persona }: { open: boolean; onClose: () => void; persona: Persona }) {
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const end = useRef<HTMLDivElement>(null);
  const [more, setMore] = useState<string[]>([]);
  const [exportOpen, setExportOpen] = useState(false);
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }); }, [msgs, busy]);

  const send = async (text: string) => {
    const q = text.trim();
    if (!q || busy) return;
    const history = msgs.filter((m) => m.content).map((m) => ({ role: m.role, content: m.content }));
    setMsgs([...msgs, { role: 'user', content: q }]);
    setInput('');
    setMore([]);
    setBusy(true);
    try {
      const r = await api.post<ChatReply>('/api/ai/chat', { message: q, history });
      setMsgs((cur) => [...cur, {
        role: 'assistant', content: r.answer ?? 'I couldn’t put an answer together. Try rephrasing the question.',
        tools: (r.toolsUsed ?? []).map((t) => t.name.replace(/_/g, ' ')), pending: r.pendingAction ?? undefined,
        note: r.provider === 'offline (model error)' ? 'The AI model didn’t respond, so built-in rules answered this one. Ask your administrator to check the AI service log.'
          : (r.provider ?? '').startsWith('offline') ? 'No AI model is configured, so built-in rules answered this one.' : undefined,
      }]);
      setMore(r.suggestions ?? []);
    } catch (e) {
      setMsgs((cur) => [...cur, { role: 'assistant', content: e instanceof Error ? e.message : 'The assistant is unavailable right now. Try again in a moment.' }]);
    } finally {
      setBusy(false);
    }
  };

  const stamp = () => new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
  const transcript = () => msgs.filter((m) => m.content).map((m) => `### ${m.role === 'user' ? 'You' : 'PropertyOS'}\n\n${m.content}${m.tools?.length ? `\n\n_Looked up: ${m.tools.join(', ')}_` : ''}`).join('\n\n---\n\n');
  const save = (name: string, body: string, type: string) => {
    const url = URL.createObjectURL(new Blob([body], { type }));
    const a = document.createElement('a');
    a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    setExportOpen(false);
  };
  const exportMd = () => save(`propertyos-chat-${stamp()}.md`, `# PropertyOS chat\n\nExported ${new Date().toLocaleString()}\n\n${transcript()}\n`, 'text/markdown');
  const exportTxt = () => save(`propertyos-chat-${stamp()}.txt`, msgs.filter((m) => m.content).map((m) => `${m.role === 'user' ? 'You' : 'PropertyOS'}: ${m.content}`).join('\n\n'), 'text/plain');
  const exportPdf = () => {
    const esc = (t: string) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    const html = `<html><head><title>PropertyOS chat</title><style>body{font:14px/1.5 system-ui,sans-serif;margin:32px;color:#1d2530}h1{font-size:20px}.m{margin:14px 0;padding:10px 12px;border-radius:6px;white-space:pre-wrap}.u{background:#e8eef8}.a{background:#f5f3ef}b{display:block;font-size:11px;color:#667;margin-bottom:4px}</style></head><body><h1>PropertyOS chat</h1><div>${esc(new Date().toLocaleString())}</div>${msgs.filter((m) => m.content).map((m) => `<div class="m ${m.role === 'user' ? 'u' : 'a'}"><b>${m.role === 'user' ? 'You' : 'PropertyOS'}</b>${esc(m.content)}</div>`).join('')}</body></html>`;
    const w = window.open('', '_blank');
    if (!w) { toastError(new Error('Allow pop-ups to export as PDF, or use the Markdown export.')); return; }
    w.document.write(html); w.document.close(); w.focus(); setTimeout(() => w.print(), 300);
    setExportOpen(false);
  };

  const decide = async (i: number, approve: boolean) => {
    const m = msgs[i];
    if (!m.pending) return;
    try {
      const r = await api.post<{ message?: string }>(`/api/ai/actions/${m.pending.id}/confirm`, { approve });
      setMsgs((cur) => [...cur.map((x, j) => (j === i ? { ...x, outcome: approve ? 'confirmed' as const : 'cancelled' as const } : x)), { role: 'assistant', content: r.message ?? (approve ? 'Done.' : 'Cancelled. Nothing was changed.') }]);
    } catch (e) {
      toastError(e);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.aside initial={{ x: '100%' }} animate={{ x: 0 }} exit={{ x: '100%' }} transition={{ type: 'spring', stiffness: 400, damping: 40 }}
          className="fixed inset-y-0 right-0 z-40 flex w-full max-w-[540px] flex-col border-l border-limestone-200 bg-white shadow-drawer" aria-label="PropertyOS assistant">
          <header className="flex items-center justify-between border-b border-limestone-200 px-5 py-3.5">
            <div className="flex items-center gap-2"><AiMark /><span className="font-bold text-graphite-900">Ask PropertyOS</span></div>
            <div className="flex items-center gap-1">
              <div className="relative">
                <button onClick={() => setExportOpen((v) => !v)} disabled={msgs.length === 0} aria-label="Export chat" aria-expanded={exportOpen} title={msgs.length ? 'Export chat' : 'Nothing to export yet'}
                  className="flex items-center gap-1 rounded p-1.5 text-sm text-graphite-600 hover:bg-limestone-100 disabled:opacity-40"><Download className="h-5 w-5" /><span className="hidden sm:inline">Export</span></button>
                {exportOpen && (
                  <div className="absolute right-0 top-full z-10 mt-1 w-44 rounded border border-limestone-200 bg-white py-1 text-sm shadow-drawer">
                    <button onClick={exportMd} className="block w-full px-3 py-2 text-left hover:bg-limestone-100">Markdown (.md)</button>
                    <button onClick={exportTxt} className="block w-full px-3 py-2 text-left hover:bg-limestone-100">Plain text (.txt)</button>
                    <button onClick={exportPdf} className="block w-full px-3 py-2 text-left hover:bg-limestone-100">PDF (print)</button>
                  </div>
                )}
              </div>
              <button onClick={onClose} aria-label="Close assistant" className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100"><X className="h-5 w-5" /></button>
            </div>
          </header>
          <div className="flex items-start gap-2 border-b border-limestone-200 bg-federal-50/60 px-5 py-2.5 text-xs text-federal-900">
            <ShieldCheck className="mt-px h-4 w-4 shrink-0" />
            Answers use only records your role can open. Actions are proposed, never taken without your confirmation.
          </div>
          <div className="scrollbar-thin flex-1 space-y-4 overflow-y-auto px-5 py-4">
            {msgs.length === 0 && (
              <div>
                <p className="text-sm text-graphite-600">Ask about your portfolio in plain English. Try:</p>
                <div className="mt-3 flex flex-col gap-2">
                  {(SUGGESTIONS[persona] ?? SUGGESTIONS.broker).map((s) => (
                    <button key={s} onClick={() => send(s)} className="rounded border border-limestone-300 px-3 py-2 text-left text-sm text-graphite-800 hover:border-federal-700 hover:bg-federal-50">{s}</button>
                  ))}
                </div>
              </div>
            )}
            {msgs.map((m, i) => (
              <div key={i} className={m.role === 'user' ? 'flex justify-end' : ''}>
                {m.role === 'user' ? (
                  <div className="max-w-[85%] rounded-lg rounded-br-sm bg-federal-900 px-3.5 py-2 text-sm text-white">{m.content}</div>
                ) : (
                  <div className="w-full min-w-0">
                    <div className="text-sm text-graphite-900"><RichText>{m.content}</RichText></div>
                    {m.tools && m.tools.length > 0 && (
                      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-2xs text-graphite-500">
                        <Database className="h-3 w-3" /> Looked up: {m.tools.join(', ')}
                      </div>
                    )}
                    {m.note && <div className="mt-2 text-2xs text-graphite-500">{m.note}</div>}
                    {m.pending && (
                      <div className="mt-3 rounded border border-brick-200 bg-brick-50/60 p-3">
                        <p className="text-sm text-graphite-800">{m.pending.summary}</p>
                        {m.outcome ? (
                          <p className="mt-2 text-xs font-semibold text-graphite-600">{m.outcome === 'confirmed' ? 'Confirmed' : 'Cancelled'}</p>
                        ) : (
                          <div className="mt-2 flex gap-2">
                            <Button size="sm" variant="accent" onClick={() => decide(i, true)}>Confirm</Button>
                            <Button size="sm" variant="quiet" onClick={() => decide(i, false)}>Cancel</Button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
            {!busy && more.length > 0 && msgs.length > 0 && (
              <div className="flex flex-wrap gap-1.5">{more.slice(0, 3).map((q) => <button key={q} onClick={() => send(q)} className="rounded-full bg-limestone-100 px-2.5 py-1 text-xs font-medium text-federal-900 hover:bg-federal-100">{q}</button>)}</div>
            )}
            {busy && <div className="flex items-center gap-2 text-sm text-graphite-500"><Spinner /> Checking your records…</div>}
            <div ref={end} />
          </div>
          <form onSubmit={(e) => { e.preventDefault(); send(input); }} className="flex items-center gap-2 border-t border-limestone-200 px-4 py-3">
            <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask about rent, repairs, leases…" aria-label="Message"
              className="h-10 flex-1 rounded border border-limestone-300 px-3 text-sm focus:border-federal-700 focus:outline-none focus:ring-2 focus:ring-federal-700/15" />
            <Button type="submit" variant="primary" disabled={!input.trim() || busy} aria-label="Send"><Send className="h-4 w-4" /></Button>
          </form>
        </motion.aside>
      )}
    </AnimatePresence>
  );
}
