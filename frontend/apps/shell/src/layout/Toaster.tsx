import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { CheckCircle2, AlertCircle, Info, X } from 'lucide-react';
import type { ToastMessage } from '@propertyos/ui';

/** Renders toasts dispatched by any micro-frontend. */
export function Toaster() {
  const [items, setItems] = useState<ToastMessage[]>([]);
  useEffect(() => {
    const on = (e: Event) => {
      const t = (e as CustomEvent<ToastMessage>).detail;
      setItems((xs) => [...xs.slice(-3), t]);
      setTimeout(() => setItems((xs) => xs.filter((x) => x.id !== t.id)), t.tone === 'error' ? 7000 : 4000);
    };
    window.addEventListener('pos:toast', on);
    return () => window.removeEventListener('pos:toast', on);
  }, []);
  return (
    <div className="pointer-events-none fixed bottom-4 left-1/2 z-[60] flex w-full max-w-md -translate-x-1/2 flex-col gap-2 px-4" aria-live="polite">
      <AnimatePresence>
        {items.map((t) => (
          <motion.div key={t.id} layout initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 8 }}
            className="pointer-events-auto flex items-start gap-3 rounded-md bg-graphite-950 px-4 py-3 text-white shadow-lift">
            {t.tone === 'success' && <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-good-100" />}
            {t.tone === 'error' && <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-crit-100" />}
            {t.tone === 'info' && <Info className="mt-0.5 h-4 w-4 shrink-0 text-slateblue-200" />}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold">{t.title}</p>
              {t.body && <p className="mt-0.5 text-[13px] text-limestone-300">{t.body}</p>}
            </div>
            <button aria-label="Dismiss" onClick={() => setItems((xs) => xs.filter((x) => x.id !== t.id))} className="text-limestone-400 hover:text-white"><X className="h-4 w-4" /></button>
          </motion.div>
        ))}
      </AnimatePresence>
    </div>
  );
}
