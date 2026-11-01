import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { ArrowLeft, ArrowRight, X } from 'lucide-react';
import clsx from 'clsx';
import type { Step } from './content';

interface Box { left: number; top: number; width: number; height: number }
const PAD = 8;
const CARD_W = 360;

/** The element a step points at, if it is on the page and visible. */
export function findTarget(selector?: string): HTMLElement | null {
  if (!selector) return null;
  // The same anchor can appear twice (a wide and a compact version of one button); use the one that is actually showing.
  for (const el of document.querySelectorAll<HTMLElement>(selector)) {
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden') return el;
  }
  return null;
}

/**
 * A guided tour: dims the page, lights up one thing at a time and explains it next to it.
 * Keyboard: arrows to move, Esc to leave. Works without a mouse, respects reduced motion, and on a phone the card sits at the bottom.
 */
export function TourOverlay({ steps, label, onFinish, onSkip }: { steps: Step[]; label: string; onFinish: () => void; onSkip: () => void }) {
  const [i, setI] = useState(0);
  const [box, setBox] = useState<Box | null>(null);
  const [vw, setVw] = useState({ w: window.innerWidth, h: window.innerHeight });
  const card = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();
  const step = steps[i];
  const last = i === steps.length - 1;

  // Remember where the person was, so closing the tour puts them back.
  const returnTo = useRef<Element | null>(document.activeElement);
  useEffect(() => () => { (returnTo.current as HTMLElement | null)?.focus?.(); }, []);

  // Follow the target: scroll it into view, then keep measuring while the page scrolls or resizes.
  useLayoutEffect(() => {
    const el = findTarget(step?.target);
    if (!el) { setBox(null); return; }
    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: reduce ? 'auto' : 'smooth' });
    let raf = 0;
    const measure = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        const r = el.getBoundingClientRect();
        setBox({ left: r.left - PAD, top: r.top - PAD, width: r.width + PAD * 2, height: r.height + PAD * 2 });
        setVw({ w: window.innerWidth, h: window.innerHeight });
      });
    };
    measure();
    const settle = window.setTimeout(measure, 380); // after the smooth scroll lands
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    return () => { cancelAnimationFrame(raf); window.clearTimeout(settle); window.removeEventListener('resize', measure); window.removeEventListener('scroll', measure, true); };
  }, [i, step?.target, reduce]);

  useEffect(() => { card.current?.focus({ preventScroll: true }); }, [i]);

  const next = () => (last ? onFinish() : setI(i + 1));
  const back = () => setI(Math.max(0, i - 1));
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onSkip(); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); next(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); back(); }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  });

  if (!step) return null;
  const phone = vw.w < 640;
  const place = (): React.CSSProperties => {
    if (phone) return { left: 12, right: 12, bottom: 12 };
    if (!box) return { left: Math.max(12, (vw.w - CARD_W) / 2), top: Math.max(12, vw.h / 2 - 140) };
    const below = vw.h - (box.top + box.height);
    const above = box.top;
    const rightRoom = vw.w - (box.left + box.width);
    const clampX = (x: number) => Math.min(Math.max(12, x), vw.w - CARD_W - 12);
    if (below >= 230) return { left: clampX(box.left), top: box.top + box.height + 12 };
    if (above >= 230) return { left: clampX(box.left), bottom: vw.h - box.top + 12 };
    if (rightRoom >= CARD_W + 24) return { left: box.left + box.width + 12, top: Math.min(Math.max(12, box.top), vw.h - 260) };
    return { left: clampX(box.left - CARD_W - 12), top: Math.min(Math.max(12, box.top), vw.h - 260) };
  };
  const t = reduce ? { duration: 0 } : { type: 'spring' as const, stiffness: 320, damping: 34 };

  return createPortal(
    <div className="fixed inset-0 z-[90]" role="presentation">
      {/* Blocks clicks on the page while the tour is open. */}
      <div className="absolute inset-0" onClick={(e) => e.stopPropagation()} />
      {box ? (
        <motion.div aria-hidden className="pointer-events-none absolute rounded-lg ring-2 ring-white/90"
          style={{ boxShadow: '0 0 0 9999px rgba(15, 23, 42, 0.62)' }}
          initial={false} animate={{ left: box.left, top: box.top, width: box.width, height: box.height }} transition={t} />
      ) : (
        <div aria-hidden className="pointer-events-none absolute inset-0 bg-graphite-950/60" />
      )}
      <motion.div key={i} ref={card} tabIndex={-1} role="dialog" aria-modal="true" aria-label={`${label}: step ${i + 1} of ${steps.length}. ${step.title}`}
        initial={reduce ? false : { opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={reduce ? { duration: 0 } : { duration: 0.18 }}
        style={{ position: 'fixed', width: phone ? undefined : CARD_W, ...place() }}
        className="rounded-xl bg-white p-5 shadow-2xl outline-none ring-1 ring-black/5">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-xs font-semibold uppercase tracking-wide text-federal-700">{label} · {i + 1} of {steps.length}</span>
          <button type="button" onClick={onSkip} aria-label="Leave the tour" className="-mr-1 rounded p-1 text-graphite-400 hover:bg-limestone-100 hover:text-graphite-800"><X className="h-4 w-4" /></button>
        </div>
        <h2 className="text-lg font-extrabold normal-case leading-snug tracking-normal text-graphite-950">{step.title}</h2>
        <p className="mt-1.5 text-[15px] leading-relaxed text-graphite-700">{step.body}</p>
        <div className="mt-4 flex items-center justify-between gap-3">
          <div className="flex gap-1.5" aria-hidden>
            {steps.map((_, k) => <span key={k} className={clsx('h-1.5 rounded-full transition-all', k === i ? 'w-5 bg-federal-700' : k < i ? 'w-1.5 bg-federal-300' : 'w-1.5 bg-limestone-300')} />)}
          </div>
          <div className="flex items-center gap-2">
            {i > 0 && <button type="button" onClick={back} className="inline-flex h-9 items-center gap-1 rounded px-3 text-sm font-semibold text-graphite-700 hover:bg-limestone-100"><ArrowLeft className="h-4 w-4" />Back</button>}
            <button type="button" onClick={next} autoFocus className="inline-flex h-9 items-center gap-1.5 rounded bg-federal-800 px-4 text-sm font-bold text-white shadow-sm hover:bg-federal-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-federal-700">
              {last ? 'Done' : 'Next'}{!last && <ArrowRight className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <p className="mt-3 text-xs text-graphite-500">{phone ? 'Tap Next to continue.' : '← → to move · Esc to leave'}</p>
      </motion.div>
      <span className="sr-only" aria-live="polite">{`Step ${i + 1} of ${steps.length}: ${step.title}`}</span>
    </div>,
    document.body,
  );
}
