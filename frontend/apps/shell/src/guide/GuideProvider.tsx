import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { CircleHelp, Compass, Search, Sparkles, X } from 'lucide-react';
import { Button, Modal, useSession } from '@propertyos/ui';
import type { NavGroup } from '../layout/nav';
import { buildAppTour, buildPageTour } from './build';
import { guideFor } from './content';
import { writeRecent } from './CommandPalette';
import { CommandPalette } from './CommandPalette';
import { HelpDrawer } from './HelpDrawer';
import { TourOverlay } from './TourOverlay';
import { GuideContext, type GuideApi } from './context';
import { useGuideState } from './state';
import type { Step } from './content';

interface ActiveTour { label: string; steps: Step[]; kind: 'app' | 'page'; id?: string }

function typingInField(t: EventTarget | null) {
  const el = t as HTMLElement | null;
  return !!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName));
}

/**
 * Teaches people the product as they use it: a welcome tour the first time, an offer of a short tour the first time they open a
 * page, a help panel for the page they are on, and a search box (Ctrl+K) that finds pages, people and records.
 * What they have already seen is saved on their account.
 */
export function GuideProvider({ groups, persona, aiEnabled, children }: { groups: NavGroup[]; persona: string; aiEnabled: boolean; children: ReactNode }) {
  const { session } = useSession();
  const { state, save, ready } = useGuideState();
  const { pathname } = useLocation();
  const [tour, setTour] = useState<ActiveTour | null>(null);
  const [helpOpen, setHelpOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [welcomeOpen, setWelcomeOpen] = useState(false);
  const [coach, setCoach] = useState<string | null>(null);
  const firstName = (session?.name ?? '').split(' ')[0] || 'there';

  const startAppTour = useCallback(() => {
    setHelpOpen(false); setPaletteOpen(false); setWelcomeOpen(false); setCoach(null);
    const steps = buildAppTour(firstName, persona);
    if (steps.length) setTour({ label: 'Welcome tour', steps, kind: 'app' });
  }, [firstName, persona]);

  const startPageTour = useCallback(() => {
    setHelpOpen(false); setPaletteOpen(false); setCoach(null);
    const { guide, steps } = buildPageTour(pathname);
    if (steps.length) setTour({ label: guide ? `${guide.title} tour` : 'Page tour', steps, kind: 'page', id: guide?.id });
  }, [pathname]);

  const closeTour = useCallback(() => {
    if (!tour) return;
    if (tour.kind === 'app') save({ welcome: true, tours: { ...(state.tours ?? {}), __app: Date.now() } });
    else if (tour.id) save({ tours: { ...(state.tours ?? {}), [tour.id]: Date.now() } });
    setTour(null);
  }, [tour, save, state.tours]);

  // First sign-in: offer the welcome tour once the page has had a moment to appear.
  useEffect(() => {
    if (!ready || state.welcome || tour) return;
    const t = setTimeout(() => setWelcomeOpen(true), 900);
    return () => clearTimeout(t);
  }, [ready, state.welcome, tour]);

  // First visit to a page we have notes on: offer a short tour, quietly, in the corner.
  useEffect(() => {
    setCoach(null);
    const g = guideFor(pathname);
    if (!ready || !g || !state.welcome || state.noCoach || state.tours?.[g.id] || tour || helpOpen || paletteOpen || welcomeOpen) return;
    const t = setTimeout(() => setCoach(g.id), 1600);
    return () => clearTimeout(t);
  }, [pathname, ready, state.welcome, state.noCoach, state.tours, tour, helpOpen, paletteOpen, welcomeOpen]);

  // Remember the pages this person visits, for the Recent list in search.
  useEffect(() => {
    const flat = groups.flatMap((g) => g.items.flatMap((i) => (i.children ? i.children : [i])));
    let best: { to: string; label: string } | null = null;
    for (const p of flat) if ((pathname === p.to || pathname.startsWith(p.to + '/')) && (!best || p.to.length > best.to.length)) best = { to: p.to, label: p.label };
    if (best && best.to !== '/app') writeRecent(session?.userId, best.to, best.label);
  }, [pathname, groups, session?.userId]);

  // Keyboard: Ctrl/⌘ K searches, ? opens help.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setHelpOpen(false); setPaletteOpen((o) => !o); return; }
      if (e.key === '?' && !e.ctrlKey && !e.metaKey && !e.altKey && !typingInField(e.target) && !tour) { e.preventDefault(); setPaletteOpen(false); setHelpOpen(true); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [tour]);

  const api = useMemo<GuideApi>(() => ({
    startPageTour, startAppTour,
    openHelp: () => { setPaletteOpen(false); setHelpOpen(true); },
    openPalette: () => { setHelpOpen(false); setPaletteOpen(true); },
    resetGuide: () => save({ welcome: false, tours: {}, noCoach: false, checklistHidden: false }),
  }), [startPageTour, startAppTour, save]);

  const coachGuide = coach ? guideFor(pathname) : undefined;

  return (
    <GuideContext.Provider value={api}>
      {children}
      <Modal open={welcomeOpen} onClose={() => { setWelcomeOpen(false); save({ welcome: true }); }} title={`Welcome to PropertyOS, ${firstName}`}
        footer={<>
          <Button variant="quiet" onClick={() => { setWelcomeOpen(false); save({ welcome: true }); }}>I’ll explore on my own</Button>
          <Button variant="primary" icon={<Compass className="h-4 w-4" />} onClick={startAppTour}>Show me around (1 minute)</Button>
        </>}>
        <div className="space-y-3 text-[15px] text-graphite-700">
          <p>You do not need to know anything about the product to use it. A short tour shows where things are, and every page has a Help button that explains it in plain words.</p>
          <ul className="space-y-2">
            <li className="flex gap-2.5"><Search className="mt-0.5 h-4 w-4 shrink-0 text-federal-700" aria-hidden /><span>Press <kbd className="rounded border border-limestone-300 bg-limestone-50 px-1.5 text-xs font-semibold">Ctrl</kbd> + <kbd className="rounded border border-limestone-300 bg-limestone-50 px-1.5 text-xs font-semibold">K</kbd> anywhere to find a page, person or record.</span></li>
            <li className="flex gap-2.5"><CircleHelp className="mt-0.5 h-4 w-4 shrink-0 text-federal-700" aria-hidden /><span>Press <kbd className="rounded border border-limestone-300 bg-limestone-50 px-1.5 text-xs font-semibold">?</kbd> for help with the page you are on.</span></li>
            {aiEnabled && <li className="flex gap-2.5"><Sparkles className="mt-0.5 h-4 w-4 shrink-0 text-federal-700" aria-hidden /><span>Ask the assistant a question in your own words.</span></li>}
          </ul>
        </div>
      </Modal>

      <AnimatePresence>
        {coachGuide && !tour && (
          <motion.div key={coachGuide.id} role="region" aria-label="Page tour offer" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 16 }} transition={{ duration: 0.2 }}
            className="fixed bottom-4 left-4 z-40 w-[min(22rem,calc(100vw-2rem))] rounded-xl border border-limestone-200 bg-white p-4 shadow-2xl lg:left-[264px]">
            <div className="flex items-start gap-3">
              <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-federal-50 text-federal-800"><Compass className="h-4 w-4" aria-hidden /></span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-graphite-950">New to {coachGuide.title}?</p>
                <p className="mt-0.5 text-sm text-graphite-600">Take a 30-second tour of what is on this page.</p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <Button size="sm" variant="primary" onClick={startPageTour}>Show me</Button>
                  <Button size="sm" variant="quiet" onClick={() => { save({ tours: { ...(state.tours ?? {}), [coachGuide.id]: Date.now() } }); setCoach(null); }}>Not now</Button>
                </div>
                <button type="button" className="mt-2 text-xs text-graphite-500 underline hover:text-graphite-800" onClick={() => { save({ noCoach: true }); setCoach(null); }}>Don’t offer page tours</button>
              </div>
              <button type="button" aria-label="Close" className="-mr-1 -mt-1 rounded p-1 text-graphite-400 hover:bg-limestone-100" onClick={() => { save({ tours: { ...(state.tours ?? {}), [coachGuide.id]: Date.now() } }); setCoach(null); }}><X className="h-4 w-4" /></button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {tour && <TourOverlay key={tour.label + tour.steps.length} steps={tour.steps} label={tour.label} onFinish={closeTour} onSkip={closeTour} />}
      <HelpDrawer open={helpOpen} onClose={() => setHelpOpen(false)} pathname={pathname} aiEnabled={aiEnabled} />
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} groups={groups} />
    </GuideContext.Provider>
  );
}
