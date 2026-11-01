import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { ArrowRight, BookOpen, Compass, Keyboard, MessageSquareText, RotateCcw, Search, X } from 'lucide-react';
import { GLOSSARY, guideFor } from './content';
import { useGuide } from './context';

const Kbd = ({ children }: { children: React.ReactNode }) => <kbd className="rounded border border-limestone-300 bg-limestone-50 px-1.5 py-0.5 text-xs font-semibold text-graphite-700">{children}</kbd>;

/**
 * Help for the page the person is on, in plain words: what it is for, how to do the common jobs, a tour, and what the words mean.
 * Opens with the ? key or the Help button in the top bar.
 */
export function HelpDrawer({ open, onClose, pathname, aiEnabled }: { open: boolean; onClose: () => void; pathname: string; aiEnabled: boolean }) {
  const { startPageTour, startAppTour, resetGuide } = useGuide();
  const nav = useNavigate();
  const reduce = useReducedMotion();
  const [q, setQ] = useState('');
  const close = useRef<HTMLButtonElement>(null);
  const guide = guideFor(pathname);
  const heading = typeof document !== 'undefined' ? document.querySelector('[data-tour="page-title"] h1')?.textContent?.trim() : undefined;

  useEffect(() => {
    if (!open) return;
    setQ('');
    const t = setTimeout(() => close.current?.focus(), 50);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); onClose(); } };
    window.addEventListener('keydown', onKey);
    return () => { clearTimeout(t); window.removeEventListener('keydown', onKey); };
  }, [open, onClose]);

  const needle = q.trim().toLowerCase();
  const tasks = useMemo(() => (guide?.tasks ?? []).filter((t) => !needle || `${t.label} ${t.hint}`.toLowerCase().includes(needle)), [guide, needle]);
  const terms = useMemo(() => GLOSSARY.filter((g) => needle ? `${g.term} ${g.meaning}`.toLowerCase().includes(needle) : false), [needle]);

  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[85]">
          <motion.div className="absolute inset-0 bg-graphite-950/30" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.aside role="dialog" aria-modal="true" aria-label="Help" className="absolute inset-y-0 right-0 flex w-full max-w-[420px] flex-col bg-white shadow-2xl"
            initial={reduce ? false : { x: 440 }} animate={{ x: 0 }} exit={reduce ? undefined : { x: 440 }} transition={{ type: 'spring', stiffness: 380, damping: 38 }}>
            <header className="flex items-center justify-between border-b border-limestone-200 px-5 py-4">
              <div className="flex items-center gap-2 text-base font-extrabold text-graphite-950"><BookOpen className="h-5 w-5 text-federal-700" aria-hidden />Help</div>
              <button ref={close} type="button" onClick={onClose} aria-label="Close help" className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100 hover:text-graphite-900"><X className="h-5 w-5" /></button>
            </header>
            <div className="scrollbar-thin flex-1 space-y-6 overflow-y-auto px-5 py-5">
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-graphite-400" aria-hidden />
                <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search help, for example “deposit”" aria-label="Search help"
                  className="h-10 w-full rounded border border-limestone-300 bg-limestone-50 pl-9 pr-3 text-sm focus:border-federal-700 focus:bg-white focus:outline-none focus:ring-2 focus:ring-federal-700/15" />
              </div>

              {!needle && (
                <section>
                  <h2 className="text-xs font-semibold uppercase tracking-wide text-graphite-500">On this page</h2>
                  <p className="mt-1 text-lg font-extrabold normal-case tracking-normal text-graphite-950">{guide?.title ?? heading ?? 'This page'}</p>
                  <p className="mt-1 text-[15px] leading-relaxed text-graphite-700">{guide?.summary ?? 'Use the title, buttons and table on this page to work with this part of the product. Take the tour for a quick look at what is here.'}</p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <button type="button" onClick={startPageTour} className="inline-flex h-9 items-center gap-2 rounded bg-federal-800 px-3.5 text-sm font-bold text-white hover:bg-federal-900"><Compass className="h-4 w-4" />Tour this page</button>
                    <button type="button" onClick={startAppTour} className="inline-flex h-9 items-center rounded border border-limestone-300 bg-white px-3.5 text-sm font-semibold text-graphite-800 hover:bg-limestone-50">Tour of the whole product</button>
                  </div>
                </section>
              )}

              {(tasks.length > 0 || !needle) && (
                <section>
                  <h2 className="text-xs font-semibold uppercase tracking-wide text-graphite-500">How do I…</h2>
                  {tasks.length === 0 ? <p className="mt-2 text-sm text-graphite-500">We have no step-by-step notes for this page yet. Try the tour, or ask the assistant.</p> : (
                    <ul className="mt-2 divide-y divide-limestone-200 rounded-lg border border-limestone-200">
                      {tasks.map((t) => (
                        <li key={t.label} className="px-4 py-3">
                          <p className="text-sm font-bold text-graphite-900">{t.label}</p>
                          <p className="mt-0.5 text-sm text-graphite-600">{t.hint}</p>
                          {t.to && <button type="button" onClick={() => { onClose(); nav(t.to!); }} className="mt-1.5 inline-flex items-center gap-1 text-sm font-semibold text-federal-800 hover:underline">Take me there<ArrowRight className="h-3.5 w-3.5" /></button>}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}

              {needle && (
                <section>
                  <h2 className="text-xs font-semibold uppercase tracking-wide text-graphite-500">What the words mean</h2>
                  {terms.length === 0 && tasks.length === 0 ? <p className="mt-2 text-sm text-graphite-500">Nothing matches “{q.trim()}”. Try a simpler word, or ask the assistant.</p> : (
                    <dl className="mt-2 space-y-3">{terms.map((g) => <div key={g.term}><dt className="text-sm font-bold text-graphite-900">{g.term}</dt><dd className="text-sm text-graphite-600">{g.meaning}</dd></div>)}</dl>
                  )}
                </section>
              )}

              {!needle && (
                <section>
                  <h2 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-graphite-500"><Keyboard className="h-3.5 w-3.5" aria-hidden />Shortcuts</h2>
                  <ul className="mt-2 space-y-2 text-sm text-graphite-700">
                    <li className="flex items-center justify-between gap-3"><span>Search for anything</span><span><Kbd>Ctrl</Kbd> + <Kbd>K</Kbd></span></li>
                    <li className="flex items-center justify-between gap-3"><span>Open this help</span><Kbd>?</Kbd></li>
                    <li className="flex items-center justify-between gap-3"><span>Close a panel or dialog</span><Kbd>Esc</Kbd></li>
                    <li className="flex items-center justify-between gap-3"><span>Move through a tour</span><span><Kbd>←</Kbd> <Kbd>→</Kbd></span></li>
                  </ul>
                </section>
              )}
            </div>
            <footer className="space-y-2 border-t border-limestone-200 bg-limestone-50 px-5 py-3">
              {aiEnabled && <button type="button" onClick={() => { onClose(); window.dispatchEvent(new Event('pos:open-assistant')); }} className="flex w-full items-center justify-center gap-2 rounded border border-limestone-300 bg-white py-2 text-sm font-semibold text-graphite-800 hover:bg-limestone-50"><MessageSquareText className="h-4 w-4" />Ask the assistant in your own words</button>}
              <button type="button" onClick={() => { resetGuide(); onClose(); }} className="flex w-full items-center justify-center gap-1.5 py-1 text-xs text-graphite-500 hover:text-graphite-800 hover:underline"><RotateCcw className="h-3 w-3" />Show me the tours again</button>
            </footer>
          </motion.aside>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
