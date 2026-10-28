import { useRef, useState } from 'react';
import { Link, Route, Routes, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Send, ShieldCheck } from 'lucide-react';
import {
  MicroApp, PageHeader, Panel, Stat, StatStrip, Button, Input, StatusPill, Badge, AiInsight, AiMark, EmptyState, Skeleton,
  useApi, useAction, api, fmt, DataTable, type Column,
} from '@propertyos/ui';

const go = (p: string) => (p.startsWith('/app') ? p : '/app' + p);
const KIND_LABEL: Record<string, string> = {
  EMERGENCY: 'Emergency', COLLECTIONS: 'Collections', APPLICATION: 'Application', RENEWAL: 'Renewal', LEAD: 'Lead',
  APPROVAL: 'Approval', VACANCY: 'Vacancy', SLA: 'SLA',
};
const kindTone = (k: string) => (k === 'EMERGENCY' ? 'crit' : k === 'COLLECTIONS' ? 'warn' : k === 'APPLICATION' ? 'info' : 'neutral');

function Queue() {
  const nav = useNavigate();
  const { data, isLoading } = useApi<{ queue: any[] }>('/api/ai/broker/queue');
  const rows = data?.queue ?? [];
  const cols: Column<any>[] = [
    { key: 'kind', header: 'Type', width: '120px', value: (r) => r.kind, render: (r) => <Badge tone={kindTone(r.kind) as any}>{KIND_LABEL[r.kind] ?? r.kind}</Badge> },
    { key: 'title', header: 'What needs you', render: (r) => (<div><div className="font-semibold text-graphite-900">{r.title}</div><div className="text-xs text-graphite-500">{r.subtitle}</div></div>) },
    { key: 'suggestion', header: 'Suggested next step', hideOnMobile: true, render: (r) => <span className="text-graphite-700">{r.suggestion}</span> },
    { key: 'dollars', header: 'At stake', align: 'right', value: (r) => r.dollars, render: (r) => (r.dollars ? fmt.usd(r.dollars) : '—') },
    { key: 'score', header: 'Priority', align: 'right', value: (r) => r.score },
  ];
  return (
    <Panel title="Work queue" description="Everything across your portfolio that needs a person, ranked by urgency and money at stake." padded={false}>
      <DataTable rows={rows} columns={cols} rowKey={(r) => r.path + r.title} loading={isLoading} searchPlaceholder="Filter the queue" exportName="broker-queue"
        initialSort={{ key: 'score', dir: 'desc' }} onRowClick={(r) => nav(go(r.path))}
        empty={{ title: 'You’re all caught up', body: 'Nothing needs attention right now.' }} />
    </Panel>
  );
}

function Opportunities({ limit }: { limit?: number }) {
  const nav = useNavigate();
  const { data, isLoading } = useApi<{ items: any[] }>('/api/ai/opportunities');
  const items = (data?.items ?? []).slice(0, limit ?? 100);
  if (isLoading) return <Skeleton className="h-40" />;
  if (!items.length) return <EmptyState title="No opportunities found" body="Check back after more activity is recorded." />;
  return (
    <div className="space-y-3">
      {items.map((o, i) => (
        <motion.div key={o.title + i} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: Math.min(i, 8) * 0.04 }}>
          <AiInsight title={o.title} confidence={o.confidence} tone={o.type === 'COLLECTIONS' ? 'warn' : 'neutral'}
            footer={<Button size="sm" variant="primary" onClick={() => nav(go(o.path))}>{o.actionLabel}</Button>}>
            <p>{o.detail}</p>
            <p className="mt-1 text-xs text-graphite-500">
              Worth about <b className="text-graphite-900">{fmt.usd(o.impact)}</b> {o.impactKind === 'ANNUAL' ? 'a year' : 'one time'} · effort {String(o.effort).toLowerCase()}
            </p>
          </AiInsight>
        </motion.div>
      ))}
    </div>
  );
}

interface Msg { role: 'user' | 'ai'; text: string; pending?: any; blocked?: boolean }

function Assistant() {
  const [msgs, setMsgs] = useState<Msg[]>([{ role: 'ai', text: 'Ask about overdue rent, vacancies, applications or repairs. I only see what your role is allowed to see.' }]);
  const [input, setInput] = useState('');
  const [sugg, setSugg] = useState<string[]>(['What needs my attention today?', 'Who is overdue on rent?', 'Which units are vacant?', 'Where can I earn more?']);
  const end = useRef<HTMLDivElement>(null);
  const send = useAction((q: string) => api.post<any>('/api/ai/chat', { message: q }), {
    silent: true,
    onSuccess: (r) => {
      setMsgs((m) => [...m, { role: 'ai', text: r.answer, pending: r.pendingAction, blocked: r.blocked }]);
      if (r.suggestions) setSugg(r.suggestions);
      setTimeout(() => end.current?.scrollIntoView({ behavior: 'smooth' }), 50);
    },
  });
  const confirm = useAction((id: string) => api.post<any>(`/api/ai/actions/${id}/confirm`, {}), {
    success: 'Done', onSuccess: (r) => setMsgs((m) => [...m, { role: 'ai', text: r.message ?? r.answer ?? 'Action completed.' }]),
  });
  const ask = (q: string) => {
    if (!q.trim()) return;
    setMsgs((m) => [...m, { role: 'user', text: q }]);
    setInput('');
    send.mutate(q);
  };
  return (
    <Panel title={<span className="flex items-center gap-2"><AiMark /> Assistant</span>} description="Answers come from your own data under your permissions." padded={false}>
      <div className="max-h-[380px] space-y-3 overflow-y-auto p-4">
        {msgs.map((m, i) => (
          <div key={i} className={m.role === 'user' ? 'flex justify-end' : ''}>
            <div className={`max-w-[92%] whitespace-pre-wrap rounded-md px-3 py-2 text-sm ${m.role === 'user' ? 'bg-federal-900 text-white' : m.blocked ? 'bg-brick-50 text-graphite-900' : 'bg-limestone-100 text-graphite-900'}`}>
              {m.text}
              {m.pending && (
                <div className="mt-2 rounded border border-limestone-300 bg-white p-2 text-xs text-graphite-700">
                  <div className="font-semibold">Needs your confirmation</div>
                  <div>{m.pending.summary ?? m.pending.description}</div>
                  <Button size="sm" variant="primary" className="mt-2" loading={confirm.isPending} onClick={() => confirm.mutate(m.pending.id)}>Confirm</Button>
                </div>
              )}
            </div>
          </div>
        ))}
        {send.isPending && <div className="text-xs text-graphite-500">Thinking…</div>}
        <div ref={end} />
      </div>
      <div className="flex flex-wrap gap-2 border-t border-limestone-200 px-4 pt-3">
        {sugg.map((s) => <button key={s} onClick={() => ask(s)} className="rounded-full border border-limestone-300 px-3 py-1 text-xs text-graphite-700 hover:bg-limestone-50">{s}</button>)}
      </div>
      <form className="flex gap-2 p-4" onSubmit={(e) => { e.preventDefault(); ask(input); }}>
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ask anything about your portfolio" aria-label="Message" />
        <Button variant="primary" type="submit" icon={<Send size={14} />} disabled={send.isPending}>Send</Button>
      </form>
      <div className="flex items-center gap-2 border-t border-limestone-200 px-4 py-2 text-2xs text-graphite-500"><ShieldCheck size={12} /> Personal data is redacted, changes need your confirmation, every request is logged.</div>
    </Panel>
  );
}

function Cockpit() {
  const { data: q } = useApi<{ queue: any[] }>('/api/ai/broker/queue');
  const { data: o } = useApi<{ items: any[] }>('/api/ai/opportunities');
  const queue = q?.queue ?? [];
  const count = (k: string) => queue.filter((x) => x.kind === k).length;
  const upside = (o?.items ?? []).reduce((s, i) => s + (i.impact ?? 0), 0);
  return (
    <>
      <PageHeader title="Broker cockpit" description="Your day, prioritised. Routine work runs on automation; this is what is left for you."
        actions={<Link to="/app/broker/opportunities"><Button variant="secondary">All opportunities</Button></Link>} />
      <StatStrip className="mb-6">
        <Stat label="Needs attention" value={queue.length} emphasis sub="items in queue" />
        <Stat label="Emergencies" value={count('EMERGENCY')} />
        <Stat label="Applications to decide" value={count('APPLICATION')} />
        <Stat label="Renewals due" value={count('RENEWAL')} />
        <Stat label="Profit upside found" value={fmt.usd(upside)} sub="by AI analysis" />
      </StatStrip>
      <div className="grid gap-6 xl:grid-cols-[1fr_380px]">
        <div className="space-y-6">
          <Queue />
          <Panel title="Top opportunities" description="Ways to earn more or lose less, with the reasoning shown."><Opportunities limit={4} /></Panel>
        </div>
        <Assistant />
      </div>
    </>
  );
}

export default function App() {
  return (
    <MicroApp name="broker">
      <Routes>
        <Route index element={<Cockpit />} />
        <Route path="opportunities" element={<><PageHeader title="Profit opportunities" description="Ranked by dollar impact. Each item shows how confident the analysis is." /><Opportunities /></>} />
      </Routes>
    </MicroApp>
  );
}
