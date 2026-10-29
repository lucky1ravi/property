import { type ReactNode, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { X } from 'lucide-react';
import clsx from 'clsx';
import { Button, Field, Textarea } from './primitives';

function useEscape(open: boolean, onClose: () => void) {
  useEffect(() => {
    if (!open) return;
    const h = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [open, onClose]);
}

/** Right-hand detail panel: the record opens beside the list so context is kept. */
export function Drawer({ open, onClose, title, subtitle, children, footer, width = 640 }: {
  open: boolean; onClose: () => void; title: ReactNode; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode; width?: number;
}) {
  useEscape(open, onClose);
  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-40" role="dialog" aria-modal="true">
          <motion.div className="absolute inset-0 bg-graphite-950/30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.aside
            className="absolute inset-y-0 right-0 flex w-full flex-col bg-limestone-50 shadow-drawer"
            style={{ maxWidth: width }}
            initial={{ x: '100%' }} animate={{ x: 0 }} exit={{ x: '100%' }}
            transition={{ type: 'spring', stiffness: 420, damping: 40 }}
          >
            <header className="flex items-start justify-between gap-4 border-b border-limestone-200 bg-white px-6 py-4">
              <div className="min-w-0">
                <h2 className="truncate text-lg font-extrabold tracking-[-0.01em] text-graphite-950">{title}</h2>
                {subtitle && <div className="mt-0.5 text-sm text-graphite-500">{subtitle}</div>}
              </div>
              <button onClick={onClose} aria-label="Close" className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100 hover:text-graphite-900"><X className="h-5 w-5" /></button>
            </header>
            <div className="flex-1 overflow-y-auto px-6 py-5">{children}</div>
            {footer && <footer className="flex flex-wrap items-center justify-end gap-2 border-t border-limestone-200 bg-white px-6 py-3">{footer}</footer>}
          </motion.aside>
        </div>
      )}
    </AnimatePresence>
  );
}

/**
 * Dialog with a title, body and optional footer. Closes on Escape and when the backdrop is clicked.
 */
export function Modal({ open, onClose, title, children, footer, size = 'md' }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; size?: 'sm' | 'md' | 'lg' }) {
  useEscape(open, onClose);
  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true">
          <motion.div className="absolute inset-0 bg-graphite-950/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div
            className={clsx('relative w-full rounded-lg bg-white shadow-lift', size === 'sm' && 'max-w-md', size === 'md' && 'max-w-lg', size === 'lg' && 'max-w-3xl')}
            initial={{ opacity: 0, scale: 0.97, y: 8 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.98 }}
            transition={{ duration: 0.16 }}
          >
            <header className="flex items-center justify-between border-b border-limestone-200 px-5 py-3.5">
              <h2 className="text-base font-bold text-graphite-950">{title}</h2>
              <button onClick={onClose} aria-label="Close" className="rounded p-1 text-graphite-500 hover:bg-limestone-100"><X className="h-4 w-4" /></button>
            </header>
            <div className="max-h-[70vh] overflow-y-auto px-5 py-4">{children}</div>
            {footer && <footer className="flex justify-end gap-2 border-t border-limestone-200 px-5 py-3">{footer}</footer>}
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}

/** Confirmation for consequential actions; optionally collects the reason the workflow requires. */
export function ConfirmDialog({ open, title, body, confirmLabel, requireReason, reasonLabel = 'Reason', tone = 'primary', loading, onConfirm, onClose }: {
  open: boolean; title: string; body?: ReactNode; confirmLabel: string; requireReason?: boolean; reasonLabel?: string;
  tone?: 'primary' | 'accent' | 'danger'; loading?: boolean; onConfirm: (reason: string) => void; onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (open) {
      setReason('');
      setTimeout(() => ref.current?.focus(), 50);
    }
  }, [open]);
  return (
    <Modal open={open} onClose={onClose} title={title} size="sm"
      footer={<>
        <Button variant="quiet" onClick={onClose}>Cancel</Button>
        <Button variant={tone === 'danger' ? 'danger' : tone} loading={loading} disabled={requireReason && !reason.trim()} onClick={() => onConfirm(reason.trim())}>{confirmLabel}</Button>
      </>}>
      {body && <div className="text-sm text-graphite-700">{body}</div>}
      {requireReason && (
        <Field label={reasonLabel} className="mt-3" required hint="Recorded in the audit trail.">
          <Textarea ref={ref} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Field>
      )}
    </Modal>
  );
}
