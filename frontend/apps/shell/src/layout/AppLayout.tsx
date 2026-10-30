import { useEffect, useState } from 'react';
import { NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { UserCircle, Bell, Menu, LogOut, RefreshCw, MessageSquareText, X, ChevronDown, ChevronRight, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import clsx from 'clsx';
import { api, setToken, useSession, personaOf, Avatar, Spinner, useApi, fmt, toast, QuickViewHost } from '@propertyos/ui';
import { useQueryClient } from '@tanstack/react-query';
import { Logo } from './Logo';
import { visibleNav, breadcrumbs, isBlockedPage, type NavItem } from './nav';
import { Assistant } from './Assistant';
import { ErrorBoundary } from './ErrorBoundary';

/** Every visible menu entry, refreshed on each render of the layout. Used to decide which entry is the most specific match for a path. */
let navPool: NavItem[] = [];

const PERSONA_LABEL: Record<string, string> = {
  platform: 'Platform admin', admin: 'Org admin', manager: 'Property manager', broker: 'Broker', agent: 'Leasing agent',
  accountant: 'Accountant', owner: 'Owner', tenant: 'Resident', vendor: 'Vendor',
};

export function AppLayout() {
  const { session, loading, token, can, feature } = useSession();
  const loc = useLocation();
  const [mobileNav, setMobileNav] = useState(false);
  const [assistant, setAssistant] = useState(false);
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem('pos.navCollapsed') === '1'; } catch { return false; } });
  const toggleCollapsed = () => setCollapsed((c) => { const n = !c; try { localStorage.setItem('pos.navCollapsed', n ? '1' : '0'); } catch { /* storage unavailable */ } return n; });
  useEffect(() => setMobileNav(false), [loc.pathname]);

  if (!token) return <Navigate to="/signin" replace />;
  if (loading || !session) {
    return <div className="flex h-screen items-center justify-center gap-3 text-graphite-500"><Spinner /> Loading your workspace…</div>;
  }

  const persona = personaOf(session);
  const groups = visibleNav(session, persona, can, feature);
  navPool = groups.flatMap((g) => g.items.flatMap((i) => i.children ?? [i]));
  const aiEnabled = feature('AI_ASSISTANT') && can('AI_ASSISTANT:VIEW') && persona !== 'platform';

  const renderSidebar = (slim: boolean) => (
    <nav className="flex h-full flex-col bg-federal-900 text-federal-100" aria-label="Main">
      <div className={clsx('flex h-16 items-center', slim ? 'justify-center' : 'justify-between pl-5 pr-3')}>
        {slim ? <Logo light compact /> : <Logo light />}
        {!slim && <button onClick={toggleCollapsed} aria-label="Collapse menu" title="Collapse menu" className="hidden rounded p-1.5 text-federal-300 hover:bg-federal-800 hover:text-white lg:block"><PanelLeftClose className="h-[18px] w-[18px]" /></button>}
      </div>
      {slim && <button onClick={toggleCollapsed} aria-label="Expand menu" title="Expand menu" className="mx-auto mb-2 rounded p-1.5 text-federal-300 hover:bg-federal-800 hover:text-white"><PanelLeftOpen className="h-[18px] w-[18px]" /></button>}
      {!slim && <div className="mx-3 mb-3 rounded bg-federal-950/50 px-3 py-2.5">
        <div className="truncate text-sm font-bold text-white">{persona === 'platform' ? session.organization.name : (session.branding?.companyName || session.organization.name)}</div>
        <div className="mt-0.5 flex items-center gap-1.5 text-2xs text-federal-300">
          {persona !== 'platform' && session.organization.plan && <><span>{session.organization.plan} plan</span><span aria-hidden>·</span></>}<span>{PERSONA_LABEL[persona]}</span>
        </div>
      </div>}
      <div className={clsx('scrollbar-thin flex-1 overflow-y-auto pb-4', slim ? 'px-2' : 'px-3')}>
        {groups.map((g, gi) => (
          <div key={gi} className="mt-3 first:mt-0">
            {g.label && !slim && <div className="px-3 pb-1 pt-2 text-2xs font-semibold text-federal-300">{g.label}</div>}
            {g.label && slim && gi > 0 && <div className="mx-3 mb-2 mt-1 border-t border-white/10" />}
            {g.items.map((i) => <NavEntry key={i.label + i.to} item={i} slim={slim} pathname={loc.pathname} />)}
          </div>
        ))}
      </div>
      {!slim && <div className="border-t border-white/10 px-5 py-3 text-2xs text-federal-300">Data scope: {fmt.title(session.scope)}{session.scope === 'PROPERTY' ? ` (${session.propertyIds.length} properties)` : ''}</div>}
    </nav>
  );

  return (
    <div className="flex h-screen overflow-hidden">
      <aside className={clsx('hidden shrink-0 transition-[width] duration-200 lg:block', collapsed ? 'w-[68px]' : 'w-[248px]')}>{renderSidebar(collapsed)}</aside>
      <AnimatePresence>
        {mobileNav && (
          <div className="fixed inset-0 z-40 lg:hidden">
            <motion.div className="absolute inset-0 bg-graphite-950/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setMobileNav(false)} />
            <motion.aside className="absolute inset-y-0 left-0 w-[260px]" initial={{ x: -260 }} animate={{ x: 0 }} exit={{ x: -260 }} transition={{ type: 'spring', stiffness: 400, damping: 40 }}>{renderSidebar(false)}</motion.aside>
          </div>
        )}
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center gap-3 border-b border-limestone-200 bg-white px-4 lg:px-8">
          <button className="rounded p-2 text-graphite-600 hover:bg-limestone-100 lg:hidden" aria-label="Open navigation" onClick={() => setMobileNav(true)}><Menu className="h-5 w-5" /></button>
          <div className="min-w-0 flex-1 truncate text-sm text-graphite-500">{session.organization.name}</div>
          <Notifications />
          <UserMenu name={session.name} email={session.email} photo={session.photo} subtitle={session.roles.join(', ')} />
        </header>
        <main className="scrollbar-thin flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1360px] px-4 py-6 lg:px-8 lg:py-8">
            <Breadcrumbs pathname={loc.pathname} groups={groups} />
            <SectionTabs pathname={loc.pathname} groups={groups} />
            <ErrorBoundary label="page" key={loc.pathname}>{isBlockedPage(loc.pathname, session, persona, can, feature) ? <NoAccess /> : <Outlet />}</ErrorBoundary>
          </div>
        </main>
      </div>
      <ErrorBoundary label="quick view" compact><QuickViewHost /></ErrorBoundary>
      {aiEnabled && !assistant && (
        <button onClick={() => setAssistant(true)} aria-label="Ask PropertyOS" title="Chat"
          style={{ bottom: 'max(1rem, env(safe-area-inset-bottom))', right: 'max(1rem, env(safe-area-inset-right))' }}
          className="fixed z-30 inline-flex h-12 w-12 items-center justify-center rounded-full bg-federal-800 text-white shadow-[0_8px_24px_rgba(20,50,120,0.4)] ring-2 ring-white transition hover:bg-federal-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slateblue-600">
          <MessageSquareText className="h-5 w-5" />
        </button>
      )}
      {aiEnabled && <ErrorBoundary label="assistant" compact><Assistant open={assistant} onClose={() => setAssistant(false)} persona={persona} /></ErrorBoundary>}
    </div>
  );
}

function Breadcrumbs({ pathname, groups }: { pathname: string; groups: ReturnType<typeof visibleNav> }) {
  const nav = useNavigate();
  const crumbs = breadcrumbs(pathname, groups);
  if (crumbs.length === 0) return null;
  return (
    <nav aria-label="Breadcrumb" className="mb-4 -mt-2">
      <ol className="flex flex-wrap items-center gap-1 text-[13px] text-graphite-500">
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <li key={i} className="flex items-center gap-1">
              {i > 0 && <ChevronRight className="h-3.5 w-3.5 text-graphite-400" aria-hidden />}
              {last || !c.to
                ? <span aria-current={last ? 'page' : undefined} className={last ? 'font-semibold text-graphite-800' : ''}>{c.label}</span>
                : <a href={c.to} onClick={(e) => { e.preventDefault(); nav(c.to!); }} className="rounded px-0.5 hover:text-federal-800 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600">{c.label}</a>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * Tabs for the section the person is in. They come from the same menu definition as the left menu, so the tabs on a page
 * always match the submenu it belongs to (and clicking one never jumps to a different menu).
 */
function SectionTabs({ pathname, groups }: { pathname: string; groups: ReturnType<typeof visibleNav> }) {
  const parent = groups.flatMap((g) => g.items).find((i) => i.children?.some((c) => childActive(c, i.children!, pathname)));
  if (!parent?.children || parent.children.length < 2) return null;
  const kids = parent.children;
  return (
    <nav aria-label={`${parent.label} sections`} className="-mx-4 mb-5 overflow-x-auto px-4 lg:mx-0 lg:px-0">
      <ul className="flex min-w-max gap-1 border-b border-limestone-200">
        {kids.map((c) => {
          const on = childActive(c, kids, pathname);
          return (
            <li key={c.label + c.to}>
              <NavLink to={c.to} aria-current={on ? 'page' : 'false'}
                className={clsx('-mb-px inline-flex items-center gap-2 whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-semibold', on ? 'border-federal-900 text-federal-900' : 'border-transparent text-graphite-500 hover:text-graphite-800')}>
                {c.label}
              </NavLink>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

const isExactRoot = (to: string) => to === '/app' || to === '/app/leasing' || to === '/app/broker';

/** A submenu entry is active on its own path or below it, unless a more specific sibling matches. */
function childActive(c: NavItem, siblings: NavItem[], pathname: string) {
  const hit = (to: string) => pathname === to || pathname.startsWith(to + '/');
  if (!hit(c.to)) return false;
  // Compare against every visible menu entry, not just this section's, so /app/leasing/units lights up Portfolio and not Leasing as well.
  const pool = navPool.length ? navPool : siblings;
  return !pool.some((s) => s.to !== c.to && s.to.startsWith(c.to + '/') && hit(s.to));
}

function NavEntry({ item, slim, pathname }: { item: NavItem; slim: boolean; pathname: string }) {
  const kids = item.children;
  const active = !!kids && kids.some((c) => childActive(c, kids, pathname));
  const [open, setOpen] = useState(active);
  const [fly, setFly] = useState<number | null>(null);
  useEffect(() => { if (active) setOpen(true); }, [active]);

  const linkCls = (isActive: boolean, sub = false) => clsx('group relative mb-0.5 flex items-center gap-3 rounded text-[14px] font-medium transition-colors',
    sub ? 'py-1.5 pl-3 pr-2 text-[13px]' : 'py-2', !sub && (slim ? 'justify-center px-0' : 'px-3'),
    isActive ? 'bg-federal-800 text-white' : 'text-federal-100/85 hover:bg-federal-800/60 hover:text-white');

  if (!kids) {
    return (
      <NavLink to={item.to} title={slim ? item.label : undefined} aria-label={slim ? item.label : undefined} end={isExactRoot(item.to)} className={({ isActive }) => linkCls(isActive)}>
        {({ isActive }) => (
          <>
            {isActive && <motion.span layoutId="nav-active" className="absolute inset-y-1.5 left-0 w-[3px] rounded-r bg-brick-500" />}
            <item.icon className="h-[18px] w-[18px] shrink-0" strokeWidth={isActive ? 2.25 : 1.75} />
            {!slim && item.label}
          </>
        )}
      </NavLink>
    );
  }

  const parentCls = clsx('relative mb-0.5 flex w-full items-center gap-3 rounded py-2 text-left text-[14px] font-medium transition-colors', slim ? 'justify-center px-0' : 'px-3',
    active ? 'text-white' : 'text-federal-100/85 hover:bg-federal-800/60 hover:text-white', active && slim && 'bg-federal-800');

  if (slim) {
    return (
      <div onMouseLeave={() => setFly(null)} onMouseEnter={(e) => setFly(e.currentTarget.getBoundingClientRect().top)}>
        <NavLink to={kids[0].to} aria-label={item.label} aria-haspopup="menu" aria-expanded={fly !== null} className={parentCls} onFocus={(e) => setFly(e.currentTarget.getBoundingClientRect().top)}>
          {active && <span className="absolute inset-y-1.5 left-0 w-[3px] rounded-r bg-brick-500" />}
          <item.icon className="h-[18px] w-[18px] shrink-0" strokeWidth={active ? 2.25 : 1.75} />
        </NavLink>
        {fly !== null && (
          <div role="menu" style={{ top: Math.max(8, fly - 4) }} className="fixed left-[64px] z-50 w-60 rounded-md border border-limestone-200 bg-white p-1.5 pl-3 shadow-drawer">
            <div className="px-2 pb-1 pt-1.5 text-xs font-semibold text-graphite-500">{item.label}</div>
            {kids.map((c) => {
              const on = childActive(c, kids, pathname);
              return (
                <NavLink key={c.label + c.to} to={c.to} role="menuitem" aria-current={on ? 'page' : 'false'} onClick={() => setFly(null)}
                  className={clsx('flex items-center gap-2.5 rounded px-2.5 py-2 text-sm font-medium', on ? 'bg-federal-50 text-federal-900' : 'text-graphite-800 hover:bg-limestone-100')}>
                  <c.icon className="h-4 w-4 shrink-0 text-graphite-500" />{c.label}
                </NavLink>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  return (
    <div>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className={parentCls}>
        {active && <span className="absolute inset-y-1.5 left-0 w-[3px] rounded-r bg-brick-500" />}
        <item.icon className="h-[18px] w-[18px] shrink-0" strokeWidth={active ? 2.25 : 1.75} />
        <span className="flex-1">{item.label}</span>
        <ChevronDown className={clsx('h-4 w-4 text-federal-300 transition-transform', !open && '-rotate-90')} />
      </button>
      {open && (
        <div className="mb-1 ml-[21px] border-l border-white/10 pl-2">
          {kids.map((c) => {
            const on = childActive(c, kids, pathname);
            return (
              <NavLink key={c.label + c.to} to={c.to} aria-current={on ? 'page' : 'false'} className={() => linkCls(on, true)}>
                <c.icon className="h-4 w-4 shrink-0" strokeWidth={on ? 2.25 : 1.75} />{c.label}
              </NavLink>
            );
          })}
        </div>
      )}
    </div>
  );
}

function UserMenu({ name, email, photo, subtitle }: { name: string; email?: string; photo?: string | null; subtitle: string }) {
  const [open, setOpen] = useState(false);
  const nav = useNavigate();
  const qc = useQueryClient();
  const refresh = async () => {
    const r = await api.post<{ token: string }>('/api/identity/refresh');
    setToken(r.token);
    qc.invalidateQueries();
    toast('Session refreshed with your latest permissions');
    setOpen(false);
  };
  return (
    <div className="relative">
      <button onClick={() => setOpen(!open)} className="flex items-center gap-2 rounded px-1.5 py-1 hover:bg-limestone-100" aria-expanded={open}>
        <Avatar name={name} size={34} seed={email} src={photo} ring />
        <span className="hidden text-left md:block">
          <span className="block text-sm font-semibold leading-tight text-graphite-900">{name}</span>
          <span className="block max-w-[160px] truncate text-2xs text-graphite-500">{subtitle}</span>
        </span>
        <ChevronDown className="hidden h-4 w-4 text-graphite-400 md:block" />
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-40 mt-2 w-56 rounded-md border border-limestone-200 bg-white py-1 shadow-lift">
            <button onClick={() => { setOpen(false); nav('/app/profile'); }} className="flex w-full items-center gap-2 px-3 py-2 text-sm text-graphite-700 hover:bg-limestone-100"><UserCircle className="h-4 w-4" /> My profile</button>
            <button onClick={() => { setOpen(false); nav('/app/preferences'); }} className="flex w-full items-center gap-2 px-3 py-2 text-sm text-graphite-700 hover:bg-limestone-100"><Bell className="h-4 w-4" /> Notification preferences</button>
            <button onClick={refresh} className="flex w-full items-center gap-2 px-3 py-2 text-sm text-graphite-700 hover:bg-limestone-100"><RefreshCw className="h-4 w-4" /> Refresh permissions</button>
            <button onClick={() => { setToken(null); qc.clear(); nav('/signin'); }} className="flex w-full items-center gap-2 px-3 py-2 text-sm text-graphite-700 hover:bg-limestone-100"><LogOut className="h-4 w-4" /> Sign out</button>
          </div>
        </>
      )}
    </div>
  );
}

interface Notification { id: string; title: string; body?: string; link?: string; priority: string; read: boolean; created_at: string; channel?: string }

function Notifications() {
  const [open, setOpen] = useState(false);
  const nav = useNavigate();
  const q = useApi<{ items: Notification[]; unread: number }>('/api/workflow/notifications', { refetchInterval: 20_000 });
  const items = q.data?.items ?? [];
  const unread = q.data?.unread ?? items.filter((n) => !n.read).length;
  const markAll = async () => {
    await api.post('/api/workflow/notifications/read-all');
    q.refetch();
  };
  return (
    <div className="relative">
      <button onClick={() => setOpen(!open)} className="relative rounded p-2 text-graphite-600 hover:bg-limestone-100" aria-label={`Notifications${unread ? `, ${unread} unread` : ''}`}>
        <Bell className="h-5 w-5" />
        {unread > 0 && <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-brick-600 px-1 text-[10px] font-bold text-white">{unread > 9 ? '9+' : unread}</span>}
      </button>
      <AnimatePresence>
        {open && (
          <>
            <div className="fixed inset-0 z-30" onClick={() => setOpen(false)} />
            <motion.div initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
              className="absolute right-0 z-40 mt-2 w-[380px] max-w-[calc(100vw-2rem)] rounded-md border border-limestone-200 bg-white shadow-lift">
              <div className="flex items-center justify-between border-b border-limestone-200 px-4 py-3">
                <span className="text-sm font-bold">Notifications</span>
                {unread > 0 && <button onClick={markAll} className="text-xs font-semibold text-federal-800 hover:underline">Mark all as read</button>}
              </div>
              <ul className="scrollbar-thin max-h-[420px] overflow-y-auto">
                {items.length === 0 && <li className="px-4 py-8 text-center text-sm text-graphite-500">You’re all caught up.</li>}
                {items.slice(0, 30).map((n) => (
                  <li key={n.id}>
                    <button onClick={() => { setOpen(false); api.post(`/api/workflow/notifications/${n.id}/read`).then(() => q.refetch()); if (n.link) nav(n.link.startsWith('/app') ? n.link : '/app' + n.link); }}
                      className={clsx('flex w-full gap-3 border-b border-limestone-100 px-4 py-3 text-left hover:bg-limestone-50', !n.read && 'bg-federal-50/50')}>
                      <span className={clsx('mt-1.5 h-2 w-2 shrink-0 rounded-full', n.read ? 'bg-transparent' : n.priority === 'HIGH' ? 'bg-brick-600' : 'bg-federal-700')} />
                      <span className="min-w-0">
                        <span className="block text-sm font-semibold text-graphite-900">{n.title}</span>
                        {n.body && <span className="mt-0.5 block text-[13px] text-graphite-600">{n.body}</span>}
                        <span className="mt-1 block text-2xs text-graphite-400">{fmt.relative(n.created_at)}</span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </motion.div>
          </>
        )}
      </AnimatePresence>
    </div>
  );
}

export function CloseButton({ onClick }: { onClick: () => void }) {
  return <button onClick={onClick} aria-label="Close" className="rounded p-1.5 text-graphite-500 hover:bg-limestone-100"><X className="h-5 w-5" /></button>;
}

/** Shown instead of a page the person's role can't use. The server refuses the data anyway; this just says so plainly. */
function NoAccess() {
  return (
    <div className="mx-auto mt-16 max-w-md text-center" role="alert">
      <h1 className="text-xl font-extrabold text-graphite-950">You don’t have access to this page</h1>
      <p className="mt-2 text-graphite-600">Your role doesn’t include it. If you need it, ask your company administrator to change your role.</p>
      <a href="/app" className="mt-4 inline-block font-semibold text-federal-900 underline">Go to your home page</a>
    </div>
  );
}
