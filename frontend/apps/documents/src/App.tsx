import { useMemo, useState } from 'react';
import { Link, Route, Routes, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Archive, ArchiveRestore, CheckCircle2, Download, FileText, Lock, PenLine, Send, ShieldCheck, Trash2, UploadCloud, XCircle } from 'lucide-react';
import {
  MicroApp, Tabs, PageHeader, Panel, Stat, StatStrip, Button, Badge, StatusPill, DataTable, type Column, Modal, Field, Input, Select, Textarea, Toggle, KeyValues,
  EmptyState, Skeleton, ConfirmDialog, Timeline, useApi, useAction, api, fmt, useSession,
  DocIcon, DocChips, AuthImage, MediaStatus, PhotoConfidence, PhotoUploadButton, MEDIA_INVALIDATE, type MediaRow, DocumentViewer, UploadDocumentDialog, downloadDocument, useDocMeta, fmtBytes, type DocRow,
} from '@propertyos/ui';

const BASE = '/app/documents';
const inv = ['/api/documents'];

// Library tabs group the document categories the way people think about them.
const GROUPS: { key: string; label: string; categories?: string[] }[] = [
  { key: 'all', label: 'All files' },
  { key: 'applicant', label: 'Applicant files', categories: ['APPLICATION', 'IDENTITY'] },
  { key: 'bills', label: 'Bills and invoices', categories: ['BILL', 'STATEMENT'] },
  { key: 'leases', label: 'Leases and notices', categories: ['LEASE', 'NOTICE'] },
  { key: 'esign', label: 'E-signed' },
  { key: 'compliance', label: 'Insurance, tax, contracts', categories: ['INSURANCE', 'TAX', 'CONTRACT'] },
  { key: 'property', label: 'Property and work orders', categories: ['PROPERTY', 'WORK_ORDER', 'OTHER'] },
];

const sigTone = (s: string) => (s === 'SIGNED' || s === 'COMPLETED' ? 'good' : s === 'DECLINED' || s === 'CANCELLED' || s === 'EXPIRED' ? 'crit' : 'info');
const sigLabel: Record<string, string> = { PENDING: 'Waiting', PARTIAL: 'Partly signed', SIGNED: 'Signed', COMPLETED: 'Completed', DECLINED: 'Declined', CANCELLED: 'Cancelled', EXPIRED: 'Expired', NONE: '' };

// ------------------------------------------------------------------ Library

function Library() {
  const nav = useNavigate();
  const [sp, setSp] = useSearchParams();
  const { can } = useSession();
  const { data: meta } = useDocMeta();
  const { data: sum } = useApi<any>('/api/documents/summary');
  const tab = sp.get('tab') ?? 'all';
  const staff = !!meta?.staff;
  const status = tab === 'trash' ? 'DELETED' : tab === 'archived' ? 'ARCHIVED' : 'ACTIVE';
  const extra = new URLSearchParams();
  for (const k of ['entityType', 'entityId', 'propertyId']) if (sp.get(k)) extra.set(k, sp.get(k)!);
  if (tab === 'review') extra.set('review', 'PENDING');
  if (tab === 'expiring') extra.set('expiring', 'true');
  const { data, isLoading } = useApi<{ items: DocRow[] }>(`/api/documents?status=${status}${extra.toString() ? `&${extra}` : ''}`);
  const [upload, setUpload] = useState(false);
  const [mode, setMode] = useState<'files' | 'gaps'>('files');

  const group = GROUPS.find((g) => g.key === tab);
  const rows = useMemo(() => {
    const all = data?.items ?? [];
    if (!group || group.key === 'all') return all;
    if (group.key === 'esign') return all.filter((d) => d.signature_status && d.signature_status !== 'NONE');
    return all.filter((d) => group.categories?.includes(d.category));
  }, [data, group]);
  const count = (g: (typeof GROUPS)[number]) => {
    const all = data?.items ?? [];
    return g.key === 'all' ? all.length : g.key === 'esign' ? all.filter((d) => d.signature_status && d.signature_status !== 'NONE').length : all.filter((d) => g.categories?.includes(d.category)).length;
  };

  const tabs = [
    ...GROUPS.map((g) => ({ key: g.key, label: g.label, count: status === 'ACTIVE' && !['review', 'expiring'].includes(tab) ? count(g) : undefined })),
    ...(staff ? [{ key: 'review', label: 'To review', count: sum?.pendingReview || undefined }] : []),
    { key: 'expiring', label: 'Expiring', count: sum?.expiringSoon || undefined },
    { key: 'archived', label: 'Archived' },
    ...(sum?.inTrash !== undefined && can('DOCUMENT:DELETE') && staff ? [{ key: 'trash', label: 'Trash', count: sum.inTrash || undefined }] : []),
  ];

  const cols: Column<DocRow>[] = [
    { key: 'title', header: 'Document', value: (d) => d.title, render: (d) => (
      <div className="flex min-w-0 items-center gap-3"><DocIcon mime={d.mime_type} />
        <div className="min-w-0"><div className="truncate font-semibold text-graphite-900">{d.title}</div>
          <div className="truncate text-xs text-graphite-500">{d.file_name}{d.current_version > 1 ? ` · v${d.current_version}` : ''}</div></div></div>) },
    { key: 'category', header: 'Category', value: (d) => d.category_label, filter: 'select' },
    { key: 'type', header: 'Type', value: (d) => d.doc_type ?? '', hideOnMobile: true },
    { key: 'for', header: 'Belongs to', value: (d) => d.entity_label ?? d.property_name ?? '', render: (d) => (
      <div className="min-w-0"><div className="truncate text-sm">{d.entity_label ?? '—'}</div>{d.property_name && d.property_name !== d.entity_label && <div className="truncate text-xs text-graphite-500">{d.property_name}</div>}</div>) },
    { key: 'state', header: 'Status', value: (d) => (d.signature_status !== 'NONE' ? d.signature_status : d.review_status), render: (d) => <div className="flex flex-wrap gap-1"><DocChips d={d} /></div>, sortable: false },
    { key: 'by', header: 'Added by', value: (d) => d.uploaded_by_name ?? 'PropertyOS', hideOnMobile: true },
    { key: 'updated', header: 'Updated', value: (d) => d.updated_at, render: (d) => fmt.shortDate(d.updated_at) },
    { key: 'size', header: 'Size', value: (d) => d.size_bytes, render: (d) => fmtBytes(d.size_bytes), align: 'right', hideOnMobile: true, filter: false },
  ];

  const canUpload = can('DOCUMENT:CREATE') && (meta?.uploadCategories?.length ?? 0) > 0;
  return (<>
    <PageHeader title="Files and documents" description={staff ? 'Applicant files, bills, leases, e-signed agreements and everything else, filed against the record it belongs to.' : 'Your lease, bills, statements and anything shared with you or sent by you.'}
      actions={<div className="flex gap-2">
        {can('ESIGN:VIEW') && meta?.esignEnabled && <Button variant="secondary" icon={<PenLine className="h-4 w-4" />} onClick={() => nav(`${BASE}/signatures`)}>E-signatures{sum?.awaitingMySignature ? ` (${sum.awaitingMySignature})` : ''}</Button>}
        {canUpload && <Button icon={<UploadCloud className="h-4 w-4" />} onClick={() => setUpload(true)}>Upload</Button>}
      </div>} />
    {sum && <StatStrip className="mb-6">
      <Stat label="Files on record" value={sum.active} sub={`${fmtBytes(sum.storageBytes)} stored`} emphasis />
      <Stat label="Awaiting my signature" value={sum.awaitingMySignature} onClick={() => nav(`${BASE}/signatures`)} />
      {staff && <Stat label="Out for signature" value={sum.outForSignature} onClick={() => nav(`${BASE}/signatures`)} />}
      {staff && <Stat label="To review" value={sum.pendingReview} sub="Uploaded by applicants and residents" onClick={() => setSp({ tab: 'review' })} />}
      <Stat label="Expiring in 30 days" value={sum.expiringSoon} onClick={() => setSp({ tab: 'expiring' })} />
    </StatStrip>}
    {staff && <div className="mb-3 flex gap-2" role="group" aria-label="View">
      <Button size="sm" variant={mode === 'files' ? 'primary' : 'secondary'} onClick={() => setMode('files')}>Files</Button>
      <Button size="sm" variant={mode === 'gaps' ? 'primary' : 'secondary'} onClick={() => setMode('gaps')}>Missing documents</Button>
    </div>}
    {mode === 'gaps' && staff ? <Gaps /> : <>
      <Tabs tabs={tabs} value={tab} onChange={(k) => setSp(k === 'all' ? {} : { tab: k })} className="mb-4" />
      <DataTable rows={rows} columns={cols} rowKey={(d) => d.id} loading={isLoading} onRowClick={(d) => nav(`${BASE}/${d.id}`)} exportName="documents"
        searchPlaceholder="Search by title, type, file name, property or tag"
        empty={{ title: tab === 'trash' ? 'Trash is empty' : 'No documents here yet', body: canUpload ? 'Upload a file, or open an application, lease or work order to add it there.' : 'Documents shared with you will appear here.' }} />
    </>}
    <UploadDocumentDialog open={upload} onClose={() => setUpload(false)} onUploaded={(d) => nav(`${BASE}/${d.id}`)} />
  </>);
}

function Gaps() {
  const { data, isLoading } = useApi<any[]>('/api/documents/requirements/gaps');
  const nav = useNavigate();
  if (isLoading) return <Skeleton className="h-40" />;
  if (!data?.length) return <Panel title="Required documents"><EmptyState icon={<CheckCircle2 className="h-8 w-8" />} title="Nothing missing" body="Every application, lease, vendor and owner has the documents your checklist asks for." /></Panel>;
  return (
    <Panel title="Required documents" description="Records that are missing a required file, or whose file has expired or was rejected. Set the checklist in Configuration.">
      <DataTable rows={data} rowKey={(r) => `${r.entityType}-${r.entityId}`} dense exportName="missing-documents"
        columns={[
          { key: 't', header: 'Record', value: (r) => r.entityType, render: (r) => <Badge>{({ APPLICATION: 'Application', LEASE: 'Lease', VENDOR: 'Vendor', OWNER: 'Owner' } as any)[r.entityType]}</Badge>, filter: 'select' },
          { key: 'l', header: 'Name', value: (r) => r.label, render: (r) => <span className="font-semibold">{r.label}</span> },
          { key: 's', header: 'Status', value: (r) => r.status ?? '', render: (r) => (r.status ? <StatusPill status={r.status} /> : null) },
          { key: 'i', header: 'What is needed', value: (r) => r.items.map((i: any) => i.type).join(', '), render: (r) => (
            <div className="flex flex-wrap gap-1">{r.items.map((i: any) => <Badge key={i.type} tone={i.state === 'MISSING' ? 'crit' : 'warn'}>{i.type}: {String(i.state).toLowerCase().replace('_', ' ')}</Badge>)}</div>) },
        ]}
        onRowClick={(r) => nav(`${r.entityType === 'APPLICATION' || r.entityType === 'LEASE' ? '/app' : '/app'}${r.link}`)} />
    </Panel>
  );
}

// ------------------------------------------------------------------ Detail

function Detail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { can } = useSession();
  const { data: d, isLoading, error } = useApi<any>(id ? `/api/documents/${id}` : null);
  const [version, setVersion] = useState<number | undefined>();
  const [newVer, setNewVer] = useState(false);
  const [sign, setSign] = useState(false);
  const [edit, setEdit] = useState(false);
  const [confirm, setConfirm] = useState<null | 'ARCHIVE' | 'DELETE' | 'PURGE' | 'HOLD'>(null);
  const act = useAction((a: string) => api.post(`/api/documents/${id}/actions/${a}`, {}), { success: (r: any) => (r.status === 'ACTIVE' ? 'Restored' : r.status === 'ARCHIVED' ? 'Archived' : 'Moved to trash'), invalidate: inv });
  const review = useAction((v: { decision: string; note?: string }) => api.post(`/api/documents/${id}/review`, v), { success: 'Review saved', invalidate: inv });
  const [rejecting, setRejecting] = useState(false);

  if (isLoading) return <Skeleton className="h-64" />;
  if (error || !d) return <EmptyState icon={<FileText className="h-8 w-8" />} title="Document not found" body="It may have been removed, or it isn’t shared with you." action={<Button onClick={() => nav(BASE)}>Back to files</Button>} />;
  const c = d.can ?? {};
  const open = (d.signatureRequests ?? []).filter((r: any) => r.status === 'PENDING');
  return (<>
    <PageHeader back={<Link to={BASE} className="text-sm font-semibold text-federal-900 hover:underline">All files</Link>}
      title={d.title} meta={<div className="flex flex-wrap items-center gap-2"><Badge>{d.category_label}</Badge>{d.doc_type && <span className="text-sm text-graphite-600">{d.doc_type}</span>}<DocChips d={d} /></div>}
      actions={<div className="flex flex-wrap gap-2">
        {!d.restricted && <Button variant="secondary" icon={<Download className="h-4 w-4" />} onClick={() => downloadDocument(d.id, version)}>Download</Button>}
        {c.requestSignature && !d.restricted && <Button icon={<PenLine className="h-4 w-4" />} onClick={() => setSign(true)}>Request signature</Button>}
        {c.newVersion && !d.restricted && d.source !== 'GENERATED' && <Button variant="secondary" icon={<UploadCloud className="h-4 w-4" />} onClick={() => setNewVer(true)}>Upload new version</Button>}
      </div>} />
    {d.review_status === 'PENDING' && c.review && (
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-md border border-warn-600/30 bg-warn-50 px-4 py-3">
        <p className="text-sm text-warn-900"><strong>{d.uploaded_by_name ?? 'Someone'}</strong> sent this file. Check it, then accept it or ask for a new one.</p>
        <div className="flex gap-2"><Button size="sm" variant="secondary" icon={<XCircle className="h-4 w-4" />} onClick={() => setRejecting(true)}>Ask for a new file</Button>
          <Button size="sm" icon={<CheckCircle2 className="h-4 w-4" />} loading={review.isPending} onClick={() => review.mutate({ decision: 'ACCEPTED' })}>Accept</Button></div>
      </div>)}
    {d.review_status === 'REJECTED' && <div className="mb-4 rounded-md border border-crit-600/30 bg-crit-50 px-4 py-3 text-sm text-crit-700">This file was sent back{d.review_note ? `: ${d.review_note}` : '.'} A new file is needed.</div>}
    {open.length > 0 && <div className="mb-4 rounded-md border border-slateblue-600/25 bg-slateblue-50 px-4 py-3 text-sm text-slateblue-800">Out for signature. See who has signed below.</div>}

    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
      <div className="space-y-6">
        <Panel title="Preview" actions={d.versions?.length > 1 ? (
          <Select value={version ?? ''} onChange={(e) => setVersion(e.target.value ? Number(e.target.value) : undefined)} aria-label="Version">
            <option value="">Current version (v{d.current_version})</option>
            {d.versions.filter((v: any) => v.version !== d.current_version).map((v: any) => <option key={v.version} value={v.version}>Version {v.version}, {fmt.shortDate(v.created_at)}</option>)}
          </Select>) : undefined}>
          <DocumentViewer doc={d} version={version} />
        </Panel>
        {(d.signatureRequests ?? []).length > 0 && <Panel title="Signatures">
          <div className="space-y-5">{d.signatureRequests.map((r: any) => (
            <div key={r.id}>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <div><p className="text-sm font-bold text-graphite-900">{r.title ?? 'Signature request'}</p>
                  <p className="text-xs text-graphite-500">Sent by {r.created_by_name}, {fmt.shortDate(r.created_at)}{r.due_on ? ` · due ${fmt.shortDate(r.due_on)}` : ''}</p></div>
                <div className="flex items-center gap-2"><StatusPill label={sigLabel[r.status] ?? r.status} tone={sigTone(r.status) as any} />
                  <Link className="text-sm font-semibold text-federal-900 hover:underline" to={`${BASE}/signatures/${r.id}`}>Open</Link></div>
              </div>
              <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">{r.signers.map((s: any) => (
                <li key={s.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                  <span><strong>{s.name}</strong> <span className="text-graphite-500">{s.role_label}</span></span>
                  <span className="text-xs text-graphite-600">{s.status === 'SIGNED' ? `Signed ${fmt.dateTime(s.signed_at)}` : s.status === 'DECLINED' ? `Declined${s.decline_reason ? `: ${s.decline_reason}` : ''}` : s.viewed_at ? 'Opened, not signed' : 'Not opened yet'}</span>
                </li>))}</ul>
              {r.certificate_id && <button type="button" onClick={() => downloadDocument(r.certificate_id)} className="mt-2 inline-flex items-center gap-1.5 text-sm font-semibold text-federal-900 hover:underline"><ShieldCheck className="h-4 w-4" />Certificate of completion</button>}
            </div>))}</div>
        </Panel>}
      </div>

      <div className="space-y-6">
        <Panel title="Details" actions={c.edit ? <Button size="sm" variant="secondary" onClick={() => setEdit(true)}>Edit</Button> : undefined}>
          <KeyValues cols={1} items={[
            { label: 'Belongs to', value: d.entity_label ?? '—' }, { label: 'Property', value: d.property_name ?? '—' },
            { label: 'Added by', value: `${d.uploaded_by_name ?? 'PropertyOS'}, ${fmt.shortDate(d.created_at)}` },
            { label: 'File', value: `${d.file_name} · ${fmtBytes(d.size_bytes)}` },
            { label: 'Expires', value: d.expires_on ? fmt.shortDate(d.expires_on) : 'No expiry' },
            { label: 'Keep until', value: d.retain_until ? fmt.shortDate(d.retain_until) : '—' },
            { label: 'Tags', value: d.tags?.length ? d.tags.join(', ') : '—' },
          ]} />
          {d.description && <p className="mt-3 text-sm text-graphite-700">{d.description}</p>}
          {d.sha256 && <p className="mt-3 break-all text-xs text-graphite-400" title="SHA-256 fingerprint, used to prove the file hasn’t changed">Fingerprint {d.sha256.slice(0, 24)}…</p>}
        </Panel>
        {(d.can?.edit || d.share_owner || d.share_tenant || d.share_vendor) && <Panel title="Who can see this" description="Staff on this property always can. Others only when shared.">
          <ul className="space-y-1.5 text-sm">
            {[['Owner', d.share_owner], ['Resident', d.share_tenant], ['Vendor', d.share_vendor]].map(([k, v]) => (
              <li key={k as string} className="flex items-center justify-between"><span>{k}</span>{v ? <Badge tone="good">Shared</Badge> : <span className="inline-flex items-center gap-1 text-xs text-graphite-500"><Lock className="h-3 w-3" />Not shared</span>}</li>))}
          </ul>
          {d.sensitive && <p className="mt-3 text-xs text-graphite-500">Sensitive document: only roles with access to sensitive documents can open it.</p>}
        </Panel>}
        {d.versions?.length > 1 && <Panel title="Versions"><ul className="space-y-2 text-sm">{d.versions.map((v: any) => (
          <li key={v.version} className="flex items-start justify-between gap-2"><div><strong>v{v.version}</strong> <span className="text-graphite-500">{v.created_by_name}, {fmt.shortDate(v.created_at)}</span>{v.note && <div className="text-xs text-graphite-600">{v.note}</div>}</div>
            <button type="button" className="text-xs font-semibold text-federal-900 hover:underline" onClick={() => downloadDocument(d.id, v.version)}>Download</button></li>))}</ul></Panel>}
        {(c.delete || c.configure || c.edit) && <Panel title="Manage">
          <div className="flex flex-col gap-2">
            {d.status === 'ACTIVE' && c.edit && <Button variant="secondary" icon={<Archive className="h-4 w-4" />} onClick={() => act.mutate('ARCHIVE')}>Archive</Button>}
            {d.status !== 'ACTIVE' && <Button variant="secondary" icon={<ArchiveRestore className="h-4 w-4" />} onClick={() => act.mutate('RESTORE')}>Restore</Button>}
            {c.configure && <Button variant="secondary" icon={<Lock className="h-4 w-4" />} onClick={() => setConfirm('HOLD')}>{d.legal_hold ? 'Release legal hold' : 'Place legal hold'}</Button>}
            {d.status !== 'DELETED' && c.delete && !d.legal_hold && <Button variant="danger" icon={<Trash2 className="h-4 w-4" />} onClick={() => setConfirm('DELETE')}>Move to trash</Button>}
            {d.status === 'DELETED' && c.configure && !d.legal_hold && <Button variant="danger" icon={<Trash2 className="h-4 w-4" />} onClick={() => setConfirm('PURGE')}>Delete permanently</Button>}
          </div>
        </Panel>}
        <Panel title="History"><Timeline items={d.history} empty="No activity yet" /></Panel>
      </div>
    </div>

    <UploadDocumentDialog open={newVer} onClose={() => setNewVer(false)} newVersionOf={{ id: d.id, title: d.title }} />
    <RequestSignature open={sign} onClose={() => setSign(false)} doc={d} />
    <EditDocument open={edit} onClose={() => setEdit(false)} doc={d} />
    <ConfirmDialog open={rejecting} title="Ask for a new file" confirmLabel="Send back" requireReason reasonLabel="What’s wrong with it?" onClose={() => setRejecting(false)}
      body="The person who sent it will be told to upload a replacement." loading={review.isPending}
      onConfirm={(note) => review.mutate({ decision: 'REJECTED', note }, { onSuccess: () => setRejecting(false) })} />
    <ConfirmDialog open={confirm === 'DELETE'} title="Move to trash?" confirmLabel="Move to trash" tone="danger" requireReason onClose={() => setConfirm(null)}
      body="It leaves the library now and is deleted for good after the trash period, unless the retention rule says to keep it longer." onConfirm={(r) => { api.post(`/api/documents/${id}/actions/delete`, { reason: r }).then(() => { nav(BASE); }); }} />
    <ConfirmDialog open={confirm === 'PURGE'} title="Delete permanently?" confirmLabel="Delete permanently" tone="danger" requireReason onClose={() => setConfirm(null)}
      body="This removes the file and every version. It can’t be undone." onConfirm={(r) => { api.post(`/api/documents/${id}/purge`, { reason: r }).then(() => nav(BASE)); }} />
    <ConfirmDialog open={confirm === 'HOLD'} title={d.legal_hold ? 'Release legal hold?' : 'Place a legal hold?'} confirmLabel={d.legal_hold ? 'Release hold' : 'Place hold'} requireReason onClose={() => setConfirm(null)}
      body={d.legal_hold ? 'The normal retention and deletion rules apply again.' : 'While on hold the document can’t be deleted or purged, whatever the retention rule says.'}
      onConfirm={(r) => { api.post(`/api/documents/${id}/legal-hold`, { on: !d.legal_hold, reason: r }).then(() => { setConfirm(null); location.reload(); }); }} />
  </>);
}

function EditDocument({ open, onClose, doc }: { open: boolean; onClose: () => void; doc: any }) {
  const { data: meta } = useDocMeta();
  const [f, setF] = useState<any>(null);
  const cur = f ?? { title: doc.title, docType: doc.doc_type ?? '', description: doc.description ?? '', expiresOn: doc.expires_on?.slice(0, 10) ?? '', tags: (doc.tags ?? []).join(', '), shareOwner: doc.share_owner, shareTenant: doc.share_tenant, shareVendor: doc.share_vendor };
  const set = (k: string, v: any) => setF({ ...cur, [k]: v });
  const save = useAction(() => api.put(`/api/documents/${doc.id}`, { ...cur, expiresOn: cur.expiresOn || null, tags: cur.tags.split(',').map((t: string) => t.trim()).filter(Boolean) }), { success: 'Saved', invalidate: inv, onSuccess: () => { setF(null); onClose(); } });
  const types = meta?.categories.find((c) => c.key === doc.category)?.types ?? [];
  return (
    <Modal open={open} onClose={() => { setF(null); onClose(); }} title="Edit document" footer={<><Button variant="quiet" onClick={() => { setF(null); onClose(); }}>Cancel</Button><Button loading={save.isPending} onClick={() => save.mutate(undefined)}>Save changes</Button></>}>
      <div className="space-y-4">
        <Field label="Title"><Input value={cur.title} onChange={(e) => set('title', e.target.value)} /></Field>
        {types.length > 0 && <Field label="Type"><Select value={cur.docType} onChange={(e) => set('docType', e.target.value)}><option value="">Not set</option>{types.map((t) => <option key={t}>{t}</option>)}</Select></Field>}
        <Field label="Expires on" hint="Reminders go out ahead of this date."><Input type="date" value={cur.expiresOn} onChange={(e) => set('expiresOn', e.target.value)} /></Field>
        <Field label="Description"><Textarea rows={2} value={cur.description} onChange={(e) => set('description', e.target.value)} /></Field>
        <Field label="Tags" hint="Separate with commas."><Input value={cur.tags} onChange={(e) => set('tags', e.target.value)} /></Field>
        {meta?.staff && <div className="space-y-2 rounded border border-limestone-200 p-3">
          <p className="text-sm font-semibold text-graphite-800">Share with</p>
          <Toggle checked={cur.shareOwner} onChange={(v) => set('shareOwner', v)} label="The owner" />
          <Toggle checked={cur.shareTenant} onChange={(v) => set('shareTenant', v)} label="The resident" />
          <Toggle checked={cur.shareVendor} onChange={(v) => set('shareVendor', v)} label="The vendor" />
        </div>}
      </div>
    </Modal>
  );
}

function RequestSignature({ open, onClose, doc }: { open: boolean; onClose: () => void; doc: any }) {
  const nav = useNavigate();
  const { data: people } = useApi<any[]>(open ? `/api/documents/${doc.id}/signers` : null);
  const [sel, setSel] = useState<string[]>([]);
  const [msg, setMsg] = useState(''); const [due, setDue] = useState(''); const [seq, setSeq] = useState(false);
  const send = useAction(() => api.post<any>(`/api/documents/${doc.id}/signature-requests`, { signers: sel.map((userId) => ({ userId })), message: msg, dueOn: due || null, sequential: seq }),
    { success: 'Sent for signature', invalidate: inv, onSuccess: (r) => { onClose(); setSel([]); if (r?.id) nav(`${BASE}/signatures/${r.id}`); } });
  return (
    <Modal open={open} onClose={onClose} title="Request signatures" footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button icon={<Send className="h-4 w-4" />} disabled={!sel.length} loading={send.isPending} onClick={() => send.mutate(undefined)}>Send for signature</Button></>}>
      <div className="space-y-4">
        <div><p className="mb-1 text-[13px] font-semibold text-graphite-800">Who needs to sign</p>
          {!people ? <Skeleton className="h-24" /> : people.length === 0 ? <p className="text-sm text-graphite-500">No one to ask. Link this document to a lease, owner or vendor first.</p> : (
            <ul className="max-h-56 divide-y divide-limestone-200 overflow-auto rounded border border-limestone-200">{people.map((p) => (
              <li key={p.userId}><label className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm hover:bg-limestone-50">
                <input type="checkbox" checked={sel.includes(p.userId)} onChange={(e) => setSel(e.target.checked ? [...sel, p.userId] : sel.filter((x) => x !== p.userId))} />
                <span className="flex-1"><strong>{p.name}</strong> <span className="text-graphite-500">{p.title}</span></span><Badge>{p.relationship}</Badge></label></li>))}</ul>)}</div>
        <Field label="Message (optional)"><Textarea rows={2} value={msg} onChange={(e) => setMsg(e.target.value)} placeholder="Please review and sign the attached agreement." /></Field>
        <Field label="Due by" hint="Defaults to your e-signature expiry setting."><Input type="date" value={due} onChange={(e) => setDue(e.target.value)} /></Field>
        {sel.length > 1 && <Toggle checked={seq} onChange={setSeq} label="Sign in the order selected" />}
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ E-signatures

function Signatures() {
  const nav = useNavigate();
  const { can } = useSession();
  const { data: meta } = useDocMeta();
  const [mine, setMine] = useState(!meta?.staff);
  const [tab, setTab] = useState('PENDING');
  const { data, isLoading } = useApi<any[]>(`/api/documents/signatures?mine=${meta?.staff ? mine : true}`);
  const rows = (data ?? []).filter((r) => (tab === 'ALL' ? true : tab === 'DONE' ? r.status === 'COMPLETED' : tab === 'PENDING' ? r.status === 'PENDING' : !['PENDING', 'COMPLETED'].includes(r.status)));
  const waiting = (data ?? []).filter((r) => r.myTurn).length;
  return (<>
    <PageHeader back={<Link to={BASE} className="text-sm font-semibold text-federal-900 hover:underline">All files</Link>} title="E-signatures"
      description={meta?.staff ? 'Agreements out for signature and the ones you need to sign yourself.' : 'Agreements waiting for your signature, and the ones you have signed.'} />
    {waiting > 0 && <div className="mb-4 rounded-md border border-slateblue-600/25 bg-slateblue-50 px-4 py-3 text-sm text-slateblue-800"><strong>{waiting}</strong> {waiting === 1 ? 'document is' : 'documents are'} waiting for your signature.</div>}
    <Tabs tabs={[{ key: 'PENDING', label: 'Waiting' }, { key: 'DONE', label: 'Completed' }, { key: 'OTHER', label: 'Declined or cancelled' }, { key: 'ALL', label: 'All' }]} value={tab} onChange={setTab} className="mb-4" />
    {meta?.staff && <div className="mb-3 w-64"><Toggle checked={mine} onChange={setMine} label="Only ones I need to sign" /></div>}
    <DataTable rows={rows} loading={isLoading} rowKey={(r) => r.id} onRowClick={(r) => nav(`${BASE}/signatures/${r.id}`)} exportName="signature-requests"
      columns={[
        { key: 't', header: 'Document', value: (r) => r.document_title ?? r.title, render: (r) => <div><div className="font-semibold text-graphite-900">{r.document_title ?? r.title}</div><div className="text-xs text-graphite-500">{r.entity_label ?? r.property_name}</div></div> },
        { key: 'st', header: 'Status', value: (r) => r.status, render: (r) => <div className="flex gap-1"><StatusPill label={sigLabel[r.status] ?? r.status} tone={sigTone(r.status) as any} />{r.myTurn && <Badge tone="warn">Your turn</Badge>}</div> },
        { key: 'p', header: 'Signed', value: (r) => `${r.signed}/${r.signers.length}`, render: (r) => `${r.signed} of ${r.signers.length}`, filter: false },
        { key: 'by', header: 'Sent by', value: (r) => r.created_by_name, hideOnMobile: true },
        { key: 'due', header: 'Due', value: (r) => r.due_on ?? '', render: (r) => (r.due_on ? fmt.shortDate(r.due_on) : '—') },
        { key: 'at', header: 'Sent', value: (r) => r.created_at, render: (r) => fmt.shortDate(r.created_at), hideOnMobile: true },
      ]}
      empty={{ title: 'No signature requests', body: can('ESIGN:CREATE') ? 'Open a document and choose Request signature.' : 'Nothing is waiting for you.' }} />
  </>);
}

function SignaturePage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { data: r, isLoading } = useApi<any>(id ? `/api/documents/signatures/${id}` : null);
  const [name, setName] = useState(''); const [consent, setConsent] = useState(false);
  const [declining, setDeclining] = useState(false); const [cancelling, setCancelling] = useState(false);
  const sign = useAction(() => api.post(`/api/documents/signatures/${id}/sign`, { signature: name.trim(), consent }), { success: 'Signed. A copy is on file.', invalidate: inv });
  const decline = useAction((reason: string) => api.post(`/api/documents/signatures/${id}/decline`, { reason }), { success: 'Declined', invalidate: inv, onSuccess: () => setDeclining(false) });
  const cancel = useAction((reason: string) => api.post(`/api/documents/signatures/${id}/cancel`, { reason }), { success: 'Request cancelled', invalidate: inv, onSuccess: () => setCancelling(false) });
  const remind = useAction(() => api.post(`/api/documents/signatures/${id}/remind`, {}), { success: 'Reminder sent', invalidate: inv });
  if (isLoading) return <Skeleton className="h-64" />;
  if (!r) return <EmptyState icon={<PenLine className="h-8 w-8" />} title="Signature request not found" action={<Button onClick={() => nav(`${BASE}/signatures`)}>Back</Button>} />;
  const me = r.me;
  const mustWait = me && me.status === 'PENDING' && !r.myTurn && r.status === 'PENDING';
  return (<>
    <PageHeader back={<Link to={`${BASE}/signatures`} className="text-sm font-semibold text-federal-900 hover:underline">E-signatures</Link>} title={r.title ?? r.document?.title}
      meta={<div className="flex items-center gap-2"><StatusPill label={sigLabel[r.status] ?? r.status} tone={sigTone(r.status) as any} />{r.due_on && <span className="text-sm text-graphite-600">Due {fmt.shortDate(r.due_on)}</span>}</div>}
      actions={<div className="flex gap-2">
        <Button variant="secondary" icon={<FileText className="h-4 w-4" />} onClick={() => nav(`${BASE}/${r.document_id}`)}>Open document</Button>
        {r.canManage && r.status === 'PENDING' && <><Button variant="secondary" icon={<Send className="h-4 w-4" />} loading={remind.isPending} onClick={() => remind.mutate(undefined)}>Send reminder</Button>
          <Button variant="danger" onClick={() => setCancelling(true)}>Cancel request</Button></>}
      </div>} />
    {r.message && <p className="mb-4 max-w-[70ch] rounded border border-limestone-200 bg-white px-4 py-3 text-sm text-graphite-700">{r.message}</p>}
    {!r.unchanged && r.status === 'PENDING' && <div className="mb-4 rounded-md border border-crit-600/30 bg-crit-50 px-4 py-3 text-sm text-crit-700">The document changed after this request was sent, so it can’t be signed. Ask for a new request.</div>}
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_380px]">
      <Panel title="Read before you sign"><DocumentViewer doc={{ id: r.document.id, title: r.document.title, mime_type: r.document.mime_type, previewable: true, restricted: false }} /></Panel>
      <div className="space-y-6">
        {me && r.status === 'PENDING' && me.status === 'PENDING' && (
          <Panel title="Sign" description={mustWait ? 'Others sign first. You’ll be asked when it’s your turn.' : undefined}>
            {!mustWait && <div className="space-y-4">
              <Field label="Type your full legal name" hint="Typing your name is your signature."><Input value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" placeholder={me.name} /><button type="button" className="mt-1 text-xs font-semibold text-federal-800 underline" onClick={() => setName(me.name)}>Use my name</button></Field>
              {name.trim() && <p className="rounded border border-dashed border-limestone-300 bg-limestone-50 px-4 py-3 text-2xl italic text-graphite-900" style={{ fontFamily: 'Georgia, "Times New Roman", serif' }}>{name}</p>}
              <label className="flex items-start gap-2 text-sm text-graphite-700"><input type="checkbox" className="mt-1" checked={consent} onChange={(e) => setConsent(e.target.checked)} /><span>{r.consentText}</span></label>
              <div className="flex gap-2"><Button disabled={!name.trim() || !consent || !r.unchanged} loading={sign.isPending} icon={<PenLine className="h-4 w-4" />} onClick={() => sign.mutate(undefined)}>Sign document</Button>
                <Button variant="quiet" onClick={() => setDeclining(true)}>Decline</Button></div>
            </div>}
          </Panel>)}
        {me?.status === 'SIGNED' && <Panel title="You signed this"><p className="text-sm text-graphite-700">Signed {fmt.dateTime(me.signed_at)}.</p></Panel>}
        <Panel title="Signers"><ul className="space-y-3">{r.signers.map((s: any) => (
          <li key={s.id} className="flex items-start justify-between gap-2 text-sm"><div><strong>{s.name}</strong><div className="text-xs text-graphite-500">{s.role_label}</div></div>
            <div className="text-right text-xs">{s.status === 'SIGNED' ? <span className="font-semibold text-good-700">Signed {fmt.shortDate(s.signed_at)}</span> : s.status === 'DECLINED' ? <span className="font-semibold text-crit-700">Declined</span> : <span className="text-graphite-600">{s.viewed_at ? 'Opened' : 'Waiting'}</span>}</div></li>))}</ul>
          {r.certificate_id && <Button className="mt-4" variant="secondary" icon={<ShieldCheck className="h-4 w-4" />} onClick={() => downloadDocument(r.certificate_id)}>Download certificate</Button>}
        </Panel>
      </div>
    </div>
    <ConfirmDialog open={declining} title="Decline to sign?" confirmLabel="Decline" tone="danger" requireReason reasonLabel="Why are you declining?" loading={decline.isPending} onClose={() => setDeclining(false)} onConfirm={(x) => decline.mutate(x)} body="The sender is told and the request closes." />
    <ConfirmDialog open={cancelling} title="Cancel this request?" confirmLabel="Cancel request" tone="danger" requireReason loading={cancel.isPending} onClose={() => setCancelling(false)} onConfirm={(x) => cancel.mutate(x)} body="Signers will no longer be able to sign." />
  </>);
}

// ---------------------------------------------------------------- photos and AI review

function PhotoCard({ m, canReview, onApprove, onReject }: { m: MediaRow; canReview: boolean; onApprove: () => void; onReject: () => void }) {
  return (
    <li className="overflow-hidden rounded border border-limestone-200 bg-white">
      <AuthImage id={m.id} alt={m.file_name} className="h-48 w-full" />
      <div className="space-y-2 p-3">
        <div className="flex flex-wrap items-center justify-between gap-2"><span className="text-sm font-semibold text-graphite-900">{m.kind === 'PROFILE' ? `Profile photo: ${m.subject_label}` : m.subject_label}</span><MediaStatus m={m} /></div>
        <p className="text-xs text-graphite-600">{m.file_name} · uploaded by {m.uploaded_by_name} · {fmt.date(m.created_at)}</p>
        <PhotoConfidence confidence={m.ai_confidence} threshold={m.threshold_used} verdict={m.ai_verdict} />
        {m.flag_reason && m.status === 'PENDING_REVIEW' && <p className="text-sm font-medium text-warn-700">{m.flag_reason}</p>}
        <p className="text-sm text-graphite-700">{m.ai_reason}</p>
        {m.ai_quality != null && <p className="text-sm text-graphite-700">Listing quality <strong>{m.ai_quality}/100</strong>{m.ai_caption ? ` · “${m.ai_caption}”` : ''}{m.ai_quality_issues && m.ai_quality_issues.length > 0 ? ` · fix: ${m.ai_quality_issues.join(', ')}` : ''}</p>}
        {m.ai_labels && m.ai_labels.length > 0 && <div className="flex flex-wrap gap-1">{m.ai_labels.map((l) => <Badge key={l}>{l}</Badge>)}</div>}
        <p className="text-xs text-graphite-500">Checked by {m.ai_simulated ? 'the built-in demo checker (no image model connected)' : m.ai_provider}</p>
        {m.status !== 'PENDING_REVIEW' && (m.reviewer_name || m.decision_note) && <p className="text-xs text-graphite-600">{m.reviewer_name ? `${m.reviewer_name}: ` : ''}{m.decision_note}</p>}
        {canReview && <div className="flex gap-2 pt-1">
          {m.status !== 'APPROVED' && <Button variant="primary" icon={<CheckCircle2 className="h-4 w-4" />} onClick={onApprove}>Approve and publish</Button>}
          {m.status !== 'REJECTED' && <Button variant="secondary" icon={<XCircle className="h-4 w-4" />} onClick={onReject}>{m.status === 'APPROVED' ? 'Take down' : 'Reject'}</Button>}
        </div>}
      </div>
    </li>
  );
}

function Photos() {
  const { can } = useSession();
  const canReview = can('MEDIA:REVIEW');
  const [tab, setTab] = useState<'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | ''>(canReview ? 'PENDING_REVIEW' : '');
  const [kind, setKind] = useState<'PROPERTY' | 'PROFILE'>(can('PROPERTY:EDIT') || can('LISTING:EDIT') ? 'PROPERTY' : 'PROFILE');
  const [property, setProperty] = useState('');
  const [rejecting, setRejecting] = useState<MediaRow | null>(null);
  const { data: settings } = useApi<any>('/api/documents/media/settings');
  const { data, isLoading } = useApi<{ items: MediaRow[]; summary?: any }>(`/api/documents/media${tab ? `?status=${tab}` : ''}`);
  const canProperty = !!settings?.canUploadProperty;
  const { data: props } = useApi<any[]>(canProperty ? '/api/leasing/properties' : null);
  const decide = useAction((v: { id: string; decision: string; note?: string }) => api.post(`/api/documents/media/${v.id}/decision`, { decision: v.decision, note: v.note }),
    { success: 'Decision saved', invalidate: MEDIA_INVALIDATE.concat(['/api/leasing/properties']), onSuccess: () => setRejecting(null) });
  const s = data?.summary;
  const tabs = canReview
    ? [{ key: 'PENDING_REVIEW' as const, label: 'Needs review', count: s?.pending }, { key: 'APPROVED' as const, label: 'Published' }, { key: 'REJECTED' as const, label: 'Rejected' }, { key: '' as const, label: 'All' }]
    : [{ key: '' as const, label: 'My uploads' }];
  return (<>
    <PageHeader title="Photos and review" description="Every property and profile photo is checked by AI. Photos it is sure about are published; the rest wait here for a person." />
    {settings && <p className="mb-4 rounded border border-limestone-200 bg-limestone-50 px-3 py-2 text-sm text-graphite-700">
      Photos are published automatically when the AI is at least <strong>{settings.autoApproveConfidence}%</strong> confident they are safe{settings.autoRejectEnabled ? `, and rejected automatically at ${settings.autoRejectConfidence}% unsafe` : ''}. {can('SETTINGS:CONFIGURE') ? 'Change this in Settings, under Photo review.' : 'Your administrator sets this limit.'}
      {!settings.enabled && ' The automatic check is off, so every photo goes to a person.'} Checker: {settings.checker?.provider}{settings.checker?.provider === 'built-in simulator' ? ' (demo only, connect an image model for real checks)' : ''}.</p>}
    {canReview && s && <StatStrip className="mb-4">
      <Stat label="Waiting for review" value={s.pending} emphasis={s.pending > 0} />
      <Stat label="Published by the AI check" value={s.auto_approved} />
      <Stat label="Approved by people" value={s.person_approved} />
      <Stat label="Rejected" value={s.rejected} />
    </StatStrip>}
    <Panel title="Add a photo" className="mb-4">
      <div className="flex flex-wrap items-end gap-3">
        {canProperty && <Field label="Photo is for"><Select value={kind} onChange={(e) => setKind(e.target.value as any)}><option value="PROPERTY">A property</option><option value="PROFILE">My profile</option></Select></Field>}
        {kind === 'PROPERTY' && canProperty && <Field label="Property"><Select value={property} onChange={(e) => setProperty(e.target.value)}><option value="">Choose a property</option>{(props ?? []).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></Field>}
        {kind === 'PROPERTY' && !property ? <p className="pb-2 text-sm text-graphite-600">Choose a property first.</p> : <PhotoUploadButton key={kind + property} kind={kind} subjectId={kind === 'PROPERTY' ? property : undefined} label={kind === 'PROFILE' ? 'Upload profile photo' : 'Add property photo'} />}
      </div>
      <p className="mt-2 text-xs text-graphite-500">JPG, PNG, WebP or GIF, up to {settings?.maxImageMb ?? 8} MB. Photos that are explicit, violent, or not about the property are held back.</p>
    </Panel>
    <Tabs tabs={tabs} value={tab} onChange={setTab} className="mb-3" />
    {isLoading ? <Skeleton className="h-48" /> : !data?.items.length ? <EmptyState title={tab === 'PENDING_REVIEW' ? 'Nothing to review' : 'No photos yet'} body={tab === 'PENDING_REVIEW' ? 'Photos the AI is unsure about will appear here.' : 'Uploaded photos will appear here.'} /> : (
      <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">{data.items.map((m) => <PhotoCard key={m.id} m={m} canReview={canReview}
        onApprove={() => decide.mutate({ id: m.id, decision: 'APPROVE' })} onReject={() => setRejecting(m)} />)}</ul>)}
    <ConfirmDialog open={!!rejecting} title={rejecting?.status === 'APPROVED' ? 'Take this photo down?' : 'Reject this photo?'} confirmLabel={rejecting?.status === 'APPROVED' ? 'Take down' : 'Reject'} tone="danger" requireReason reasonLabel="Reason (the uploader will see this)"
      loading={decide.isPending} onClose={() => setRejecting(null)} onConfirm={(note) => rejecting && decide.mutate({ id: rejecting.id, decision: 'REJECT', note })} />
  </>);
}

export default function App() {
  return (
    <MicroApp name="documents">
      <Routes>
        <Route index element={<Library />} />
        <Route path="photos" element={<Photos />} />
        <Route path="signatures" element={<Signatures />} />
        <Route path="signatures/:id" element={<SignaturePage />} />
        <Route path=":id" element={<Detail />} />
      </Routes>
    </MicroApp>
  );
}
