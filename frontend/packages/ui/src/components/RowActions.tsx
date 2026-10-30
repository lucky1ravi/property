import type { ReactNode } from 'react';
import { motion } from 'framer-motion';

/** The three row actions every list uses, so View, Edit and Delete look and behave the same on every screen. */
export type RowActionKind = 'view' | 'edit' | 'delete';

const TONES: Record<RowActionKind, string> = {
  view: 'bg-sky-600 shadow-sky-600/40 hover:bg-sky-500 focus-visible:ring-sky-400',
  edit: 'bg-amber-500 shadow-amber-500/40 hover:bg-amber-400 focus-visible:ring-amber-300',
  delete: 'bg-red-600 shadow-red-600/40 hover:bg-red-500 focus-visible:ring-red-400',
};

const TIPS: Record<RowActionKind, string> = { view: 'View', edit: 'Edit', delete: 'Delete' };

const svg = (children: ReactNode) => <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>;
const ICONS: Record<RowActionKind, ReactNode> = {
  view: svg(<><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></>),
  edit: svg(<><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" /></>),
  delete: svg(<><path d="M3 6h18" /><path d="M8 6V4h8v2" /><path d="M19 6l-1 14H6L5 6" /><path d="M10 11v6M14 11v6" /></>),
};

/**
 * One highlighted icon button. The tooltip is the plain action word; pass `label` (for example "Edit Acme")
 * so screen readers know which row it belongs to.
 */
export function RowAction({ kind, label, onClick, disabled }: { kind: RowActionKind; label?: string; onClick: () => void; disabled?: boolean }) {
  return (
    <motion.button type="button" title={TIPS[kind]} aria-label={label ?? TIPS[kind]} disabled={disabled}
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      whileHover={disabled ? undefined : { scale: 1.15, y: -2 }} whileTap={disabled ? undefined : { scale: 0.9 }}
      transition={{ type: 'spring', stiffness: 400, damping: 18 }}
      className={`inline-flex h-8 w-8 items-center justify-center rounded-md text-white shadow-md focus:outline-none focus-visible:ring-2 disabled:cursor-not-allowed disabled:opacity-40 ${TONES[kind]}`}>
      {ICONS[kind]}
    </motion.button>
  );
}

/** A row of actions. Pass only the handlers that apply; a missing one hides that button. */
export function RowActions({ name, onView, onEdit, onDelete }: { name?: string; onView?: () => void; onEdit?: () => void; onDelete?: () => void }) {
  const label = (kind: RowActionKind) => (name ? `${TIPS[kind]} ${name}` : undefined);
  return (
    <span className="flex items-center gap-2">
      {onView && <RowAction kind="view" label={label('view')} onClick={onView} />}
      {onEdit && <RowAction kind="edit" label={label('edit')} onClick={onEdit} />}
      {onDelete && <RowAction kind="delete" label={label('delete')} onClick={onDelete} />}
    </span>
  );
}
