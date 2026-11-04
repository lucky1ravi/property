import { useState, type ReactNode } from 'react';
import { Star } from 'lucide-react';
import clsx from 'clsx';
import { Drawer } from './overlay';
import { Button, Textarea } from './primitives';
import { useApi, useAction } from '../lib/hooks';
import { fmt } from '../lib/format';

/** Five stars showing a value from 0 to 5, to one decimal, with the number of ratings beside it. */
export function Stars({ value, count, size = 14, className }: { value: number | string | null | undefined; count?: number | string | null; size?: number; className?: string }) {
  const v = Number(value ?? 0);
  const n = Number(count ?? 0);
  if (!v && !n) return <span className={clsx('text-xs text-graphite-500', className)}>No ratings yet</span>;
  return (
    <span className={clsx('inline-flex items-center gap-1.5 whitespace-nowrap', className)} title={`${v.toFixed(1)} out of 5${n ? ` from ${n} rating${n === 1 ? '' : 's'}` : ''}`}>
      <span className="inline-flex" aria-hidden>
        {[1, 2, 3, 4, 5].map((i) => (
          <Star key={i} width={size} height={size} className={i <= Math.round(v) ? 'fill-[#C8963E] text-[#C8963E]' : 'text-limestone-300'} />
        ))}
      </span>
      <span className="text-xs font-semibold text-graphite-800">{v.toFixed(1)}</span>
      {n > 0 && <span className="text-xs text-graphite-500">({n})</span>}
      <span className="sr-only">{v.toFixed(1)} out of 5 stars</span>
    </span>
  );
}

/** Five stars to choose from. Keyboard: Tab to the group, then left and right arrows. */
export function StarInput({ value, onChange, label, size = 26 }: { value: number; onChange: (v: number) => void; label: string; size?: number }) {
  const [hover, setHover] = useState(0);
  const shown = hover || value;
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex gap-0.5" onMouseLeave={() => setHover(0)}>
      {[1, 2, 3, 4, 5].map((i) => (
        <button key={i} type="button" role="radio" aria-checked={value === i} aria-label={`${i} star${i === 1 ? '' : 's'}`} tabIndex={value === i || (!value && i === 1) ? 0 : -1}
          onMouseEnter={() => setHover(i)} onClick={() => onChange(i)}
          onKeyDown={(e) => { if (e.key === 'ArrowRight' || e.key === 'ArrowUp') onChange(Math.min(5, (value || 0) + 1)); if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') onChange(Math.max(1, (value || 2) - 1)); }}
          className="rounded p-0.5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2">
          <Star width={size} height={size} className={i <= shown ? 'fill-[#C8963E] text-[#C8963E]' : 'text-limestone-300'} />
        </button>
      ))}
    </div>
  );
}

/** A form for one rating: stars and an optional comment. */
export function RatingForm({ initial, onSave, saving, label = 'Your rating', saveLabel = 'Save rating', prompt }: {
  initial?: { stars?: number; comment?: string } | null; onSave: (stars: number, comment: string) => void; saving?: boolean; label?: string; saveLabel?: string; prompt?: ReactNode;
}) {
  const [stars, setStars] = useState(initial?.stars ?? 0);
  const [comment, setComment] = useState(initial?.comment ?? '');
  return (
    <div className="space-y-2">
      {prompt && <div className="text-sm font-semibold text-graphite-900">{prompt}</div>}
      <StarInput value={stars} onChange={setStars} label={label} />
      <Textarea rows={2} value={comment} maxLength={1000} onChange={(e) => setComment(e.target.value)} placeholder="Add a comment (optional)" aria-label={`${label}: comment`} />
      <Button variant="primary" size="sm" loading={saving} disabled={!stars} onClick={() => onSave(stars, comment)}>{initial?.stars ? 'Update rating' : saveLabel}</Button>
    </div>
  );
}

/**
 * The ratings of one thing (a property, a unit, a vendor) in a drawer: the average, how the stars are spread, and what people wrote.
 * Pass `rate` to let the signed-in person add their own rating from here.
 */
export function ReviewsDrawer({ title, subtitle, endpoint, onClose, rate, reply, moderate }: {
  title: ReactNode; subtitle?: ReactNode; endpoint: string; onClose: () => void;
  rate?: { send: (stars: number, comment: string) => Promise<unknown>; prompt?: ReactNode };
  /** Lets staff answer a review in public. Pass it only to people allowed to; an empty text removes the reply. */
  reply?: (reviewId: string, text: string) => Promise<unknown>;
  /** Lets staff override the AI moderation of a comment: SHOW it, HIDE it, or CLEAR the override. SHOW and HIDE need a reason. Pass it only to people allowed to. */
  moderate?: (reviewId: string, decision: 'SHOW' | 'HIDE' | 'CLEAR', reason: string) => Promise<unknown>;
}) {
  const { data, isLoading, refetch } = useApi<any>(endpoint);
  const [replying, setReplying] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [deciding, setDeciding] = useState<{ id: string; decision: 'SHOW' | 'HIDE' } | null>(null);
  const [why, setWhy] = useState('');
  const decide = useAction((v: { id: string; decision: 'SHOW' | 'HIDE' | 'CLEAR'; reason: string }) => moderate!(v.id, v.decision, v.reason), { success: 'Saved', onSuccess: () => { setDeciding(null); setWhy(''); void refetch(); } });
  const sendReply = useAction((v: { id: string; text: string }) => reply!(v.id, v.text), { success: 'Reply saved', onSuccess: () => { setReplying(null); void refetch(); } });
  const save = useAction((v: { stars: number; comment: string }) => rate!.send(v.stars, v.comment), { success: 'Thank you. Your rating is saved.', onSuccess: () => refetch() });
  const total = Number(data?.count ?? 0);
  const rows: any[] = data?.rows ?? data?.items ?? [];
  return (
    <Drawer open onClose={onClose} title={title} subtitle={subtitle} width={520}>
      {isLoading || !data ? <p className="text-sm text-graphite-600">Loading…</p> : (
        <div className="space-y-6">
          <div className="flex items-center gap-5">
            <div className="text-center"><div className="text-4xl font-extrabold text-graphite-950">{total ? Number(data.average).toFixed(1) : '–'}</div><Stars value={data.average} size={16} /><div className="mt-1 text-xs text-graphite-500">{total} rating{total === 1 ? '' : 's'}</div></div>
            <div className="flex-1 space-y-1" aria-label="How the ratings are spread">
              {[5, 4, 3, 2, 1].map((s) => {
                const n = Number(data.distribution?.[s] ?? 0);
                return (<div key={s} className="flex items-center gap-2 text-xs text-graphite-600"><span className="w-3 text-right">{s}</span><Star width={11} height={11} className="fill-[#C8963E] text-[#C8963E]" />
                  <div className="h-2 flex-1 overflow-hidden rounded bg-limestone-200"><div className="h-full bg-[#C8963E]" style={{ width: total ? `${(n / total) * 100}%` : 0 }} /></div><span className="w-6 text-right">{n}</span></div>);
              })}
            </div>
          </div>
          {rate && <div className="rounded-md border border-limestone-200 bg-limestone-50 p-4"><RatingForm prompt={rate.prompt ?? 'Add your rating'} saving={save.isPending} onSave={(stars, comment) => save.mutate({ stars, comment })} /></div>}
          <div>
            <h3 className="mb-2 text-sm font-bold text-graphite-900">What people said</h3>
            {rows.length === 0 ? <p className="text-sm text-graphite-600">Nothing yet.</p> : (
              <ul className="divide-y divide-limestone-200 rounded border border-limestone-200">
                {rows.map((r) => (
                  <li key={r.id} className="space-y-1 px-3 py-3">
                    <div className="flex items-center justify-between gap-3"><span className="text-sm font-semibold text-graphite-900">{r.rater_name}{r.rater_role ? <span className="ml-2 text-xs font-normal text-graphite-500">{String(r.rater_role).toLowerCase()}</span> : null}</span><Stars value={r.stars} size={12} /></div>
                    {r.comment && <p className="text-sm text-graphite-700">{r.comment}</p>}
                    {r.hidden_from_public && <div className="rounded bg-warn-50 px-2 py-1.5 text-xs text-graphite-800" role="note" data-testid="hidden-comment"><strong>Hidden from visitors.</strong> {r.ai_score == null ? 'Not checked yet.' : `Moderation score ${r.ai_score} is under the ${data.min_score} needed.`}{r.ai_reasons ? ` ${r.ai_reasons}` : ''}</div>}
                    {r.moderation_override && <div className="text-xs text-graphite-600" data-testid="moderation-override">{r.moderation_override === 'SHOW' ? 'Shown' : 'Hidden'} by {r.override_by} on {fmt.date(r.override_at)}: {r.override_reason}</div>}
                    <div className="text-2xs text-graphite-500">{fmt.date(r.created_at)}</div>
                    {moderate && r.comment && !deciding && <div className="flex flex-wrap gap-2">
                      {r.hidden_from_public && r.moderation_override !== 'SHOW' && <Button size="sm" variant="secondary" aria-label={`Show ${r.rater_name}'s comment anyway`} onClick={() => { setDeciding({ id: r.id, decision: 'SHOW' }); setWhy(''); }}>Show anyway</Button>}
                      {!r.hidden_from_public && <Button size="sm" variant="secondary" aria-label={`Hide ${r.rater_name}'s comment from visitors`} onClick={() => { setDeciding({ id: r.id, decision: 'HIDE' }); setWhy(''); }}>Hide from visitors</Button>}
                      {r.moderation_override && <Button size="sm" variant="quiet" aria-label={`Let the AI decide for ${r.rater_name}'s comment`} loading={decide.isPending} onClick={() => decide.mutate({ id: r.id, decision: 'CLEAR', reason: '' })}>Back to the AI decision</Button>}
                    </div>}
                    {moderate && deciding && deciding.id === r.id && <div className="mt-2 space-y-2">
                      <Textarea rows={2} value={why} maxLength={300} onChange={(e) => setWhy(e.target.value)} aria-label={`Reason for ${deciding.decision === 'SHOW' ? 'showing' : 'hiding'} ${r.rater_name}'s comment`} placeholder="Why? This is kept in the audit trail." />
                      <div className="flex gap-2"><Button size="sm" variant="primary" disabled={!why.trim()} loading={decide.isPending} onClick={() => decide.mutate({ id: r.id, decision: deciding.decision, reason: why })}>{deciding.decision === 'SHOW' ? 'Show to visitors' : 'Hide from visitors'}</Button><Button size="sm" variant="quiet" onClick={() => setDeciding(null)}>Cancel</Button></div></div>}
                    {r.reply && replying !== r.id && <div className="mt-2 rounded border-l-2 border-federal-700 bg-limestone-50 px-3 py-2 text-sm text-graphite-800"><div className="text-2xs font-semibold text-graphite-600">Public reply · {fmt.date(r.replied_at)}</div>{r.reply}</div>}
                    {reply && replying !== r.id && <Button size="sm" variant="quiet" aria-label={`${r.reply ? 'Edit reply to' : 'Reply to'} ${r.rater_name}`} onClick={() => { setReplying(r.id); setText(r.reply ?? ''); }}>{r.reply ? 'Edit reply' : 'Reply publicly'}</Button>}
                    {reply && replying === r.id && <div className="mt-2 space-y-2">
                      <Textarea rows={3} value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} aria-label={`Public reply to ${r.rater_name}`} placeholder="Everyone who views this listing will see your reply." />
                      <div className="flex gap-2"><Button size="sm" variant="primary" loading={sendReply.isPending} onClick={() => sendReply.mutate({ id: r.id, text })}>{text.trim() ? 'Post reply' : 'Remove reply'}</Button><Button size="sm" variant="quiet" onClick={() => setReplying(null)}>Cancel</Button></div></div>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </Drawer>
  );
}
