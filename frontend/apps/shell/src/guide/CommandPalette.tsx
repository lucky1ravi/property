import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { Building2, CircleHelp, Compass, CornerDownLeft, FileText, History, Receipt, Search, UserRound, Wrench, DoorOpen, Landmark, Truck, type LucideIcon } from 'lucide-react';
import clsx from 'clsx';
import { api, openQuickView, useSession, type QuickKind } from '@propertyos/ui';
import type { NavGroup } from '../layout/nav';
import { useGuide } from './context';

interface Row { id: string; group: string; title: string; sub?: string; icon: LucideIcon; run: () => void }

/** Records that can be found by typing. Each is searched on the server, a few at a time, and only if this person may see it. */
const ENTITIES: { group: string; perm: string; ep: string; icon: LucideIcon; title: (r: any) => string; sub: (r: any) => string; quick?: QuickKind; to?: (r: any) => string }[] = [
  { group: 'Properties', perm: 'PROPERTY:VIEW', ep: '/api/leasing/properties', icon: Building2, title: (r) => r.name, sub: (r) => [r.street, r.city, r.state].filter(Boolean).join(', '), quick: 'property' },
  { group: 'Units', perm: 'PROPERTY:VIEW', ep: '/api/leasing/units', icon: DoorOpen, title: (r) => `${r.property_name} · Unit ${r.number}`, sub: (r) => String(r.status ?? '').toLowerCase(), quick: 'unit' },
  { group: 'Owners', perm: 'OWNER:VIEW', ep: '/api/leasing/owners', icon: Landmark, title: (r) => r.name, sub: (r) => [r.contact, r.email].filter(Boolean).join(' · '), quick: 'owner' },
  { group: 'Leases and residents', perm: 'LEASE:VIEW', ep: '/api/leasing/leases', icon: FileText, title: (r) => r.tenant_name, sub: (r) => `${r.property_name ?? ''} ${r.unit_label ?? ''}`.trim(), to: (r) => `/app/leasing/leases/${r.id}` },
  { group: 'Work orders', perm: 'WORK_ORDER:VIEW', ep: '/api/maintenance/work-orders', icon: Wrench, title: (r) => r.title, sub: (r) => [r.property_name, r.status && String(r.status).toLowerCase()].filter(Boolean).join(' · '), quick: 'workorder' },
  { group: 'Vendors', perm: 'VENDOR:VIEW', ep: '/api/maintenance/vendors', icon: Truck, title: (r) => r.name, sub: (r) => (r.trades ?? []).join(', '), quick: 'vendor' },
  { group: 'Invoices', perm: 'INVOICE:VIEW', ep: '/api/billing/invoices', icon: Receipt, title: (r) => r.description ?? 'Invoice', sub: (r) => [r.tenant_name, r.unit_label].filter(Boolean).join(' · '), quick: 'invoice' },
  { group: 'People with a sign-in', perm: 'USER:VIEW', ep: '/api/identity/users', icon: UserRound, title: (r) => r.name, sub: (r) => r.email, to: () => '/app/admin/access' },
];

const recentKey = (userId?: string) => `pos.recent.${userId ?? 'anon'}`;
export function readRecent(userId?: string): { to: string; label: string }[] {
  try { return JSON.parse(localStorage.getItem(recentKey(userId)) ?? '[]'); } catch { return []; }
}
export function writeRecent(userId: string | undefined, to: string, label: string) {
  try {
    const next = [{ to, label }, ...readRecent(userId).filter((r) => r.to !== to)].slice(0, 6);
    localStorage.setItem(recentKey(userId), JSON.stringify(next));
  } catch { /* storage unavailable */ }
}

/**
 * Search for anything: pages, actions, and the people and records you are allowed to see. Opens with Ctrl/⌘ K.
 * Records are searched by the server a few at a time, so it stays fast however large the company is.
 */
export function CommandPalette({ open, onClose, groups }: { open: boolean; onClose: () => void; groups: NavGroup[] }) {
  const { can, session } = useSession();
  const { startPageTour, startAppTour, openHelp } = useGuide();
  const nav = useNavigate();
  const reduce = useReducedMotion();
  const [q, setQ] = useState('');
  const [active, setActive] = useState(0);
  const [found, setFound] = useState<Row[]>([]);
  const [searching, setSearching] = useState(false);
  const seq = useRef(0);
  const list = useRef<HTMLUListElement>(null);
  const go = (to: string) => { onClose(); nav(to); };

  const pages = useMemo(() => groups.flatMap((g) => g.items.flatMap((i) => (i.children ? i.children.map((c) => ({ ...c, section: i.label })) : [{ ...i, section: g.label ?? '' }]))), [groups]);
  const pageRows = (needle: string, skip?: Set<string>): Row[] => pages
    .filter((p) => (!needle || `${p.label} ${p.section}`.toLowerCase().includes(needle)) && !skip?.has(p.to))
    .slice(0, needle ? 6 : 5)
    .map((p) => ({ id: 'p:' + p.to + p.label, group: 'Pages', title: p.label, sub: p.section || undefined, icon: p.icon, run: () => go(p.to) }));

  const actions: Row[] = [
    { id: 'a:tour', group: 'Help', title: 'Take a tour of this page', icon: Compass, run: () => { onClose(); setTimeout(startPageTour, 150); } },
    { id: 'a:app', group: 'Help', title: 'Take the tour of the whole product', icon: Compass, run: () => { onClose(); setTimeout(startAppTour, 150); } },
    { id: 'a:help', group: 'Help', title: 'Open help for this page', icon: CircleHelp, run: () => { onClose(); setTimeout(openHelp, 150); } },
    { id: 'a:profile', group: 'Your account', title: 'Your profile and photo', icon: UserRound, run: () => go('/app/profile') },
    { id: 'a:prefs', group: 'Your account', title: 'Notification preferences', icon: UserRound, run: () => go('/app/preferences') },
  ];

  useEffect(() => { if (open) { setQ(''); setActive(0); setFound([]); } }, [open]);

  // Search records on the server once typing pauses; ignore answers that arrive after a newer search.
  useEffect(() => {
    const needle = q.trim();
    setFound([]);
    if (!open || needle.length < 2) { setSearching(false); return; }
    setSearching(true);
    const mine = ++seq.current;
    const t = setTimeout(async () => {
      const kinds = ENTITIES.filter((e) => can(e.perm));
      const results = await Promise.allSettled(kinds.map((e) => api.get<any>(`${e.ep}?page=1&size=4&q=${encodeURIComponent(needle)}`)));
      if (mine !== seq.current) return;
      const rows: Row[] = [];
      results.forEach((r, k) => {
        if (r.status !== 'fulfilled') return;
        const e = kinds[k];
        const data = r.value;
        const items: any[] = Array.isArray(data) ? data : data?.rows ?? data?.items ?? [];
        for (const it of items.slice(0, 4)) {
          rows.push({
            id: `${e.group}:${it.id}`, group: e.group, title: e.title(it), sub: e.sub(it) || undefined, icon: e.icon,
            run: () => { onClose(); if (e.quick) openQuickView({ kind: e.quick, id: String(it.id), name: e.title(it) }); else if (e.to) nav(e.to(it)); },
          });
        }
      });
      setFound(rows);
      setSearching(false);
    }, 250);
    return () => clearTimeout(t);
  }, [q, open]); // eslint-disable-line react-hooks/exhaustive-deps

  const needle = q.trim().toLowerCase();
  const rows: Row[] = useMemo(() => {
    if (!needle) {
      const recentList = readRecent(session?.userId);
      const recent = recentList.map((r): Row => ({ id: 'r:' + r.to, group: 'Recent', title: r.label, icon: History, run: () => go(r.to) }));
      return [...recent, ...pageRows('', new Set(recentList.map((r) => r.to))), ...actions.slice(0, 3)];
    }
    return [...pageRows(needle), ...found, ...actions.filter((a) => a.title.toLowerCase().includes(needle))];
  }, [needle, found, pages, session?.userId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => setActive(0), [needle, found.length]);
  useEffect(() => { list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }); }, [active]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, rows.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); rows[active]?.run(); }
    else if (e.key === 'Escape') { e.preventDefault(); onClose(); }
  };

  // Rows come in groups; draw a heading whenever the group changes.
  let lastGroup = '';
  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[88] flex items-start justify-center px-4 pt-[12vh]" role="presentation">
          <motion.div className="absolute inset-0 bg-graphite-950/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
          <motion.div role="dialog" aria-modal="true" aria-label="Search" onKeyDown={onKeyDown}
            initial={reduce ? false : { opacity: 0, y: -12, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={reduce ? undefined : { opacity: 0, y: -8 }} transition={{ duration: 0.15 }}
            className="relative w-full max-w-[620px] overflow-hidden rounded-xl bg-white shadow-2xl ring-1 ring-black/5">
            <div className="flex items-center gap-3 border-b border-limestone-200 px-4">
              <Search className="h-5 w-5 shrink-0 text-graphite-400" aria-hidden />
              <input autoFocus value={q} onChange={(e) => setQ(e.target.value)} role="combobox" aria-expanded="true" aria-controls="palette-list" aria-activedescendant={rows[active] ? `pal-${active}` : undefined}
                placeholder="Search pages, people, properties, leases…" aria-label="Search" className="h-14 w-full bg-transparent text-base outline-none placeholder:text-graphite-400" />
              <kbd className="hidden rounded border border-limestone-300 bg-limestone-50 px-1.5 py-0.5 text-xs text-graphite-500 sm:block">Esc</kbd>
            </div>
            <ul id="palette-list" ref={list} role="listbox" className="max-h-[52vh] overflow-y-auto py-2">
              {rows.map((r, i) => {
                const head = r.group !== lastGroup;
                lastGroup = r.group;
                return (
                  <li key={r.id} role="presentation">
                    {head && <div className="px-4 pb-1 pt-3 text-xs font-semibold uppercase tracking-wide text-graphite-500">{r.group}</div>}
                    <div id={`pal-${i}`} role="option" aria-selected={i === active} onMouseEnter={() => setActive(i)} onClick={r.run}
                      className={clsx('mx-2 flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5', i === active ? 'bg-federal-50' : '')}>
                      <span className={clsx('inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md', i === active ? 'bg-federal-800 text-white' : 'bg-limestone-100 text-graphite-600')}><r.icon className="h-4 w-4" aria-hidden /></span>
                      <span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold text-graphite-900">{r.title}</span>{r.sub && <span className="block truncate text-xs text-graphite-500">{r.sub}</span>}</span>
                      {i === active && <CornerDownLeft className="h-4 w-4 shrink-0 text-graphite-400" aria-hidden />}
                    </div>
                  </li>
                );
              })}
              {rows.length === 0 && !searching && <li className="px-4 py-8 text-center text-sm text-graphite-500">Nothing matches “{q.trim()}”. Try a name, an address or a page like “invoices”.</li>}
              {searching && <li className="px-4 py-3 text-sm text-graphite-500" aria-live="polite">Searching…</li>}
            </ul>
            <div className="flex items-center justify-between gap-3 border-t border-limestone-200 bg-limestone-50 px-4 py-2 text-xs text-graphite-500">
              <span>{needle.length > 0 && needle.length < 2 ? 'Keep typing to search records' : 'Type at least 2 letters to search records'}</span>
              <span className="hidden sm:inline">↑↓ to move · Enter to open · Esc to close</span>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
