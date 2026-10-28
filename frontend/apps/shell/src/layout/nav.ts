import type { LucideIcon } from 'lucide-react';
import { Home, Gauge, Building2, KeyRound, Receipt, Wrench, BarChart3, Workflow, Settings2, Users, ScrollText, Globe2, FileSignature, CalendarCheck, HandCoins, Sparkles } from 'lucide-react';
import type { Session, Persona } from '@propertyos/ui';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  perm?: string;
  feature?: string;
  personas?: Persona[];
  exclude?: Persona[];
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
      { to: '/app/broker', label: 'Broker cockpit', icon: Gauge, perm: 'OPPORTUNITY:VIEW', feature: 'BROKER_COCKPIT', personas: ['admin', 'manager', 'broker'] },
    ],
  },
  {
    label: 'Operations',
    items: [
      { to: '/app/leasing/properties', label: 'Properties', icon: Building2, perm: 'PROPERTY:VIEW', exclude: ['tenant', 'vendor'] },
      { to: '/app/leasing', label: 'Leasing', icon: KeyRound, perm: 'LISTING:VIEW', feature: 'LEASING', personas: STAFF },
      { to: '/app/leasing/my-lease', label: 'My lease', icon: FileSignature, personas: ['tenant'] },
      { to: '/app/payments', label: 'Payments', icon: Receipt, perm: 'INVOICE:VIEW', feature: 'PAYMENTS', exclude: ['vendor'] },
      { to: '/app/maintenance', label: 'Maintenance', icon: Wrench, perm: 'WORK_ORDER:VIEW', feature: 'MAINTENANCE' },
      { to: '/app/leasing/showings', label: 'Showings', icon: CalendarCheck, perm: 'SHOWING:VIEW', personas: ['broker', 'agent', 'manager'] },
    ],
  },
  {
    label: 'Insights',
    items: [
      { to: '/app/insights', label: 'Reports', icon: BarChart3, perm: 'REPORT:VIEW', exclude: ['tenant', 'vendor'] },
      { to: '/app/broker/opportunities', label: 'Opportunities', icon: HandCoins, perm: 'OPPORTUNITY:VIEW', feature: 'OPPORTUNITIES', personas: ['admin', 'manager', 'broker', 'owner'] },
    ],
  },
  {
    label: 'Automation',
    items: [
      { to: '/app/admin/automations', label: 'Automation rules', icon: Sparkles, perm: 'AUTOMATION:VIEW', feature: 'AUTOMATIONS' },
      { to: '/app/admin/workflows', label: 'Workflow designer', icon: Workflow, perm: 'WORKFLOW:VIEW' },
    ],
  },
  {
    label: 'Administration',
    items: [
      { to: '/app/admin/access', label: 'Users and roles', icon: Users, perm: 'USER:VIEW' },
      { to: '/app/admin/configuration', label: 'Configuration', icon: Settings2, perm: 'SETTINGS:VIEW' },
      { to: '/app/admin/audit', label: 'Audit log', icon: ScrollText, perm: 'AUDIT:VIEW' },
      { to: '/app/admin/platform', label: 'Platform', icon: Globe2, perm: 'PLATFORM:VIEW', personas: ['platform'] },
    ],
  },
];

export function visibleNav(s: Session | undefined, persona: Persona, can: (p: string) => boolean, feature: (f: string) => boolean): NavGroup[] {
  if (!s) return [];
  return NAV.map((g) => ({
    ...g,
    items: g.items.filter((i) => {
      if (i.personas && !i.personas.includes(persona)) return false;
      if (i.exclude && i.exclude.includes(persona)) return false;
      if (persona === 'platform') return !i.perm || ['PLATFORM:VIEW', 'SETTINGS:VIEW', 'AUDIT:VIEW'].includes(i.perm);
      if (i.perm && !can(i.perm)) return false;
      if (i.feature && !feature(i.feature)) return false;
      return true;
    }),
  })).filter((g) => g.items.length > 0);
}
