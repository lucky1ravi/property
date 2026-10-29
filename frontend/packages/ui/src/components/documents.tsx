import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import clsx from 'clsx';
import { CheckCircle2, CircleAlert, Clock3, Download, Eye, FileImage, FileSpreadsheet, FileText, Lock, PenLine, Sparkles, UploadCloud, X } from 'lucide-react';
import { api, saveBlob, ApiError } from '../lib/api';
import { useApi } from '../lib/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { useSession } from '../lib/session';
import { fmt } from '../lib/format';
import { toast, toastError } from '../lib/toast';
import { Panel } from './layout';
import { Modal } from './overlay';
import { Badge, Button, Field, Input, Select, Skeleton, Textarea, Toggle } from './primitives';

// ---------------------------------------------------------------- types and helpers

/**
 * Categories, document types and rules the upload form needs, from {@code /api/documents/meta}.
 */
export interface DocMeta {
  categories: { key: string; label: string; sensitive: boolean; retentionYears: number; types: string[] }[];
  uploadCategories: string[];
  maxFileMb: number;
  allowedExtensions: string[];
  requirements: Record<string, string[]>;
  esignEnabled: boolean;
  consentText: string;
  smartFiling: boolean;
  staff: boolean;
  sensitiveAccess: string;
}

/**
 * One document as listed by the library.
 */
export interface DocRow {
  id: string; title: string; category: string; category_label: string; doc_type?: string; description?: string; status: string; source: string;
  entity_type?: string; entity_id?: string; entity_label?: string; property_id?: string; property_name?: string; owner_id?: string; tenant_id?: string; vendor_id?: string;
  share_owner: boolean; share_tenant: boolean; share_vendor: boolean; sensitive: boolean; restricted: boolean; tags?: string[]; expires_on?: string; days_to_expiry?: number | null;
  retain_until?: string; legal_hold: boolean; review_status: string; review_note?: string; signature_status: string; current_version: number; file_name: string; mime_type: string;
  size_bytes: number; sha256?: string; uploaded_by_name?: string; uploader_scope?: string; created_at: string; updated_at: string; previewable: boolean; mine: boolean;
}

/**
 * Query keys to refresh after a document changes.
 */
export const DOC_INVALIDATE = ['/api/documents'];

/**
 * Loads and caches the document categories, types and rules.
 */
export function useDocMeta() {
  const { can, feature } = useSession();
  return useApi<DocMeta>(can('DOCUMENT:VIEW') && feature('DOCUMENTS') ? '/api/documents/meta' : null, { staleTime: 120_000 });
}

/**
 * Formats a byte count for people, for example "240 KB"; shows a dash when unknown.
 */
export const fmtBytes = (n?: number) => (!n ? '—' : n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(0)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

/**
 * Icon matching a file's content type.
 */
export function DocIcon({ mime, className }: { mime?: string; className?: string }) {
  const c = clsx('h-5 w-5 shrink-0', className);
  if (mime?.startsWith('image/')) return <FileImage className={clsx(c, 'text-slateblue-600')} aria-hidden />;
  if (mime?.includes('sheet') || mime?.includes('excel') || mime === 'text/csv') return <FileSpreadsheet className={clsx(c, 'text-good-700')} aria-hidden />;
  return <FileText className={clsx(c, mime === 'application/pdf' ? 'text-brick-600' : 'text-graphite-500')} aria-hidden />;
}

/** Plain-language state chips for a document: review, signature, expiry, restriction. */
export function DocChips({ d }: { d: DocRow }) {
  const chips: ReactNode[] = [];
  if (d.restricted) chips.push(<Badge key="r" tone="neutral"><Lock className="mr-1 h-3 w-3" />Restricted</Badge>);
  if (d.review_status === 'PENDING') chips.push(<Badge key="rv" tone="warn">In review</Badge>);
  if (d.review_status === 'REJECTED') chips.push(<Badge key="rj" tone="crit">Needs a new file</Badge>);
  if (d.signature_status === 'PENDING') chips.push(<Badge key="s" tone="info">Out for signature</Badge>);
  if (d.signature_status === 'PARTIAL') chips.push(<Badge key="s" tone="info">Partly signed</Badge>);
  if (d.signature_status === 'SIGNED') chips.push(<Badge key="s" tone="good">Signed</Badge>);
  if (d.signature_status === 'DECLINED') chips.push(<Badge key="s" tone="crit">Signature declined</Badge>);
  if (d.days_to_expiry != null) {
    if (d.days_to_expiry < 0) chips.push(<Badge key="e" tone="crit">Expired {fmt.shortDate(d.expires_on)}</Badge>);
    else if (d.days_to_expiry <= 30) chips.push(<Badge key="e" tone="warn">Expires in {d.days_to_expiry} day{d.days_to_expiry === 1 ? '' : 's'}</Badge>);
  }
  if (d.status === 'ARCHIVED') chips.push(<Badge key="a" tone="neutral">Archived</Badge>);
  if (d.legal_hold) chips.push(<Badge key="h" tone="brand">Legal hold</Badge>);
  return <>{chips}</>;
}

/**
 * Downloads a document (or one of its versions) with the user's token and saves it in the browser.
 */
export async function downloadDocument(id: string, version?: number) {
  try {
    const { blob, fileName } = await api.blob(`/api/documents/${id}/file${version ? `?version=${version}` : ''}`);
    saveBlob(blob, fileName);
  } catch (e) {
    toastError(e, 'Couldn’t download the file');
  }
}

// ---------------------------------------------------------------- preview

/** Renders a PDF, image or text file inline. The file is fetched with the session token, never through a public link. */
export function DocumentViewer({ doc, version, className = 'h-[70vh]' }: { doc: Pick<DocRow, 'id' | 'title' | 'mime_type' | 'previewable' | 'restricted'>; version?: number; className?: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (doc.restricted || !doc.previewable) return;
    let gone = false; let u: string | null = null;
    setErr(null); setUrl(null); setText(null);
    api.blob(`/api/documents/${doc.id}/file?inline=true${version ? `&version=${version}` : ''}`).then(async ({ blob }) => {
      if (gone) return;
      if (doc.mime_type?.startsWith('text/')) setText((await blob.text()).slice(0, 200_000));
      else { u = URL.createObjectURL(blob); setUrl(u); }
    }).catch((e) => setErr(e instanceof ApiError ? e.message : 'Couldn’t open the file'));
    return () => { gone = true; if (u) URL.revokeObjectURL(u); };
  }, [doc.id, version]);
  if (doc.restricted) return <p className="text-sm text-graphite-700">Your role can see that this document exists but can’t open it. Ask an administrator if you need access.</p>;
  if (!doc.previewable) return <p className="text-sm text-graphite-700">This file type can’t be shown in the browser. Download it to open it.</p>;
  if (err) return <p className="text-sm text-crit-700">{err}</p>;
  if (text != null) return <pre className={clsx('overflow-auto whitespace-pre-wrap rounded bg-limestone-50 p-4 text-xs text-graphite-800', className)}>{text}</pre>;
  if (!url) return <Skeleton className={clsx('w-full', className)} />;
  if (doc.mime_type === 'application/pdf') return <iframe title={doc.title} src={url} className={clsx('w-full rounded border border-limestone-200 bg-white', className)} />;
  return <img src={url} alt={doc.title} className="mx-auto max-h-[70vh] rounded" />;
}

/**
 * Shows a document inline when its type is safe to preview, otherwise offers a download.
 */
export function DocumentPreview({ doc, version, open, onClose }: { doc: Pick<DocRow, 'id' | 'title' | 'mime_type' | 'previewable' | 'restricted'> | null; version?: number; open: boolean; onClose: () => void }) {
  if (!doc) return null;
  return (
    <Modal open={open} onClose={onClose} size="lg" title={doc.title}
      footer={<><Button variant="quiet" onClick={onClose}>Close</Button>{!doc.restricted && <Button icon={<Download className="h-4 w-4" />} onClick={() => downloadDocument(doc.id, version)}>Download</Button>}</>}>
      {open && <DocumentViewer doc={doc} version={version} />}
    </Modal>
  );
}

// ---------------------------------------------------------------- upload

const EXPIRING = ['INSURANCE', 'CONTRACT', 'IDENTITY'];

/**
 * The record a new document should be attached to.
 */
export interface UploadTarget { entityType?: string; entityId?: string; propertyId?: string; label?: string }

/** Upload with smart filing: the category and type are suggested from the file name; the person confirms. */
export function UploadDocumentDialog({ open, onClose, target, defaultCategory, defaultDocType, onUploaded, newVersionOf }: {
  open: boolean; onClose: () => void; target?: UploadTarget; defaultCategory?: string; defaultDocType?: string; onUploaded?: (d: DocRow) => void;
  newVersionOf?: { id: string; title: string };
}) {
  const { data: meta } = useDocMeta();
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [drag, setDrag] = useState(false);
  const [f, setF] = useState<any>({});
  const [hint, setHint] = useState<any>(null);
  const [autoShare, setAutoShare] = useState(true);
  const [busy, setBusy] = useState(false);
  const [dup, setDup] = useState<string | null>(null);
  useEffect(() => {
    if (!open) return;
    setFile(null); setHint(null); setDup(null); setAutoShare(true);
    setF({ category: defaultCategory ?? '', docType: defaultDocType ?? '', title: '', expiresOn: '', description: '', tags: '', shareOwner: false, shareTenant: false, shareVendor: false, note: '' });
  }, [open, defaultCategory, defaultDocType]);
  const cats = useMemo(() => (meta?.categories ?? []).filter((c) => (meta?.uploadCategories ?? []).includes(c.key)), [meta]);
  const types = cats.find((c) => c.key === f.category)?.types ?? [];
  const pick = async (fl: File | undefined | null) => {
    if (!fl) return;
    setFile(fl); setDup(null);
    if (newVersionOf || !meta?.smartFiling) return;
    try {
      const s = await api.post<any>('/api/documents/classify', { fileName: fl.name, entityType: target?.entityType });
      setHint(s);
      setF((x: any) => ({ ...x, category: x.category || (meta.uploadCategories.includes(s.category) ? s.category : x.category), docType: x.docType || (meta.uploadCategories.includes(s.category) ? s.docType : x.docType) }));
    } catch { /* suggestion is optional */ }
  };
  const tooBig = file && meta ? file.size > meta.maxFileMb * 1024 * 1024 : false;
  const ext = file?.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
  const badType = file && meta ? !meta.allowedExtensions.includes(ext) : false;
  const submit = async (allowDuplicate = false) => {
    if (!file) return;
    setBusy(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      let r: DocRow;
      if (newVersionOf) {
        if (f.note) fd.append('note', f.note);
        r = await api.upload<DocRow>(`/api/documents/${newVersionOf.id}/versions`, fd);
        toast('New version uploaded');
      } else {
        for (const k of ['title', 'category', 'docType', 'description', 'expiresOn', 'tags']) if (f[k]) fd.append(k, f[k]);
        if (target?.entityType && target.entityId) { fd.append('entityType', target.entityType); fd.append('entityId', target.entityId); }
        if (target?.propertyId) fd.append('propertyId', target.propertyId);
        if (meta?.staff && !autoShare) { fd.append('shareOwner', String(!!f.shareOwner)); fd.append('shareTenant', String(!!f.shareTenant)); fd.append('shareVendor', String(!!f.shareVendor)); }
        if (allowDuplicate) fd.append('allowDuplicate', 'true');
        r = await api.upload<DocRow>('/api/documents', fd);
        toast('Uploaded', { body: r.review_status === 'PENDING' ? 'The team will review it shortly.' : `Filed under ${r.category_label}.` });
      }
      qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0] ?? '').startsWith('/api/documents') });
      onUploaded?.(r);
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && !newVersionOf) setDup(e.message);
      else toastError(e, 'Upload failed');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title={newVersionOf ? `New version of ${newVersionOf.title}` : target?.label ? `Add a document to ${target.label}` : 'Upload a document'}
      footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button>
        {dup && meta?.staff && <Button onClick={() => submit(true)} loading={busy}>Upload anyway</Button>}
        <Button variant="primary" icon={<UploadCloud className="h-4 w-4" />} loading={busy} disabled={!file || tooBig || badType || (!newVersionOf && !f.category)} onClick={() => submit(false)}>Upload</Button></>}>
      <div className="space-y-4">
        <div onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)} onDrop={(e) => { e.preventDefault(); setDrag(false); pick(e.dataTransfer.files?.[0]); }}
          className={clsx('flex flex-col items-center justify-center rounded-md border-2 border-dashed px-4 py-6 text-center transition-colors', drag ? 'border-federal-700 bg-federal-50' : 'border-limestone-300 bg-limestone-50')}>
          <UploadCloud className="mb-2 h-7 w-7 text-graphite-500" aria-hidden />
          {file ? <div className="flex items-center gap-2 text-sm font-semibold text-graphite-900"><DocIcon mime={file.type} />{file.name}<span className="font-normal text-graphite-500">{fmtBytes(file.size)}</span>
            <button type="button" aria-label="Remove file" onClick={() => { setFile(null); setHint(null); }} className="rounded p-0.5 text-graphite-500 hover:bg-limestone-200"><X className="h-4 w-4" /></button></div>
            : <><p className="text-sm font-semibold text-graphite-900">Drop a file here or <button type="button" className="text-federal-900 underline" onClick={() => input.current?.click()}>choose one</button></p>
              <p className="mt-1 text-xs text-graphite-500">{meta ? `${meta.allowedExtensions.map((x) => x.toUpperCase()).join(', ')} · up to ${meta.maxFileMb} MB` : ' '}</p></>}
          <input ref={input} type="file" className="sr-only" onChange={(e) => pick(e.target.files?.[0])} accept={meta?.allowedExtensions.map((x) => '.' + x).join(',')} />
        </div>
        {tooBig && <p className="text-sm text-crit-700">This file is larger than {meta?.maxFileMb} MB.</p>}
        {badType && <p className="text-sm text-crit-700">.{ext} files can’t be uploaded.</p>}
        {dup && <p className="rounded bg-warn-50 px-3 py-2 text-sm text-warn-800">{dup}</p>}
        {hint && !newVersionOf && <p className="flex items-start gap-2 rounded bg-slateblue-50 px-3 py-2 text-xs text-slateblue-800"><Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>Suggested filing: <b>{meta?.categories.find((c) => c.key === hint.category)?.label}</b>{hint.docType ? ` · ${hint.docType}` : ''}. {hint.reason} Change it below if it’s wrong.</span></p>}
        {newVersionOf ? <Field label="What changed" hint="Shown in the version history."><Input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} placeholder="For example: signed copy, corrected dates" /></Field> : <>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Category" required><Select value={f.category} onChange={(e) => setF({ ...f, category: e.target.value, docType: '' })}>
              <option value="">Choose a category</option>{cats.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}</Select></Field>
            <Field label="Document type"><Select value={f.docType} onChange={(e) => setF({ ...f, docType: e.target.value })} disabled={!f.category}>
              <option value="">{f.category ? 'Choose a type' : 'Choose a category first'}</option>{types.map((t) => <option key={t} value={t}>{t}</option>)}</Select></Field>
          </div>
          <Field label="Title" hint="Leave blank to name it from the type and the record."><Input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Field>
          {(EXPIRING.includes(f.category) || f.expiresOn) && <Field label="Expires on" hint="You and the team are reminded before this date."><Input type="date" value={f.expiresOn} onChange={(e) => setF({ ...f, expiresOn: e.target.value })} /></Field>}
          <Field label="Notes"><Textarea rows={2} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          {meta?.staff && <div className="rounded-md border border-limestone-200 p-3">
            <label className="flex items-center justify-between gap-3 text-sm font-semibold text-graphite-900">Share automatically for this type of document<Toggle checked={autoShare} onChange={setAutoShare} label="Share automatically" /></label>
            <p className="mt-1 text-xs text-graphite-500">Leases and notices go to the resident and owner, bills and property records to the owner, vendor paperwork to the vendor. Applicant files stay internal.</p>
            {!autoShare && <div className="mt-3 grid gap-2 sm:grid-cols-3">{[['shareOwner', 'Owner'], ['shareTenant', 'Resident'], ['shareVendor', 'Vendor']].map(([k, l]) => (
              <label key={k} className="flex items-center gap-2 text-sm text-graphite-800"><input type="checkbox" checked={!!f[k]} onChange={(e) => setF({ ...f, [k]: e.target.checked })} />Share with {l.toLowerCase()}</label>))}</div>}
          </div>}
        </>}
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- panel for any record

const STATE: Record<string, { label: string; tone: 'good' | 'warn' | 'crit' | 'info'; icon: ReactNode }> = {
  ON_FILE: { label: 'On file', tone: 'good', icon: <CheckCircle2 className="h-3.5 w-3.5" /> },
  IN_REVIEW: { label: 'In review', tone: 'warn', icon: <Clock3 className="h-3.5 w-3.5" /> },
  AWAITING_SIGNATURE: { label: 'Awaiting signature', tone: 'info', icon: <PenLine className="h-3.5 w-3.5" /> },
  MISSING: { label: 'Missing', tone: 'crit', icon: <CircleAlert className="h-3.5 w-3.5" /> },
  EXPIRED: { label: 'Expired', tone: 'crit', icon: <CircleAlert className="h-3.5 w-3.5" /> },
};

/**
 * Documents attached to one record (application, lease, work order, invoice, vendor, owner, property) with its
 * required-documents checklist and an upload button. Drop it into any detail page or quick view.
 */
export function DocumentsPanel({ entityType, entityId, filter, uploadTarget, title = 'Documents', description, requirements = true, compact = false, defaultCategory, emptyText, bare = false }: {
  entityType?: string; entityId?: string; filter?: Record<string, string>; uploadTarget?: UploadTarget | null; title?: string; description?: ReactNode;
  requirements?: boolean; compact?: boolean; defaultCategory?: string; emptyText?: string; bare?: boolean;
}) {
  const { can, feature } = useSession();
  const nav = useNavigate();
  const enabled = can('DOCUMENT:VIEW') && feature('DOCUMENTS');
  const qs = new URLSearchParams({ ...(entityType && entityId ? { entityType, entityId } : {}), ...(filter ?? {}) }).toString();
  const { data, isLoading } = useApi<{ items: DocRow[] }>(enabled && (entityId || filter) ? `/api/documents?${qs}` : null);
  const { data: meta } = useDocMeta();
  const needsCheck = requirements && entityType && entityId && (meta?.requirements?.[entityType]?.length ?? 0) > 0;
  const { data: req } = useApi<any>(enabled && needsCheck ? `/api/documents/requirements?entityType=${entityType}&entityId=${entityId}` : null);
  const [up, setUp] = useState<{ docType?: string; category?: string } | null>(null);
  const [view, setView] = useState<DocRow | null>(null);
  if (!enabled) return null;
  const target = uploadTarget === null ? undefined : uploadTarget ?? (entityType && entityId ? { entityType, entityId } : undefined);
  const canUpload = can('DOCUMENT:CREATE') && !!target && (meta?.uploadCategories?.length ?? 0) > 0;
  const items = data?.items ?? [];
  const catOf = (type: string) => meta?.categories.find((c) => c.types.includes(type))?.key;
  const body = (<>
    {req?.items?.length > 0 && <div className="mb-3 flex flex-wrap gap-2" aria-label="Required documents">{req.items.map((i: any) => {
      const s = STATE[i.state] ?? STATE.MISSING;
      const clickable = (i.state === 'MISSING' || i.state === 'EXPIRED') && canUpload && meta?.uploadCategories.includes(catOf(i.type) ?? '');
      return (<button key={i.type} type="button" disabled={!clickable && !i.documentId}
        onClick={() => (clickable ? setUp({ docType: i.type, category: catOf(i.type) }) : i.documentId && nav(`/app/documents/${i.documentId}`))}
        className={clsx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ring-inset',
          s.tone === 'good' && 'bg-good-50 text-good-700 ring-good-600/20', s.tone === 'warn' && 'bg-warn-50 text-warn-700 ring-warn-600/25',
          s.tone === 'info' && 'bg-slateblue-50 text-slateblue-700 ring-slateblue-600/20', s.tone === 'crit' && 'bg-crit-50 text-crit-700 ring-crit-600/20',
          (clickable || i.documentId) && 'hover:brightness-95')}>
        {s.icon}{i.type}<span className="font-normal opacity-80">· {s.label}{clickable ? ', upload' : ''}</span></button>);
    })}</div>}
    {isLoading ? <Skeleton className="h-20" /> : items.length === 0 ? <p className="text-sm text-graphite-500">{emptyText ?? 'No documents yet.'}</p> : (
      <ul className="-mx-1 divide-y divide-limestone-200">{items.slice(0, compact ? 6 : 50).map((d) => (
        <li key={d.id} className="flex items-start gap-3 px-1 py-2.5">
          <DocIcon mime={d.mime_type} className="mt-0.5" />
          <div className="min-w-0 flex-1">
            <button type="button" onClick={() => nav(`/app/documents/${d.id}`)} className="block max-w-full truncate text-left text-sm font-semibold text-graphite-900 hover:underline">{d.title}</button>
            <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-graphite-500">
              <span>{d.doc_type ?? d.category_label}</span><span>{d.uploaded_by_name ?? 'PropertyOS'}, {fmt.shortDate(d.created_at)}</span>{d.current_version > 1 && <span>v{d.current_version}</span>}<DocChips d={d} />
            </div>
          </div>
          {!d.restricted && <div className="flex shrink-0 gap-1">
            {d.previewable && <button type="button" onClick={() => setView(d)} className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100 hover:text-graphite-900" aria-label={`Preview ${d.title}`}><Eye className="h-4 w-4" /></button>}
            <button type="button" onClick={() => downloadDocument(d.id)} className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100 hover:text-graphite-900" aria-label={`Download ${d.title}`}><Download className="h-4 w-4" /></button>
          </div>}
        </li>))}</ul>)}
    {compact && items.length > 6 && <button type="button" className="mt-2 text-sm font-semibold text-federal-900 hover:underline" onClick={() => nav(`/app/documents?${qs}`)}>See all {items.length} documents</button>}
    <UploadDocumentDialog open={!!up} onClose={() => setUp(null)} target={target} defaultCategory={up?.category ?? defaultCategory} defaultDocType={up?.docType} />
    <DocumentPreview doc={view} open={!!view} onClose={() => setView(null)} />
  </>);
  const actions = canUpload ? <Button size="sm" icon={<UploadCloud className="h-4 w-4" />} onClick={() => setUp({})}>Upload</Button> : undefined;
  if (bare) return <div><div className="mb-2 flex items-center justify-between"><h3 className="text-sm font-bold text-graphite-900">{title}</h3>{actions}</div>{body}</div>;
  return <Panel title={title} description={description} actions={actions}>{body}</Panel>;
}
