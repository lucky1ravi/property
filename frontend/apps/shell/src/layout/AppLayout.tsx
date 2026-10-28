import { useEffect, useState } from 'react';
import { NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Bell, Menu, LogOut, RefreshCw, MessageSquareText, X, ChevronDown } from 'lucide-react';
import clsx from 'clsx';
import { api, setToken, useSession, personaOf, Avatar, Spinner, useApi, fmt, toast } from '@propertyos/ui';
import { useQueryClient } from '@tanstack/react-query';
import { Logo } from './Logo';
import { visibleNav } from './nav';
import { Assistant } from './Assistant';

const PERSONA_LABEL: Record<string, string> = {
  platform: 'Platform admin', admin: 'Org admin', manager: 'Property manager', broker: 'Broker', agent: 'Leasing agent',
  accountant: 'Accountant', owner: 'Owner', tenant: 'Resident', vendor: 'Vendor',
};

export function AppLayout() {
  const { session, loading, token, can, feature } = useSession();
  const loc = useLocation();
  const [mobileNav, setMobileNav] = useState(false);
  const [assistant, setAssistant] = useState(false);
  useEffect(() => setMobileNav(false), [loc.pathname]);

  if (!token) return <Navigate to="/signin" replace />;
  if (loading || !session) {
    return <div className="flex h-screen items-center justify-center gap-3 text-graphite-500"><Spinner /> Loading your workspace…</div>;
  }

  const persona = personaOf(session);
  const groups = visibleNav(session, persona, can, feature);
  const aiEnabled = feature('AI_ASSISTANT') && can('AI_ASSISTANT:VIEW') && persona !== 'platform';

  const sidebar = (
    <nav className="flex h-full flex-col bg-federal-900 text-federal-100" aria-label="Main">
      <div className="flex h-16 items-center px-5"><Logo light /></div>
      <div className="mx-3 mb-3 rounded bg-federal-950/50 px-3 py-2.5">
        <div className="truncate text-sm font-bold text-white">{session.branding?.companyName ?? session.organization.name}</div>
        <div className="mt-0.5 flex items-center gap-1.5 text-2xs text-federal-300">
          <span>{session.organization.plan} plan</span><span aria-hidden>·</span><span>{PERSONA_LABEL[persona]}</span>
        </div>
      </div>
      <div className="scrollbar-thin flex-1 overflow-y-auto px-3 pb-4">
        {groups.map((g, gi) => (
          <div key={gi} className="mt-3 first:mt-0">
            {g.label && <div className="px-3 pb-1 pt-2 text-2xs font-semibold text-federal-300">{g.label}</div>}
            {g.items.map((i) => (
              <NavLink key={i.to} to={i.to} end={i.to === '/app' || i.to === '/app/leasing' || i.to === '/app/broker'}
                className={({ isActive }) => clsx('group relative mb-0.5 flex items-center gap-3 rounded px-3 py-2 text-[14px] font-medium transition-colors',
                  isActive ? 'bg-federal-800 text-white' : 'text-federal-100/85 hover:bg-federal-800/60 hover:text-white')}>
                {({ isActive }) => (
                  <>
                    {isActive && <motion.span layoutId="nav-active" className="absolute inset-y-1.5 left-0 w-[3px] rounded-r bg-brick-500" />}
                    <i.icon className="h-[18px] w-[18px] shrink-0" strokeWidth={isActive ? 2.25 : 1.75} />
                    {i.label}
                  </>
                )}
              </NavLink>
            ))}
          </div>
        ))}
      </div>
      <div className="border-t border-white/10 px-5 py-3 text-2xs text-federal-300">Data scope: {fmt.title(session.scope)}{session.scope === 'PROPERTY' ? ` (${session.propertyIds.length} properties)` : ''}</div>
    </nav>
  );

  return (
    <div className="flex h-screen overflow-hidden">
      <aside className="hidden w-[248px] shrink-0 lg:block">{sidebar}</aside>
      <AnimatePresence>
        {mobileNav && (
          <div className="fixed inset-0 z-40 lg:hidden">
            <motion.div className="absolute inset-0 bg-graphite-950/40" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setMobileNav(false)} />
            <motion.aside className="absolute inset-y-0 left-0 w-[260px]" initial={{ x: -260 }} animate={{ x: 0 }} exit={{ x: -260 }} transition={{ type: 'spring', stiffness: 400, damping: 40 }}>{sidebar}</motion.aside>
          </div>
        )}
      </AnimatePresence>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center gap-3 border-b border-limestone-200 bg-white px-4 lg:px-8">
          <button className="rounded p-2 text-graphite-600 hover:bg-limestone-100 lg:hidden" aria-label="Open navigation" onClick={() => setMobileNav(true)}><Menu className="h-5 w-5" /></button>
          <div className="min-w-0 flex-1 truncate text-sm text-graphite-500">{session.organization.name}</div>
          {aiEnabled && (
            <button onClick={() => setAssistant(true)} className="inline-flex h-9 items-center gap-2 rounded border border-federal-100 bg-federal-50 px-3 text-sm font-semibold text-federal-900 hover:bg-federal-100">
              <MessageSquareText className="h-4 w-4" /> <span className="hidden sm:inline">Ask PropertyOS</span>
            </button>
          )}
          <Notifications />
          <UserMenu name={session.name} subtitle={session.roles.join(', ')} />
        </header>
        <main className="scrollbar-thin flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[1360px] px-4 py-6 lg:px-8 lg:py-8">
            <Outlet />
          </div>
        </main>
      </div>
      {aiEnabled && <Assistant open={assistant} onClose={() => setAssistant(false)} persona={persona} />}
    </div>
  );
}

function UserMenu({ name, subtitle }: { name: string; subtitle: string }) {
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
        <Avatar name={name} size={32} />
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
  const q = useApi<Notification[]>('/api/workflow/notifications', { refetchInterval: 20_000 });
  const unread = (q.data ?? []).filter((n) => !n.read).length;
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
                {(q.data ?? []).length === 0 && <li className="px-4 py-8 text-center text-sm text-graphite-500">You’re all caught up.</li>}
                {(q.data ?? []).slice(0, 30).map((n) => (
                  <li key={n.id}>
                    <button onClick={() => { setOpen(false); api.post(`/api/workflow/notifications/${n.id}/read`).then(() => q.refetch()); if (n.link) nav(n.link); }}
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
