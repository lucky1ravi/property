import { type ReactNode, useState } from 'react';
import clsx from 'clsx';
import { motion } from 'framer-motion';
import { Check, Clock, AlertTriangle } from 'lucide-react';
import { Button } from './primitives';
import { ConfirmDialog } from './overlay';
import { fmt } from '../lib/format';

export interface WorkflowAction {
  action: string;
  label: string;
  to: string;
  requiresReason: boolean;
}

/** The happy path of a workflow with the current stage marked; side states (rejected etc.) shown as a flag. */
export function WorkflowTrack({ steps, current, compact }: { steps: { key: string; label: string }[]; current: string; compact?: boolean }) {
  const idx = steps.findIndex((s) => s.key === current);
  const offPath = idx === -1;
  return (
    <div className="w-full">
      <ol className="flex items-start">
        {steps.map((s, i) => {
          const done = !offPath && i < idx;
          const active = i === idx;
          return (
            <li key={s.key} className="relative flex flex-1 flex-col items-center text-center last:flex-none">
              {i < steps.length - 1 && (
                <span className="absolute left-1/2 top-[11px] h-0.5 w-full bg-limestone-300">
                  <motion.span className="block h-full bg-federal-700" initial={false} animate={{ width: done ? '100%' : '0%' }} transition={{ duration: 0.35 }} />
                </span>
              )}
              <span className={clsx('relative z-10 flex h-6 w-6 items-center justify-center rounded-full border-2 text-2xs font-bold',
                done && 'border-federal-700 bg-federal-700 text-white',
                active && 'border-federal-900 bg-white text-federal-900 ring-4 ring-federal-100',
                !done && !active && 'border-limestone-300 bg-white text-graphite-400')}>
                {done ? <Check className="h-3.5 w-3.5" /> : i + 1}
              </span>
              {!compact && <span className={clsx('mt-1.5 px-1 text-[11px] font-semibold leading-tight', active ? 'text-federal-900' : done ? 'text-graphite-700' : 'text-graphite-400')}>{s.label}</span>}
            </li>
          );
        })}
      </ol>
      {offPath && <p className="mt-2 text-xs font-semibold text-crit-700">Ended off the main path: {fmt.title(current)}</p>}
    </div>
  );
}

/** Buttons for the workflow transitions this user may take. Reason-required actions prompt first. */
export function ActionBar({ actions, onAction, loading, primary }: { actions: WorkflowAction[] | undefined; onAction: (action: string, reason?: string) => void; loading?: boolean; primary?: string[] }) {
  const [pending, setPending] = useState<WorkflowAction | null>(null);
  if (!actions || actions.length === 0) return null;
  const isPrimary = (a: WorkflowAction) => primary?.includes(a.action) ?? false;
  const isNegative = (a: WorkflowAction) => /REJECT|DECLINE|VOID|CANCEL|WITHDRAW|NO_SHOW/.test(a.action);
  return (
    <>
      {actions.map((a) => (
        <Button key={a.action} size="sm" loading={loading && pending?.action === a.action}
          variant={isPrimary(a) ? 'primary' : isNegative(a) ? 'danger' : 'secondary'}
          onClick={() => (a.requiresReason ? setPending(a) : onAction(a.action))}>
          {a.label}
        </Button>
      ))}
      <ConfirmDialog open={!!pending} title={pending?.label ?? ''} confirmLabel={pending?.label ?? 'Confirm'} requireReason
        tone={pending && isNegative(pending) ? 'danger' : 'primary'}
        body="This step needs a reason. It's saved with the record and shown in the audit trail."
        onClose={() => setPending(null)} onConfirm={(reason) => { onAction(pending!.action, reason); setPending(null); }} />
    </>
  );
}

/** Time left against an SLA. */
export function SlaClock({ dueAt, done }: { dueAt?: string | null; done?: boolean }) {
  if (!dueAt) return null;
  const ms = new Date(dueAt).getTime() - Date.now();
  const breached = ms < 0 && !done;
  const soon = ms > 0 && ms < 4 * 3600_000 && !done;
  return (
    <span className={clsx('inline-flex items-center gap-1 text-xs font-semibold tabular-nums',
      done ? 'text-graphite-500' : breached ? 'text-crit-700' : soon ? 'text-warn-700' : 'text-graphite-600')}>
      {breached ? <AlertTriangle className="h-3.5 w-3.5" /> : <Clock className="h-3.5 w-3.5" />}
      {done ? 'SLA met' : breached ? `SLA breached ${fmt.relative(dueAt)}` : `Due ${fmt.relative(dueAt)}`}
    </span>
  );
}

export interface TimelineItem {
  id: string | number;
  action: string;
  from_state?: string;
  to_state?: string;
  actor_name?: string;
  created_at: string;
  details?: string | Record<string, unknown>;
}

/** Audit trail for a record: every state change, who made it and why. */
export function Timeline({ items, empty = 'No activity yet' }: { items: TimelineItem[] | undefined; empty?: string }) {
  if (!items || items.length === 0) return <p className="text-sm text-graphite-500">{empty}</p>;
  return (
    <ol className="relative ml-2 border-l border-limestone-300">
      {items.map((it) => {
        let details: Record<string, any> = {};
        try {
          details = typeof it.details === 'string' ? JSON.parse(it.details) : (it.details as any) ?? {};
        } catch { /* ignore */ }
        const reason = details.reason ?? details.note;
        return (
          <li key={it.id} className="mb-4 ml-4 last:mb-0">
            <span className="absolute -left-[5px] mt-1.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-federal-700" />
            <div className="text-sm text-graphite-900">
              <span className="font-semibold">{fmt.title(it.action)}</span>
              {it.to_state && <span className="text-graphite-500"> → {fmt.title(it.to_state)}</span>}
            </div>
            <div className="text-xs text-graphite-500">{it.actor_name ?? 'System'} · {fmt.dateTime(it.created_at)}</div>
            {reason && <div className="mt-1 rounded bg-limestone-100 px-2 py-1 text-xs text-graphite-700">“{String(reason)}”</div>}
          </li>
        );
      })}
    </ol>
  );
}

export function Section({ title, children, aside }: { title: string; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-6 last:mb-0">
      <div className="mb-2 flex items-center justify-between">
        <h3 className="text-[13px] font-bold text-graphite-700">{title}</h3>
        {aside}
      </div>
      {children}
    </div>
  );
}
