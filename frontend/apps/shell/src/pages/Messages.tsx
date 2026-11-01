import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, Download, MessageSquarePlus, Send, Sparkles, RotateCcw, Search } from 'lucide-react';
import { PageHeader, Button, Avatar, Badge, Field, Input, Select, Textarea, Modal, Skeleton, EmptyState, Spinner, MultiEntityPicker, useApi, useAction, api, fmt, useSession } from '@propertyos/ui';

const KIND_LABEL: Record<string, string> = { STAFF: 'Staff', TENANT: 'Resident', OWNER: 'Owner', VENDOR: 'Vendor' };
const CONTEXT_LABEL: Record<string, string> = { GENERAL: 'General', WORK_ORDER: 'Work order', INVOICE: 'Invoice', LEASE: 'Lease', APPLICATION: 'Application', PROPERTY: 'Property' };
const inv = ['/api/workflow/chat'];

function ago(ts: string) {
  const s = (Date.now() - new Date(ts).getTime()) / 1000;
  if (s < 60) return 'now';
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return fmt.date(ts);
}

function NewConversation({ open, onClose, preset, onCreated }: { open: boolean; onClose: () => void; preset: any; onCreated: (id: string) => void }) {
  const [f, setF] = useState<any>({ subject: '', body: '', contextType: 'GENERAL', contextId: '', contextLabel: '', memberIds: [] as string[] });
  useEffect(() => { if (open) setF((x: any) => ({ ...x, subject: preset?.subject ?? '', contextType: preset?.contextType ?? 'GENERAL', contextId: preset?.contextId ?? '', contextLabel: preset?.contextLabel ?? '', memberIds: [], body: '' })); }, [open, preset]);
  const go = useAction(() => api.post<any>('/api/workflow/chat/conversations', f), { success: 'Conversation started', invalidate: inv, onSuccess: (r: any) => { onClose(); onCreated(r.id); } });
  return (
    <Modal open={open} onClose={onClose} title="New conversation" size="lg"
      footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={go.isPending} disabled={!f.subject.trim() || !f.body.trim() || f.memberIds.length === 0} onClick={() => go.mutate(undefined)}>Send</Button></>}>
      <div className="space-y-4">
        {f.contextLabel && <div className="rounded border border-limestone-200 bg-limestone-50 px-3 py-2 text-sm">About: <b>{CONTEXT_LABEL[f.contextType] ?? f.contextType}</b> · {f.contextLabel}</div>}
        <Field label="Subject" required><Input value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} placeholder="What is this about?" /></Field>
        <div>
          <span className="mb-1 block text-[13px] font-semibold text-graphite-800">Who to message<span className="text-brick-600"> *</span></span>
          <MultiEntityPicker<any> endpoint="/api/workflow/chat/contacts" value={f.memberIds} onChange={(ids) => setF({ ...f, memberIds: ids })} avatar
            label={(c) => c.name} sub={(c) => c.title} tag={(c) => KIND_LABEL[c.kind] ?? c.kind}
            placeholder="Search people by name or title" hint="Your company’s messaging rules decide who you can reach." />
        </div>
        <Field label="Message" required><Textarea rows={4} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} placeholder="Write your message" /></Field>
      </div>
    </Modal>
  );
}

export function Messages() {
  const { can, session } = useSession();
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const sel = sp.get('c');
  // The inbox is searched and paged on the server: 30 at a time, and "Show more" for the rest.
  const [convQ, setConvQ] = useState('');
  const [convLimit, setConvLimit] = useState(30);
  const [convTerm, setConvTerm] = useState('');
  useEffect(() => { const t = setTimeout(() => { setConvTerm(convQ.trim()); setConvLimit(30); }, 300); return () => clearTimeout(t); }, [convQ]);
  const { data: list, isLoading, refetch: refetchList } = useApi<any>(`/api/workflow/chat/conversations?limit=${convLimit}${convTerm ? `&q=${encodeURIComponent(convTerm)}` : ''}`, { refetchInterval: 15_000, placeholderData: keepPreviousData });
  const { data: conv, refetch: refetchConv } = useApi<any>(sel ? `/api/workflow/chat/conversations/${sel}` : null, { refetchInterval: 8_000 });
  const [text, setText] = useState('');
  const [newOpen, setNewOpen] = useState(false);
  const [catchup, setCatchup] = useState<any>(null);
  const end = useRef<HTMLDivElement>(null);
  const preset = useMemo(() => (sp.get('new') ? { subject: sp.get('subject') ?? '', contextType: sp.get('ctx') ?? 'GENERAL', contextId: sp.get('id') ?? '', contextLabel: sp.get('label') ?? '' } : null), [sp]);
  useEffect(() => { if (sp.get('new')) setNewOpen(true); }, [sp]);
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }); }, [conv?.messages?.length, sel]);
  useEffect(() => { setCatchup(null); }, [sel]);

  const send = useAction(() => api.post(`/api/workflow/chat/conversations/${sel}/messages`, { body: text }), { invalidate: inv, onSuccess: () => { setText(''); refetchConv(); refetchList(); } });
  const resolve = useAction((resolved: boolean) => api.post(`/api/workflow/chat/conversations/${sel}/resolve`, { resolved }), { invalidate: inv, onSuccess: () => { refetchConv(); refetchList(); } });
  const assist = useAction(() => api.post<any>(`/api/workflow/chat/conversations/${sel}/assist`), { onSuccess: (r: any) => setCatchup(r) });
  const open = (id: string | null) => { const n = new URLSearchParams(); if (id) n.set('c', id); setSp(n, { replace: true }); };
  const closeNew = () => { setNewOpen(false); if (sp.get('new')) open(sel); };

  const exportChat = () => {
    if (!conv) return;
    const body = `# ${conv.subject}\n${conv.context_label ? `About: ${conv.context_label}\n` : ''}\n` + conv.messages.map((m: any) => `**${m.sender_name}** (${fmt.date(m.created_at)})\n${m.body}`).join('\n\n');
    const url = URL.createObjectURL(new Blob([body], { type: 'text/markdown' }));
    const a = document.createElement('a'); a.href = url; a.download = `conversation-${String(conv.subject).replace(/[^A-Za-z0-9]+/g, '-').toLowerCase()}.md`; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const items: any[] = list?.items ?? [];
  const me = session?.userId;
  return (<>
    <PageHeader title="Messages" description="Conversations about your properties, repairs and payments. Each one stays attached to its record."
      actions={can('CONVERSATION:CREATE') && <Button variant="primary" onClick={() => setNewOpen(true)}><MessageSquarePlus className="mr-1.5 h-4 w-4" />New conversation</Button>} />
    <div className="grid gap-4 md:grid-cols-[320px_1fr]" style={{ minHeight: '60vh' }}>
      <section className={`rounded-md border border-limestone-200 bg-white ${sel ? 'hidden md:block' : ''}`} aria-label="Conversations">
        <div className="border-b border-limestone-200 p-2">
          <div className="relative"><Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-graphite-400" aria-hidden />
            <input value={convQ} onChange={(e) => setConvQ(e.target.value)} placeholder="Search conversations" aria-label="Search conversations"
              className="h-9 w-full rounded border border-limestone-300 bg-limestone-50 pl-8 pr-3 text-sm focus:border-federal-700 focus:bg-white focus:outline-none focus:ring-2 focus:ring-federal-700/15" /></div>
        </div>
        {isLoading ? <div className="p-4"><Skeleton className="h-40" /></div> : items.length === 0
          ? <EmptyState title={convTerm ? `Nothing matches “${convTerm}”` : 'No conversations yet'} body={convTerm ? 'Try a different name or word.' : 'Start one from here, or from an invoice or work order.'} />
          : <ul className="divide-y divide-limestone-200">{items.map((c) => (
            <li key={c.id}><button onClick={() => open(c.id)} className={`flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-limestone-50 ${sel === c.id ? 'bg-federal-50' : ''}`}>
              <Avatar name={c.with_names || c.subject} size={36} seed={c.id} />
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline justify-between gap-2"><span className={`truncate text-sm ${Number(c.unread) ? 'font-bold' : 'font-semibold'} text-graphite-900`}>{c.subject}</span><span className="shrink-0 text-2xs text-graphite-500">{ago(c.last_message_at)}</span></span>
                <span className="block truncate text-xs text-graphite-500">{c.with_names}</span>
                <span className="block truncate text-xs text-graphite-600">{c.last_sender ? `${c.last_sender}: ` : ''}{c.last_body}</span>
                <span className="mt-1 flex items-center gap-1.5">{c.context_type !== 'GENERAL' && <Badge>{CONTEXT_LABEL[c.context_type]}</Badge>}{c.status === 'RESOLVED' && <Badge tone="good">Resolved</Badge>}{Number(c.unread) > 0 && <span className="rounded-full bg-brick-600 px-1.5 text-[10px] font-bold text-white">{c.unread}</span>}</span>
              </span></button></li>))}</ul>}
        {items.length > 0 && <div className="flex items-center justify-between gap-2 border-t border-limestone-200 px-3 py-2 text-xs text-graphite-500">
          <span>Showing {items.length} of {Number(list?.total ?? items.length).toLocaleString()}</span>
          {Number(list?.total ?? 0) > items.length && <button type="button" className="font-semibold text-federal-800 hover:underline" onClick={() => setConvLimit((n) => Math.min(n + 30, 200))}>Show more</button>}
        </div>}
      </section>
      <section className={`flex min-h-[60vh] flex-col rounded-md border border-limestone-200 bg-white ${sel ? '' : 'hidden md:flex'}`} aria-label="Conversation">
        {!sel ? <div className="m-auto p-8 text-center text-sm text-graphite-500">Choose a conversation to read it.</div> : !conv ? <div className="p-4"><Skeleton className="h-40" /></div> : (<>
          <header className="flex flex-wrap items-center gap-2 border-b border-limestone-200 px-4 py-3">
            <button className="rounded p-1.5 hover:bg-limestone-100 md:hidden" onClick={() => open(null)} aria-label="Back to conversations"><ArrowLeft className="h-5 w-5" /></button>
            <div className="min-w-0 flex-1"><h2 className="truncate text-base font-bold normal-case tracking-normal text-graphite-900">{conv.subject}</h2>
              <p className="truncate text-xs text-graphite-500">{conv.members.map((m: any) => m.user_name).join(', ')}{conv.context_label ? ` · ${CONTEXT_LABEL[conv.context_type]}: ${conv.context_label}` : ''}</p></div>
            <Button size="sm" variant="quiet" loading={assist.isPending} onClick={() => assist.mutate(undefined)}><Sparkles className="mr-1 h-4 w-4" />Catch up</Button>
            {can('CONVERSATION:EXPORT') && <Button size="sm" variant="quiet" onClick={exportChat}><Download className="mr-1 h-4 w-4" />Export</Button>}
            {conv.status === 'RESOLVED' ? <Button size="sm" variant="quiet" onClick={() => resolve.mutate(false)}><RotateCcw className="mr-1 h-4 w-4" />Reopen</Button>
              : <Button size="sm" variant="secondary" onClick={() => resolve.mutate(true)}><CheckCircle2 className="mr-1 h-4 w-4" />Resolve</Button>}
          </header>
          {catchup && <div className="border-b border-limestone-200 bg-federal-50/60 px-4 py-3 text-sm">
            <div className="flex items-start justify-between gap-3"><div><b>Catch-up</b>{catchup.source === 'AI' ? '' : ' (summary from message text)'}<p className="mt-1">{catchup.summary}</p>
              {catchup.openQuestions?.length > 0 && <ul className="mt-1 list-disc pl-5 text-graphite-700">{catchup.openQuestions.map((q: string, i: number) => <li key={i}>{q}</li>)}</ul>}
              {catchup.suggestedReply && <div className="mt-2 rounded border border-limestone-200 bg-white p-2"><div className="text-xs font-semibold text-graphite-600">Suggested reply. Edit before sending.</div><p className="mt-1">{catchup.suggestedReply}</p><Button size="sm" variant="quiet" className="mt-1" onClick={() => setText(catchup.suggestedReply)}>Use this reply</Button></div>}
            </div><button className="text-xs text-graphite-500 hover:underline" onClick={() => setCatchup(null)}>Hide</button></div></div>}
          <div className="scrollbar-thin flex-1 space-y-3 overflow-y-auto px-4 py-4" style={{ maxHeight: '55vh' }}>
            {conv.messages.map((m: any) => m.kind === 'SYSTEM'
              ? <div key={m.id} className="text-center text-xs text-graphite-500">{m.body}</div>
              : (() => { const mine = m.sender_id === me; return (
                <div key={m.id} className={`flex gap-2 ${mine ? 'flex-row-reverse' : ''}`}>
                  <Avatar name={m.sender_name} size={30} seed={m.sender_id} />
                  <div className={`max-w-[80%] rounded-lg px-3 py-2 text-sm ${mine ? 'bg-federal-900 text-white' : 'bg-limestone-100 text-graphite-900'}`}>
                    <div className={`mb-0.5 text-2xs ${mine ? 'text-white/70' : 'text-graphite-500'}`}>{mine ? 'You' : m.sender_name}{!mine && m.sender_persona ? ` · ${KIND_LABEL[m.sender_persona] ?? ''}` : ''} · {ago(m.created_at)}</div>
                    <p className="whitespace-pre-wrap break-words">{m.body}</p></div></div>); })())}
            <div ref={end} />
          </div>
          {can('CONVERSATION:CREATE') && <form onSubmit={(e) => { e.preventDefault(); if (text.trim()) send.mutate(undefined); }} className="flex items-end gap-2 border-t border-limestone-200 p-3">
            <Textarea rows={2} className="flex-1" value={text} onChange={(e) => setText(e.target.value)} placeholder="Write a message" aria-label="Message"
              onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (text.trim()) send.mutate(undefined); } }} />
            <Button type="submit" variant="primary" disabled={!text.trim()} aria-label="Send message">{send.isPending ? <Spinner /> : <Send className="h-4 w-4" />}</Button>
          </form>}
        </>)}
      </section>
    </div>
    <NewConversation open={newOpen} onClose={closeNew} preset={preset} onCreated={(id) => { setNewOpen(false); nav(`/app/messages?c=${id}`, { replace: true }); refetchList(); }} />
  </>);
}
