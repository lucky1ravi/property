import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Sparkles, ShieldCheck, TriangleAlert, Camera, FileSearch, ScrollText, Users, Receipt, Scale } from 'lucide-react';
import { PageHeader, Panel, Button, Badge, Field, Textarea, Select, Tabs, Skeleton, EmptyState, KeyValues, useApi, useAction, api, fmt } from '@propertyos/ui';

type Tool = 'billing' | 'outlook' | 'lease' | 'statement' | 'photo' | 'fair';

const TOOLS: { key: Tool; label: string; blurb: string; icon: typeof Sparkles; perm: string }[] = [
  { key: 'billing', label: 'Billing check', blurb: 'Finds duplicate invoices, rent that differs from the lease, and doubled expenses before month end.', icon: Receipt, perm: 'INVOICE:VIEW' },
  { key: 'outlook', label: 'Resident outlook', blurb: 'Shows who is most likely to pay late or move out, why, and a sensible next step.', icon: Users, perm: 'LEASE:VIEW' },
  { key: 'lease', label: 'Lease summary', blurb: 'Pulls key dates, amounts, duties and risks out of a lease. Every number is matched back to the lease.', icon: FileSearch, perm: 'LEASE:VIEW' },
  { key: 'statement', label: 'Owner statement', blurb: 'Writes the plain-English note at the top of an owner statement. Amounts are checked against the statement.', icon: ScrollText, perm: 'OWNER_STATEMENT:VIEW' },
  { key: 'photo', label: 'Repair photo', blurb: 'Reads a photo of a problem and suggests the trade, urgency and a cost range.', icon: Camera, perm: 'WORK_ORDER:VIEW' },
  { key: 'fair', label: 'Fair housing check', blurb: 'Flags wording in an ad or message that could break fair housing rules, with a safer rewrite.', icon: Scale, perm: 'LISTING:VIEW' },
];

function Source({ r }: { r: any }) {
  if (!r) return null;
  const ai = String(r.source ?? '').includes('AI');
  return (
    <p className="mt-4 flex flex-wrap items-center gap-2 text-xs text-graphite-600">
      <Badge tone={ai ? 'brand' : 'neutral'}>{ai ? 'Written with AI' : 'Standard rules'}</Badge>
      {ai && r.model && <span>{r.model}</span>}
      {r.checks && <span className="flex items-center gap-1"><ShieldCheck className="h-3.5 w-3.5" />{r.checks}</span>}
      {r.note && <span>{r.note}</span>}
    </p>
  );
}

const tone = (s: string) => (s === 'HIGH' || s === 'EMERGENCY' ? 'crit' : s === 'MEDIUM' || s === 'HIGH' ? 'warn' : 'neutral') as 'crit' | 'warn' | 'neutral';

function Billing() {
  const run = useAction(() => api.get<any>('/api/ai/studio/anomalies'), { silent: true });
  const r = run.data;
  return (<>
    <Button variant="primary" icon={<Sparkles className="h-4 w-4" />} loading={run.isPending} onClick={() => run.mutate(undefined)}>{r ? 'Run again' : 'Check my billing'}</Button>
    {run.isPending && <Skeleton className="mt-4 h-32" />}
    {r && (<div className="mt-4">
      <p className="text-sm font-semibold text-graphite-900">{r.summary}</p>
      <p className="mt-1 text-xs text-graphite-600">Checked {r.scanned.invoices} invoices, {r.scanned.statements} owner statements and {r.scanned.leases} leases.</p>
      {r.findings.length === 0 ? <EmptyState title="Nothing unusual" body="No duplicate invoices, rent mismatches or doubled expenses were found." /> : (
        <ul className="mt-3 space-y-3">{r.findings.map((f: any, i: number) => (
          <li key={i} className="rounded border border-limestone-200 bg-white p-3">
            <div className="flex flex-wrap items-center gap-2"><Badge tone={tone(f.severity)}>{f.severity === 'HIGH' ? 'Check first' : 'Check'}</Badge><span className="text-sm font-semibold text-graphite-900">{f.title}</span></div>
            <p className="mt-1 text-sm text-graphite-700">{f.detail}</p>
            <div className="mt-2 flex flex-wrap gap-1">{f.evidence.map((e: string, k: number) => <Badge key={k}>{e}</Badge>)}</div>
            <p className="mt-2 text-sm text-graphite-800"><strong>Next:</strong> {f.suggestion} <Link className="font-semibold text-federal-800 underline" to={f.path}>Open</Link></p>
          </li>))}</ul>)}
      <Source r={r} />
    </div>)}
  </>);
}

function Outlook() {
  const { data, isLoading } = useApi<any>('/api/ai/studio/resident-outlook');
  if (isLoading) return <Skeleton className="h-48" />;
  if (!data?.residents?.length) return <EmptyState title="No one stands out" body="No active residents have late payments, open repairs or a lease ending soon." />;
  return (<>
    <ul className="space-y-3">{data.residents.map((r: any) => (
      <li key={r.leaseId} className="rounded border border-limestone-200 bg-white p-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span><span className="text-sm font-semibold text-graphite-900">{r.tenant}</span> <span className="text-sm text-graphite-600">· {r.unit}</span></span>
          <Badge tone={tone(r.level)}>{r.level === 'HIGH' ? 'Reach out now' : r.level === 'MEDIUM' ? 'Keep an eye on' : 'Low'}</Badge>
        </div>
        <div className="mt-2 grid grid-cols-2 gap-3 text-xs text-graphite-700">
          <Meter label="May pay late" v={r.latePaymentRisk} /><Meter label="May move out" v={r.moveOutRisk} />
        </div>
        <ul className="mt-2 list-disc pl-5 text-sm text-graphite-700">{r.reasons.map((x: string) => <li key={x}>{x}</li>)}</ul>
        <p className="mt-2 text-sm text-graphite-900"><strong>Next step:</strong> {r.action}</p>
      </li>))}</ul>
    <Source r={data} />
  </>);
}

function Meter({ label, v }: { label: string; v: number }) {
  return (<div><div className="flex justify-between"><span>{label}</span><span>{v}%</span></div>
    <div className="mt-1 h-1.5 rounded-full bg-limestone-200"><div className={`h-1.5 rounded-full ${v >= 60 ? 'bg-crit-500' : v >= 35 ? 'bg-warn-500' : 'bg-good-500'}`} style={{ width: `${v}%` }} /></div></div>);
}

function Lease() {
  const { data: leases } = useApi<any[]>('/api/leasing/leases');
  const [id, setId] = useState('');
  const run = useAction((lid: string) => api.post<any>(`/api/ai/studio/lease-abstract/${lid}`), { silent: true });
  const r = run.data;
  return (<>
    <div className="flex flex-wrap items-end gap-3">
      <Field label="Lease"><Select value={id} onChange={(e) => setId(e.target.value)}><option value="">Choose a lease</option>{(leases ?? []).map((l) => <option key={l.id} value={l.id}>{l.tenant_name} – {l.property_name} {l.unit_label}</option>)}</Select></Field>
      <Button variant="primary" icon={<Sparkles className="h-4 w-4" />} disabled={!id} loading={run.isPending} onClick={() => run.mutate(id)}>Summarise lease</Button>
    </div>
    {run.isPending && <Skeleton className="mt-4 h-40" />}
    {r && (<div className="mt-4 space-y-4">
      <p className="text-sm text-graphite-900">{r.summary}</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <div><h4 className="text-sm font-semibold text-graphite-900">Key dates</h4><ul className="mt-1 text-sm text-graphite-700">{(r.keyDates ?? []).map((d: any, i: number) => <li key={i}>{d.label}: <strong>{fmt.date(d.date)}</strong></li>)}</ul></div>
        <div><h4 className="text-sm font-semibold text-graphite-900">Money</h4><ul className="mt-1 text-sm text-graphite-700">{(r.money ?? []).map((d: any, i: number) => <li key={i}>{d.label}: <strong>{fmt.usd(d.amount)}</strong></li>)}</ul></div>
      </div>
      <div><h4 className="text-sm font-semibold text-graphite-900">Who has to do what</h4><ul className="mt-1 list-disc pl-5 text-sm text-graphite-700">{(r.obligations ?? []).map((o: any, i: number) => <li key={i}><strong>{o.party}:</strong> {o.text}</li>)}</ul></div>
      {(r.risks ?? []).length > 0 && <div><h4 className="flex items-center gap-1 text-sm font-semibold text-graphite-900"><TriangleAlert className="h-4 w-4" />Worth a look</h4><ul className="mt-1 space-y-1">{r.risks.map((x: any, i: number) => <li key={i} className="text-sm text-graphite-700"><Badge tone={tone(x.severity)}>{x.severity}</Badge> {x.text}</li>)}</ul></div>}
      {(r.missing ?? []).length > 0 && <div><h4 className="text-sm font-semibold text-graphite-900">Not covered in this lease</h4><div className="mt-1 flex flex-wrap gap-1">{r.missing.map((m: string) => <Badge key={m}>{m}</Badge>)}</div></div>}
      <Source r={r} />
    </div>)}
  </>);
}

function Statement() {
  const { data: sts } = useApi<any[]>('/api/billing/statements');
  const [id, setId] = useState('');
  const run = useAction((sid: string) => api.post<any>(`/api/ai/studio/statement-narrative/${sid}`), { silent: true });
  const r = run.data;
  return (<>
    <div className="flex flex-wrap items-end gap-3">
      <Field label="Owner statement"><Select value={id} onChange={(e) => setId(e.target.value)}><option value="">Choose a statement</option>{(sts ?? []).map((s) => <option key={s.id} value={s.id}>{s.owner_name} – {s.period}</option>)}</Select></Field>
      <Button variant="primary" icon={<Sparkles className="h-4 w-4" />} disabled={!id} loading={run.isPending} onClick={() => run.mutate(id)}>Write summary</Button>
    </div>
    {run.isPending && <Skeleton className="mt-4 h-32" />}
    {r && (<div className="mt-4 rounded border border-limestone-200 bg-white p-4">
      <h4 className="text-base font-bold text-graphite-900">{r.headline}</h4>
      <p className="mt-2 max-w-[70ch] text-sm leading-relaxed text-graphite-800">{r.narrative}</p>
      {(r.watch ?? []).length > 0 && <ul className="mt-3 list-disc pl-5 text-sm text-graphite-700">{r.watch.map((w: string) => <li key={w}>{w}</li>)}</ul>}
      <Button variant="secondary" className="mt-3" onClick={() => navigator.clipboard?.writeText(`${r.headline}\n\n${r.narrative}`)}>Copy text</Button>
      <Source r={r} />
    </div>)}
  </>);
}

function Photo() {
  const [file, setFile] = useState<File | null>(null);
  const [note, setNote] = useState('');
  const run = useAction(() => { const f = new FormData(); f.append('file', file!); if (note) f.append('note', note); return api.upload<any>('/api/ai/studio/photo-triage', f); }, { silent: true });
  const r = run.data;
  return (<>
    <div className="grid gap-3 sm:grid-cols-2">
      <Field label="Photo of the problem" hint="JPG, PNG or WebP, up to 8 MB"><input type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="block w-full text-sm" /></Field>
      <Field label="What the resident said (optional)"><Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Started after last night’s storm" /></Field>
    </div>
    <Button className="mt-3" variant="primary" icon={<Camera className="h-4 w-4" />} disabled={!file} loading={run.isPending} onClick={() => run.mutate(undefined)}>Check photo</Button>
    {run.isPending && <Skeleton className="mt-4 h-32" />}
    {r && (<div className="mt-4 rounded border border-limestone-200 bg-white p-4">
      <div className="flex flex-wrap items-center gap-2"><Badge tone="brand">{fmt.title(r.category)}</Badge><Badge tone={tone(r.priority)}>{fmt.title(r.priority)} priority</Badge>{r.safetyRisk && <Badge tone="crit">Safety risk</Badge>}<Badge>{Math.round(r.confidence * 100)}% sure</Badge></div>
      <p className="mt-2 text-sm text-graphite-900">{r.summary}</p>
      <KeyValues cols={2} items={[{ label: 'Likely cause', value: r.likelyCause || '—' }, { label: 'Suggested action', value: r.suggestedAction || '—' }, { label: 'Typical cost', value: `${fmt.usd(r.estimateLow)} – ${fmt.usd(r.estimateHigh)}` }, { label: 'Tell the resident', value: r.tenantAdvice || '—' }]} />
      <Source r={r} />
    </div>)}
  </>);
}

function Fair() {
  const [text, setText] = useState('Spacious 2 bedroom, perfect for young professionals. Quiet building, no kids please. Walking distance to church.');
  const run = useAction(() => api.post<any>('/api/ai/studio/fair-housing', { text }), { silent: true });
  const r = run.data;
  return (<>
    <Field label="Ad or message text"><Textarea rows={4} value={text} onChange={(e) => setText(e.target.value)} /></Field>
    <Button className="mt-3" variant="primary" icon={<Scale className="h-4 w-4" />} disabled={!text.trim()} loading={run.isPending} onClick={() => run.mutate(undefined)}>Check wording</Button>
    {r && (<div className="mt-4 space-y-3">
      <Badge tone={r.verdict === 'CLEAR' ? 'good' : 'warn'}>{r.verdict === 'CLEAR' ? 'No problems found' : `${r.issues.length} thing${r.issues.length === 1 ? '' : 's'} to fix`}</Badge>
      {r.issues.map((i: any, k: number) => <p key={k} className="text-sm text-graphite-800"><strong>“{i.phrase}”</strong> {i.protectedClass && <span className="text-graphite-600">({i.protectedClass})</span>} — {i.why}</p>)}
      {r.rewrite && <div className="rounded border border-limestone-200 bg-white p-3"><h4 className="text-sm font-semibold text-graphite-900">Safer version</h4><p className="mt-1 text-sm text-graphite-800">{r.rewrite}</p><Button variant="secondary" className="mt-2" onClick={() => navigator.clipboard?.writeText(r.rewrite)}>Copy</Button></div>}
      <Source r={r} />
    </div>)}
  </>);
}

export function AiTools() {
  const [tool, setTool] = useState<Tool>('billing');
  const t = TOOLS.find((x) => x.key === tool)!;
  return (<>
    <PageHeader title="AI tools" description="Focused helpers for the jobs that eat the most time. Each one shows what it checked, and a person always makes the final call." />
    <Tabs tabs={TOOLS.map((x) => ({ key: x.key, label: x.label }))} value={tool} onChange={setTool} className="mb-4" />
    <Panel title={t.label} description={t.blurb}>
      {tool === 'billing' && <Billing />}{tool === 'outlook' && <Outlook />}{tool === 'lease' && <Lease />}
      {tool === 'statement' && <Statement />}{tool === 'photo' && <Photo />}{tool === 'fair' && <Fair />}
    </Panel>
  </>);
}
