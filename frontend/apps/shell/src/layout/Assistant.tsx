import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Send, X, ShieldCheck, Database } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { api, AiMark, Button, Spinner, toast, toastError, type Persona } from '@propertyos/ui';

interface Msg {
  role: 'user' | 'assistant';
  content: string;
  sources?: { label: string; link?: string }[];
  tools?: string[];
  action?: { label: string; method: 'POST' | 'PUT'; path: string; body?: unknown; confirm: string };
  done?: boolean;
}

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
  const nav = useNavigate();
  useEffect(() => end.current?.scrollIntoView({ behavior: 'smooth' }), [msgs, busy]);

  const send = async (text: string) => {
    const q = text.trim();
    if (!q || busy) return;
    const next: Msg[] = [...msgs, { role: 'user', content: q }];
    setMsgs(next);
    setInput('');
    setBusy(true);
    try {
      const r = await api.post<{ reply: string; sources?: Msg['sources']; toolsUsed?: string[]; proposedAction?: Msg['action'] }>('/api/ai/chat', {
        messages: next.map((m) => ({ role: m.role, content: m.content })),
      });
      setMsgs([...next, { role: 'assistant', content: r.reply, sources: r.sources, tools: r.toolsUsed, action: r.proposedAction }]);
    } catch (e) {
      setMsgs([...next, { role: 'assistant', content: e instanceof Error ? e.message : 'The assistant is unavailable right now.' }]);
    } finally {
      setBusy(false);
    }
  };

  const runAction = async (i: number) => {
    const m = msgs[i];
    if (!m.action) return;
    try {
      if (m.action.method === 'POST') await api.post(m.action.path, m.action.body);
      else await api.put(m.action.path, m.action.body);
      toast(`${m.action.label}: done`);
      setMsgs(msgs.map((x, j) => (j === i ? { ...x, done: true } : x)));
    } catch (e) {
      toastError(e);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <motion.aside initial={{ x: '100%' }} animate={{ x: 0 }} exit={{ x: '100%' }} transition={{ type: 'spring', stiffness: 400, damping: 40 }}
          className="fixed inset-y-0 right-0 z-40 flex w-full max-w-[440px] flex-col border-l border-limestone-200 bg-white shadow-drawer" aria-label="PropertyOS assistant">
          <header className="flex items-center justify-between border-b border-limestone-200 px-5 py-3.5">
            <div className="flex items-center gap-2"><AiMark /><span className="font-bold text-graphite-900">Ask PropertyOS</span></div>
            <button onClick={onClose} aria-label="Close assistant" className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100"><X className="h-5 w-5" /></button>
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
                  <div className="max-w-[95%]">
                    <div className="whitespace-pre-wrap text-sm leading-relaxed text-graphite-900">{m.content}</div>
                    {m.tools && m.tools.length > 0 && (
                      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-2xs text-graphite-500">
                        <Database className="h-3 w-3" /> Looked up: {m.tools.join(', ')}
                      </div>
                    )}
                    {m.sources && m.sources.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {m.sources.map((s, j) => (
                          <button key={j} onClick={() => s.link && (nav(s.link), onClose())} className="rounded-full bg-limestone-100 px-2.5 py-1 text-xs font-medium text-federal-900 hover:bg-federal-100">{s.label}</button>
                        ))}
                      </div>
                    )}
                    {m.action && (
                      <div className="mt-3 rounded border border-brick-200 bg-brick-50/60 p-3">
                        <p className="text-sm text-graphite-800">{m.action.confirm}</p>
                        <Button size="sm" variant="accent" className="mt-2" disabled={m.done} onClick={() => runAction(i)}>{m.done ? 'Done' : m.action.label}</Button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
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
