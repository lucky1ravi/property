import type { LucideIcon } from 'lucide-react';
import {
  Home, Gauge, Building2, KeyRound, Receipt, Wrench, BarChart3, Workflow, Settings2, Users, ScrollText, Globe2, LayoutTemplate, Layers3, SlidersHorizontal, FileSignature, CalendarCheck, HandCoins, Sparkles,
  MessagesSquare, CalendarClock, Landmark, DoorOpen, UserRound, ClipboardList, Truck, Repeat, Inbox, CreditCard, Banknote, FileText, ListChecks, Briefcase, LayoutDashboard, Megaphone, FolderOpen, Camera,
} from 'lucide-react';
import type { Session, Persona } from '@propertyos/ui';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  perm?: string;
  feature?: string;
  personas?: Persona[];
  exclude?: Persona[];
  /** Submenu. The parent opens its first visible child when clicked while the sidebar is collapsed. */
  children?: NavItem[];
}

export interface NavGroup {
  label?: string;
  items: NavItem[];
}

const STAFF: Persona[] = ['admin', 'manager', 'broker', 'agent', 'accountant'];

export const NAV: NavGroup[] = [
  {
    items: [
      { to: '/app', label: 'Home', icon: Home },
      { to: '/app/messages', label: 'Messages', icon: MessagesSquare, perm: 'CONVERSATION:VIEW', feature: 'MESSAGING' },
      { to: '/app/broker', label: 'Broker cockpit', icon: Gauge, perm: 'OPPORTUNITY:VIEW', feature: 'BROKER_COCKPIT', personas: ['admin', 'manager', 'broker'] },
    ],
  },
  {
    label: 'Assets',
    items: [
      {
        to: '/app/leasing/properties', label: 'Portfolio', icon: Building2, perm: 'PROPERTY:VIEW', exclude: ['tenant', 'vendor'],
        children: [
          { to: '/app/leasing/properties', label: 'Properties', icon: Building2, perm: 'PROPERTY:VIEW', exclude: ['tenant', 'vendor'] },
          { to: '/app/leasing/units', label: 'Units', icon: DoorOpen, perm: 'PROPERTY:VIEW', exclude: ['tenant', 'vendor'] },
          { to: '/app/leasing/owners', label: 'Owners', icon: Landmark, perm: 'OWNER:VIEW', exclude: ['tenant', 'vendor', 'owner'] },
        ],
      },
      { to: '/app/leasing/my-lease', label: 'My lease', icon: FileSignature, personas: ['tenant'] },
    ],
  },
  {
    label: 'Operations',
    items: [
      {
        to: '/app/leasing', label: 'Leasing', icon: KeyRound, perm: 'LISTING:VIEW', feature: 'LEASING', personas: STAFF,
        children: [
          { to: '/app/leasing', label: 'Overview', icon: LayoutDashboard, perm: 'LISTING:VIEW', personas: STAFF },
          { to: '/app/leasing/listings', label: 'Listings', icon: Megaphone, perm: 'LISTING:VIEW', personas: STAFF },
          { to: '/app/leasing/leads', label: 'Leads', icon: Inbox, perm: 'LISTING:VIEW', personas: STAFF },
          { to: '/app/leasing/showings', label: 'Showings', icon: CalendarCheck, perm: 'SHOWING:VIEW', personas: STAFF },
          { to: '/app/leasing/applications', label: 'Applications', icon: ClipboardList, perm: 'APPLICATION:VIEW', personas: STAFF },
          { to: '/app/leasing/leases', label: 'Leases', icon: FileText, perm: 'LEASE:VIEW', personas: STAFF },
        ],
      },
      {
        to: '/app/payments', label: 'Payments', icon: Receipt, perm: 'INVOICE:VIEW', feature: 'PAYMENTS', exclude: ['vendor'],
        children: [
          { to: '/app/payments', label: 'Overview', icon: LayoutDashboard, perm: 'INVOICE:VIEW', exclude: ['vendor'] },
          { to: '/app/payments/invoices', label: 'Invoices', icon: Receipt, perm: 'INVOICE:VIEW', exclude: ['vendor'] },
          { to: '/app/payments/collections', label: 'Collections', icon: HandCoins, perm: 'INVOICE:EDIT' },
          { to: '/app/payments/payments', label: 'Payments received', icon: CreditCard, perm: 'PAYMENT:VIEW' },
          { to: '/app/payments/statements', label: 'Owner statements', icon: FileText, perm: 'OWNER_STATEMENT:VIEW' },
          { to: '/app/payments/deposits', label: 'Deposits', icon: Banknote, perm: 'PAYMENT:VIEW' },
          { to: '/app/payments/tax', label: 'Tax and compliance', icon: ScrollText, perm: 'TAX_REPORT:VIEW', exclude: ['tenant', 'vendor'] },
        ],
      },
      {
        to: '/app/maintenance', label: 'Maintenance', icon: Wrench, perm: 'WORK_ORDER:VIEW', feature: 'MAINTENANCE',
        children: [
          { to: '/app/maintenance', label: 'Work orders', icon: ListChecks, perm: 'WORK_ORDER:VIEW' },
          { to: '/app/maintenance/vendors', label: 'Vendors', icon: Truck, perm: 'VENDOR:VIEW', exclude: ['tenant'] },
          { to: '/app/maintenance/recurring', label: 'Preventive maintenance', icon: Repeat, perm: 'WORK_ORDER:VIEW', exclude: ['tenant'] },
        ],
      },
      {
        to: '/app/documents', label: 'Files and documents', icon: FolderOpen, perm: 'DOCUMENT:VIEW', feature: 'DOCUMENTS',
        children: [
          { to: '/app/documents', label: 'All files', icon: FolderOpen, perm: 'DOCUMENT:VIEW' },
          { to: '/app/documents/photos', label: 'Photos and review', icon: Camera, perm: 'MEDIA:UPLOAD' },
          { to: '/app/documents/signatures', label: 'E-signatures', icon: FileSignature, perm: 'ESIGN:VIEW', feature: 'ESIGN' },
        ],
      },
    ],
  },
  {
    label: 'Insights',
    items: [
      { to: '/app/insights/ai', label: 'AI tools', icon: Sparkles, perm: 'AI_ASSISTANT:VIEW', feature: 'AI_ASSISTANT', personas: STAFF },
      { to: '/app/insights', label: 'Reports', icon: BarChart3, perm: 'REPORT:VIEW', exclude: ['tenant', 'vendor'] },
      { to: '/app/broker/opportunities', label: 'Opportunities', icon: Briefcase, perm: 'OPPORTUNITY:VIEW', feature: 'OPPORTUNITIES', personas: ['admin', 'manager', 'broker', 'owner'] },
    ],
  },
  {
    label: 'Automation',
    items: [
      { to: '/app/deadlines', label: 'Deadlines and alerts', icon: CalendarClock, perm: 'ALERT:VIEW', exclude: ['tenant', 'vendor', 'owner'] },
      { to: '/app/admin/automations', label: 'Automation rules', icon: Sparkles, perm: 'AUTOMATION:VIEW', feature: 'AUTOMATIONS' },
      { to: '/app/admin/workflows', label: 'Workflow designer', icon: Workflow, perm: 'WORKFLOW:VIEW' },
    ],
  },
  {
    label: 'Administration',
    items: [
      {
        to: '/app/admin/access', label: 'Administration', icon: Settings2, perm: 'USER:VIEW',
        children: [
          { to: '/app/admin/access', label: 'Users and roles', icon: Users, perm: 'USER:VIEW' },
          { to: '/app/admin/configuration', label: 'Configuration', icon: Settings2, perm: 'SETTINGS:VIEW', exclude: ['platform'] },
          { to: '/app/admin/audit', label: 'Audit log', icon: ScrollText, perm: 'AUDIT:VIEW' },
        ],
      },
      { to: '/app/admin/platform', label: 'Platform', icon: Globe2, perm: 'PLATFORM:VIEW', personas: ['platform'] },
      { to: '/app/admin/defaults', label: 'Product defaults', icon: SlidersHorizontal, perm: 'PLATFORM:VIEW', personas: ['platform'] },
      { to: '/app/admin/plans', label: 'Plans', icon: Layers3, perm: 'PLATFORM:VIEW', personas: ['platform'] },
      { to: '/app/admin/website', label: 'Website', icon: LayoutTemplate, perm: 'PLATFORM:VIEW', personas: ['platform'] },
    ],
  },
];

function allowed(i: NavItem, persona: Persona, can: (p: string) => boolean, feature: (f: string) => boolean): boolean {
  if (i.personas && !i.personas.includes(persona)) return false;
  if (i.exclude && i.exclude.includes(persona)) return false;
  if (persona === 'platform') return !i.perm || ['PLATFORM:VIEW', 'SETTINGS:VIEW', 'AUDIT:VIEW'].includes(i.perm);
  if (i.perm && !can(i.perm)) return false;
  if (i.feature && !feature(i.feature)) return false;
  return true;
}

export function visibleNav(s: Session | undefined, persona: Persona, can: (p: string) => boolean, feature: (f: string) => boolean): NavGroup[] {
  if (!s) return [];
  return NAV.map((g) => ({
    ...g,
    items: g.items.flatMap((i): NavItem[] => {
      if (!i.children) return allowed(i, persona, can, feature) ? [i] : [];
      // A submenu item inherits the parent's feature flag; its own permission and persona rules also apply.
      const kids = i.children.filter((c) => allowed({ ...c, feature: c.feature ?? i.feature }, persona, can, feature));
      if (kids.length === 0) return [];
      // One child left: show it as a plain link under the parent's name.
      return kids.length === 1 ? [{ ...kids[0], label: i.label, icon: i.icon }] : [{ ...i, to: kids[0].to, children: kids }];
    }),
  })).filter((g) => g.items.length > 0);
}

export interface Crumb { label: string; to?: string }

/** Breadcrumb trail for a path, built from the menu the person can see: Home › Section › Page › Details. */
export function breadcrumbs(pathname: string, groups: NavGroup[]): Crumb[] {
  if (pathname === '/app' || pathname === '/app/') return [];
  const hit = (to: string) => pathname === to || pathname.startsWith(to + '/');
  let best: { path: string; trail: Crumb[] } | null = null;
  const consider = (path: string, trail: Crumb[]) => {
    if (path === '/app' || !hit(path)) return;
    if (!best || path.length > best.path.length) best = { path, trail };
  };
  for (const g of groups) {
    for (const i of g.items) {
      if (i.children) for (const c of i.children) consider(c.to, [{ label: i.label, to: i.children[0].to }, { label: c.label, to: c.to }]);
      else consider(i.to, [{ label: i.label, to: i.to }]);
    }
  }
  const crumbs: Crumb[] = [{ label: 'Home', to: '/app' }];
  if (!best) {
    const seg = pathname.replace(/^\/app\/?/, '').split('/').filter(Boolean)[0];
    if (seg) crumbs.push({ label: seg.replace(/-/g, ' ').replace(/^./, (c) => c.toUpperCase()) });
    return crumbs;
  }
  const b = best as { path: string; trail: Crumb[] };
  crumbs.push(...b.trail);
  // A page below the matched menu entry is a record view (a listing, lease, work order and so on).
  if (pathname !== b.path) crumbs.push({ label: 'Details' });
  // Parent and page can be the same place (for example Leasing › Overview): keep one of them.
  return crumbs.filter((c, i, a) => i === 0 || !(c.to && c.to === a[i - 1].to && c.label === 'Overview'));
}
