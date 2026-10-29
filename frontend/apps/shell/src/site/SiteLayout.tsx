import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { getToken } from '@propertyos/ui';
import { Logo } from '../layout/Logo';

const NAV = [
  { to: '/products', label: 'Platform' },
  { to: '/solutions', label: 'Solutions' },
  { to: '/features', label: 'Features' },
  { to: '/plans', label: 'Plans' },
  { to: '/why-us', label: 'Why us' },
  { to: '/security', label: 'Security' },
];

const link = ({ isActive }: { isActive: boolean }) =>
  `rounded px-3 py-2 text-sm font-semibold transition-colors ${isActive ? 'bg-federal-50 text-federal-900' : 'text-graphite-700 hover:text-federal-900'}`;

export function SiteLayout() {
  const [open, setOpen] = useState(false);
  const { pathname, hash } = useLocation();
  const signedIn = !!getToken();
  useEffect(() => {
    setOpen(false);
    if (!hash) { window.scrollTo(0, 0); return; }
    // Pages render their content after a data call, so give the anchor a moment to exist.
    const t = window.setTimeout(() => document.getElementById(hash.slice(1))?.scrollIntoView({ block: 'start' }), 150);
    return () => window.clearTimeout(t);
  }, [pathname, hash]);

  return (
    <div className="min-h-screen bg-white text-graphite-900">
      <a href="#content" className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded focus:bg-white focus:px-3 focus:py-2">Skip to content</a>
      <header className="sticky top-0 z-40 border-b border-limestone-200 bg-white/95 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between gap-4 px-6">
          <Link to="/" aria-label="PropertyOS home"><Logo /></Link>
          <nav aria-label="Main" className="hidden items-center gap-1 lg:flex">
            {NAV.map((n) => <NavLink key={n.to} to={n.to} className={link}>{n.label}</NavLink>)}
          </nav>
          <div className="hidden items-center gap-2 lg:flex">
            <Link to={signedIn ? '/app' : '/signin'} className="rounded px-3 py-2 text-sm font-semibold text-graphite-800 hover:text-federal-900">{signedIn ? 'Open PropertyOS' : 'Sign in'}</Link>
            <Link to="/contact" className="rounded bg-federal-900 px-4 py-2 text-sm font-semibold text-white hover:bg-federal-800">Request a demo</Link>
          </div>
          <button type="button" className="rounded p-2 text-graphite-800 lg:hidden" aria-expanded={open} aria-controls="mobile-nav" aria-label={open ? 'Close menu' : 'Open menu'} onClick={() => setOpen(!open)}>
            {open ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
          </button>
        </div>
        {open && (
          <nav id="mobile-nav" aria-label="Main" className="border-t border-limestone-200 bg-white px-6 pb-4 pt-2 lg:hidden">
            <ul className="flex flex-col">
              {[...NAV, { to: '/contact', label: 'Contact sales' }].map((n) => <li key={n.to}><NavLink to={n.to} className={({ isActive }) => `block border-b border-limestone-200 py-3 text-base font-semibold ${isActive ? 'text-federal-900' : 'text-graphite-800'}`}>{n.label}</NavLink></li>)}
            </ul>
            <div className="mt-4 grid grid-cols-2 gap-3"><Link to={signedIn ? '/app' : '/signin'} className="rounded border border-federal-900 px-4 py-3 text-center text-sm font-semibold text-federal-900">{signedIn ? 'Open app' : 'Sign in'}</Link><Link to="/contact" className="rounded bg-federal-900 px-4 py-3 text-center text-sm font-semibold text-white">Request a demo</Link></div>
          </nav>
        )}
      </header>

      <main id="content"><Outlet /></main>

      <footer className="bg-federal-950 text-federal-100">
        <div className="mx-auto grid max-w-[1200px] gap-10 px-6 py-16 md:grid-cols-[1.5fr_1fr_1fr_1fr_1fr]">
          <div>
            <Logo light />
            <p className="mt-4 max-w-[32ch] text-sm leading-relaxed text-federal-200">The operating system for property management. Routine work done for you, decisions left to people.</p>
            <Link to="/contact" className="mt-5 inline-block rounded bg-white px-4 py-2.5 text-sm font-bold text-federal-900 hover:bg-federal-50">Request a demo</Link>
          </div>
          <FooterColumn title="Platform" items={[['/products#leasing', 'Leasing'], ['/products#rent', 'Rent and owners'], ['/products#maintenance', 'Maintenance'], ['/products#automation', 'Automation and AI']]} />
          <FooterColumn title="Solutions" items={[['/solutions#brokers', 'Brokers'], ['/solutions#managers', 'Property managers'], ['/solutions#owners', 'Owners'], ['/solutions#accountants', 'Accountants']]} />
          <FooterColumn title="Explore" items={[['/features', 'Features'], ['/plans', 'Plans and pricing'], ['/why-us', 'Why PropertyOS'], ['/security', 'Security']]} />
          <FooterColumn title="Get started" items={[['/contact', 'Contact sales'], ['/signin', 'Sign in'], ['/homes', 'Homes for rent']]} />
        </div>
        <div className="border-t border-federal-800 px-6 py-5 text-center text-xs text-federal-300">© {new Date().getFullYear()} PropertyOS. All rights reserved.</div>
      </footer>
    </div>
  );
}

function FooterColumn({ title, items }: { title: string; items: [string, string][] }) {
  return (
    <div>
      <h2 className="text-sm font-bold text-white">{title}</h2>
      <ul className="mt-3 space-y-2 text-sm">
        {items.map(([to, label]) => <li key={label}><Link className="text-federal-200 hover:text-white hover:underline" to={to}>{label}</Link></li>)}
      </ul>
    </div>
  );
}
