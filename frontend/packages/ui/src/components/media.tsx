import { useEffect, useRef, useState } from 'react';
import clsx from 'clsx';
import { Camera, CheckCircle2, ShieldAlert, Sparkles, Trash2, XCircle } from 'lucide-react';
import { api } from '../lib/api';
import { useApi, useAction } from '../lib/hooks';
import { useSession } from '../lib/session';
import { Badge, Button, Skeleton } from './primitives';
import { ConfirmDialog } from './overlay';

/**
 * One photo as listed by the photo review screens.
 */
export interface MediaRow {
  id: string; kind: 'PROPERTY' | 'PROFILE'; subject_id: string; subject_label?: string; file_name: string; status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
  decided_by?: 'AI' | 'PERSON'; ai_verdict?: string; ai_confidence?: number | string; ai_category?: string; ai_labels?: string[]; ai_quality?: number | null; ai_caption?: string | null; ai_quality_issues?: string[]; ai_reason?: string; ai_provider?: string; ai_simulated?: boolean;
  threshold_used?: number; flag_reason?: string; reviewer_name?: string; decision_note?: string; uploaded_by: string; uploaded_by_name?: string; created_at: string; publicUrl?: string | null;
}

/**
 * Query keys to refresh after a photo changes.
 */
export const MEDIA_INVALIDATE = ['/api/documents/media'];

/** Shows a photo that needs the session token (pending and rejected photos are never public). */
export function AuthImage({ id, alt, className }: { id: string; alt: string; className?: string }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let gone = false; let u: string | null = null;
    setUrl(null); setFailed(false);
    api.blob(`/api/documents/media/${id}/content`).then(({ blob }) => { if (gone) return; u = URL.createObjectURL(blob); setUrl(u); }).catch(() => !gone && setFailed(true));
    return () => { gone = true; if (u) URL.revokeObjectURL(u); };
  }, [id]);
  if (failed) return <div className={clsx('flex items-center justify-center bg-limestone-100 text-xs text-graphite-500', className)}>Can’t show this photo</div>;
  if (!url) return <Skeleton className={className} />;
  return <img src={url} alt={alt} className={clsx('object-cover', className)} />;
}

/**
 * Badge showing whether a photo is pending, approved or rejected and whether AI or a person decided.
 */
export function MediaStatus({ m }: { m: Pick<MediaRow, 'status' | 'decided_by'> }) {
  if (m.status === 'APPROVED') return <Badge tone="good">{m.decided_by === 'AI' ? 'Published by AI check' : 'Approved by reviewer'}</Badge>;
  if (m.status === 'REJECTED') return <Badge tone="crit">{m.decided_by === 'AI' ? 'Rejected by AI check' : 'Rejected'}</Badge>;
  return <Badge tone="warn">Waiting for review</Badge>;
}

const pct = (v: number | string | undefined) => Math.round(Number(v ?? 0) * 100);

/** How sure the AI was, against the line that decides whether a photo is published without a person. */
export function PhotoConfidence({ confidence, threshold, verdict }: { confidence?: number | string; threshold?: number; verdict?: string }) {
  const c = pct(confidence);
  const tone = verdict === 'UNSAFE' ? 'bg-crit-500' : c >= (threshold ?? 80) ? 'bg-good-500' : 'bg-warn-500';
  return (
    <div>
      <div className="relative h-2 rounded-full bg-limestone-200" role="img" aria-label={`AI confidence ${c} percent${threshold ? `, auto-approve line ${threshold} percent` : ''}`}>
        <div className={clsx('h-2 rounded-full', tone)} style={{ width: `${c}%` }} />
        {threshold != null && <div className="absolute -top-1 h-4 w-0.5 bg-graphite-800" style={{ left: `${threshold}%` }} title={`Auto-approve at ${threshold}%`} />}
      </div>
      <div className="mt-1 flex justify-between text-xs text-graphite-600"><span>{verdict === 'UNSAFE' ? 'Unsafe' : verdict === 'SAFE' ? 'Safe' : 'Unsure'} · {c}% confident</span>{threshold != null && <span>Auto-approve at {threshold}%</span>}</div>
    </div>
  );
}

/** Choose a photo, upload it, and tell the person straight away whether it was published or sent for review. */
export function PhotoUploadButton({ kind, subjectId, label = 'Add photo', onDone }: { kind: 'PROPERTY' | 'PROFILE'; subjectId?: string; label?: string; onDone?: (r: MediaRow) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [note, setNote] = useState<{ tone: 'good' | 'warn' | 'crit'; text: string } | null>(null);
  const send = useAction((file: File) => {
    const f = new FormData();
    f.append('file', file); f.append('kind', kind);
    if (subjectId) f.append('subjectId', subjectId);
    return api.upload<MediaRow>('/api/documents/media', f);
  }, {
    invalidate: MEDIA_INVALIDATE.concat(['/api/leasing/properties']), silent: true,
    onSuccess: (r) => {
      setNote(r.status === 'APPROVED' ? { tone: 'good', text: `Published. The AI check was ${pct(r.ai_confidence)}% confident it’s fine.` }
        : r.status === 'REJECTED' ? { tone: 'crit', text: `Not accepted: ${r.decision_note ?? 'the photo was rejected.'}` }
          : { tone: 'warn', text: `Sent for review. ${r.flag_reason ?? ''} A reviewer will decide.` });
      onDone?.(r);
    },
  });
  return (
    <div>
      <input ref={input} type="file" accept="image/jpeg,image/png,image/webp,image/gif" className="sr-only" aria-label={label}
        onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) { setNote(null); send.mutate(f); } }} />
      <Button variant="secondary" icon={<Camera className="h-4 w-4" />} loading={send.isPending} onClick={() => input.current?.click()}>{send.isPending ? 'Checking photo…' : label}</Button>
      {note && <p role="status" className={clsx('mt-2 flex items-start gap-1.5 text-sm', note.tone === 'good' ? 'text-good-700' : note.tone === 'crit' ? 'text-crit-700' : 'text-warn-700')}>
        {note.tone === 'good' ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" /> : note.tone === 'crit' ? <XCircle className="mt-0.5 h-4 w-4 shrink-0" /> : <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />}{note.text}</p>}
    </div>
  );
}

/** Gallery for one property with each photo’s review status, an upload button, and removal. Shown only to staff who can upload photos. */
export function PhotoManager({ propertyId }: { propertyId: string }) {
  const { can } = useSession();
  const allowed = can('MEDIA:UPLOAD');
  const { data, isLoading } = useApi<{ items: MediaRow[] }>(allowed ? `/api/documents/media?kind=PROPERTY&subjectId=${propertyId}` : null);
  const [removing, setRemoving] = useState<MediaRow | null>(null);
  const del = useAction((id: string) => api.del(`/api/documents/media/${id}`), { success: 'Photo removed', invalidate: MEDIA_INVALIDATE.concat(['/api/leasing/properties']), onSuccess: () => setRemoving(null) });
  if (!allowed) return null;
  const items = data?.items ?? [];
  return (<>
    <div className="mb-3 flex items-center justify-between gap-3">
      <p className="flex items-center gap-1.5 text-xs text-graphite-600"><Sparkles className="h-3.5 w-3.5" />Every photo is checked by AI before it is published.</p>
      <PhotoUploadButton kind="PROPERTY" subjectId={propertyId} />
    </div>
    {isLoading ? <Skeleton className="h-24" /> : items.length === 0 ? <p className="text-sm text-graphite-600">No photos uploaded here yet. Existing listing photos are not included.</p> : (
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">{items.map((m) => (
        <li key={m.id} className="overflow-hidden rounded border border-limestone-200 bg-white">
          <AuthImage id={m.id} alt={m.file_name} className="h-24 w-full" />
          <div className="space-y-1 p-2"><MediaStatus m={m} />
            {m.status === 'PENDING_REVIEW' && <p className="text-xs text-graphite-600">{m.flag_reason}</p>}
            {m.ai_quality != null && <p className="text-xs text-graphite-600">Quality {m.ai_quality}/100{m.ai_quality_issues && m.ai_quality_issues.length > 0 ? ` · ${m.ai_quality_issues.join(', ')}` : ''}</p>}
            {m.status === 'REJECTED' && m.decision_note && <p className="text-xs text-crit-700">{m.decision_note}</p>}
            <button type="button" className="inline-flex items-center gap-1 text-xs text-graphite-600 hover:text-crit-700" onClick={() => setRemoving(m)}><Trash2 className="h-3 w-3" />Remove</button></div>
        </li>))}</ul>)}
    <ConfirmDialog open={!!removing} title="Remove this photo?" confirmLabel="Remove" tone="danger" loading={del.isPending} onClose={() => setRemoving(null)} onConfirm={() => removing && del.mutate(removing.id)}
      body={removing?.status === 'APPROVED' ? 'It will also be taken off the property and any listings that use the gallery.' : 'It will be deleted.'} />
  </>);
}

