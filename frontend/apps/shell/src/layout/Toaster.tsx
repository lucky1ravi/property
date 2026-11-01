import { useCallback, useEffect, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { AlertTriangle, CheckCircle2, Copy, Info, X, XOctagon } from 'lucide-react';
import clsx from 'clsx';
import type { ToastMessage, ToastTone } from '@propertyos/ui';

/**
 * Messages appear at the top centre of the screen, coloured by what they mean:
 * green for done, yellow for "this needs your attention" (a rule, a missing detail, no access), red for "something broke",
 * blue for information. They go away on their own (red ones stay longest), pause while the pointer is over them,
 * and can be closed. A red message carries a reference to quote to support.
 */
const LOOK: Record<ToastTone, { box: string; icon: typeof Info; iconCls: string; title: string; bar: string; seconds: number; role: 'status' | 'alert' }> = {
  success: { box: 'border-good-600 bg-good-100', icon: CheckCircle2, iconCls: 'text-good-600', title: 'text-good-700', bar: 'bg-good-600', seconds: 5, role: 'status' },
  warning: { box: 'border-warn-600 bg-warn-100', icon: AlertTriangle, iconCls: 'text-warn-600', title: 'text-warn-700', bar: 'bg-warn-600', seconds: 9, role: 'alert' },
  error: { box: 'border-crit-600 bg-crit-100', icon: XOctagon, iconCls: 'text-crit-600', title: 'text-crit-700', bar: 'bg-crit-600', seconds: 14, role: 'alert' },
  info: { box: 'border-slateblue-600 bg-slateblue-100', icon: Info, iconCls: 'text-slateblue-600', title: 'text-slateblue-700', bar: 'bg-slateblue-600', seconds: 6, role: 'status' },
};

export function Toaster() {
  const [items, setItems] = useState<ToastMessage[]>([]);
  const reduce = useReducedMotion();
  const dismiss = useCallback((id: string) => setItems((xs) => xs.filter((x) => x.id !== id)), []);
  useEffect(() => {
    const on = (e: Event) => {
      const t = (e as CustomEvent<ToastMessage>).detail;
      // The same message twice in a row is one message, not a stack of them.
      setItems((xs) => [...xs.filter((x) => !(x.title === t.title && x.body === t.body)).slice(-3), t]);
    };
    window.addEventListener('pos:toast', on);
    return () => window.removeEventListener('pos:toast', on);
  }, []);

  return (
    <div className="pointer-events-none fixed left-1/2 top-4 z-[100] flex w-full max-w-[34rem] -translate-x-1/2 flex-col gap-2.5 px-4">
      {/* The countdown bar is a CSS animation so that hovering can pause it. */}
      <style>{'@keyframes pos-toast-run{from{transform:scaleX(1)}to{transform:scaleX(0)}}'}</style>
      <AnimatePresence initial={false}>
        {items.map((t) => <Toast key={t.id} t={t} onClose={() => dismiss(t.id)} reduce={!!reduce} />)}
      </AnimatePresence>
    </div>
  );
}

function Toast({ t, onClose, reduce }: { t: ToastMessage; onClose: () => void; reduce: boolean }) {
  const look = LOOK[t.tone] ?? LOOK.info;
  const Icon = look.icon;
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(t.ref ?? ''); setCopied(true); setTimeout(() => setCopied(false), 1600); } catch { /* clipboard unavailable */ }
  };
  return (
    <motion.div layout={!reduce} role={look.role} aria-live={look.role === 'alert' ? 'assertive' : 'polite'}
      initial={reduce ? false : { opacity: 0, y: -20, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={reduce ? { opacity: 0 } : { opacity: 0, y: -12, scale: 0.97 }}
      transition={{ type: 'spring', stiffness: 420, damping: 34 }}
      className={clsx('group pointer-events-auto relative overflow-hidden rounded-lg border-l-[6px] shadow-lift ring-1 ring-black/5', look.box)}>
      <div className="flex items-start gap-3 px-4 py-3.5">
        <Icon className={clsx('mt-0.5 h-5 w-5 shrink-0', look.iconCls)} aria-hidden />
        <div className="min-w-0 flex-1">
          <p className={clsx('text-[15px] font-bold leading-snug', look.title)}>{t.title}</p>
          {t.body && <p className="mt-0.5 text-sm leading-relaxed text-graphite-800">{t.body}</p>}
          {t.ref && (
            <p className="mt-2 flex flex-wrap items-center gap-2 text-xs text-graphite-700">
              <span>Reference <code className="rounded bg-white/70 px-1.5 py-0.5 font-mono text-[11px] font-semibold text-graphite-900">{t.ref}</code></span>
              <button type="button" onClick={copy} className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 font-semibold text-graphite-800 hover:bg-white/70 focus-visible:outline focus-visible:outline-2 focus-visible:outline-graphite-700">
                <Copy className="h-3 w-3" aria-hidden />{copied ? 'Copied' : 'Copy'}
              </button>
            </p>
          )}
        </div>
        <button type="button" aria-label="Dismiss message" onClick={onClose} className="-mr-1 -mt-0.5 rounded p-1 text-graphite-600 hover:bg-white/70 hover:text-graphite-950 focus-visible:outline focus-visible:outline-2 focus-visible:outline-graphite-700"><X className="h-4 w-4" /></button>
      </div>
      {!reduce && (
        <span aria-hidden className={clsx('absolute inset-x-0 bottom-0 h-1 origin-left opacity-70 group-hover:[animation-play-state:paused]', look.bar)}
          style={{ animation: `pos-toast-run ${look.seconds}s linear forwards` }} onAnimationEnd={onClose} />
      )}
      {reduce && <AutoClose seconds={look.seconds} onClose={onClose} />}
    </motion.div>
  );
}

/** For people who prefer reduced motion there is no moving bar, so close the message on a timer instead. */
function AutoClose({ seconds, onClose }: { seconds: number; onClose: () => void }) {
  useEffect(() => { const h = setTimeout(onClose, seconds * 1000); return () => clearTimeout(h); }, [seconds, onClose]);
  return null;
}
