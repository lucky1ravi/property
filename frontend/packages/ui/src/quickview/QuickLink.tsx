import { Avatar } from '../components/primitives';
import type { ReactNode } from 'react';
import { useSession } from '../lib/session';

export type QuickKind = 'property' | 'owner' | 'unit' | 'tenant' | 'vendor' | 'workorder' | 'invoice' | 'payment' | 'applicant';
export interface QuickTarget { kind: QuickKind; id: string; name?: string }

const EVENT = 'pos:quickview';

/** Asks the shell to open a quick-information panel. Works from any module, because the panels live in the shell. */
export function openQuickView(t: QuickTarget) {
  window.dispatchEvent(new CustomEvent<QuickTarget>(EVENT, { detail: t }));
}
export const QUICKVIEW_EVENT = EVENT;

/** Permission needed to open each panel, and the sign-in scopes that may not see it. */
const RULES: Record<QuickKind, { perm: string; blocked: string[] }> = {
  property: { perm: 'PROPERTY:VIEW', blocked: ['TENANT', 'VENDOR'] },
  owner: { perm: 'PROPERTY:VIEW', blocked: ['TENANT', 'VENDOR', 'OWNER'] },
  unit: { perm: 'PROPERTY:VIEW', blocked: ['TENANT', 'VENDOR'] },
  tenant: { perm: 'LEASE:VIEW', blocked: ['TENANT', 'VENDOR'] },
  vendor: { perm: 'VENDOR:VIEW', blocked: ['TENANT', 'VENDOR'] },
  workorder: { perm: 'WORK_ORDER:VIEW', blocked: ['TENANT', 'VENDOR'] },
  invoice: { perm: 'INVOICE:VIEW', blocked: ['TENANT', 'VENDOR'] },
  payment: { perm: 'PAYMENT:VIEW', blocked: ['TENANT', 'VENDOR'] },
  applicant: { perm: 'APPLICATION:VIEW', blocked: ['TENANT', 'VENDOR'] },
};

/** A name or number that opens a quick-information panel. Plain text when the person can't open it. */
export function QuickLink({ kind, id, name, children, className = '' }: { kind: QuickKind; id?: string | null; name?: string; children: ReactNode; className?: string }) {
  const { session, can } = useSession();
  const rule = RULES[kind];
  const person = (kind === 'tenant' || kind === 'applicant') && !!(name || typeof children === 'string');
  const label = name ?? (typeof children === 'string' ? children : undefined);
  const withPhoto = (node: ReactNode) => person && label ? <span className="inline-flex items-center gap-2"><Avatar name={label} size={24} />{node}</span> : <>{node}</>;
  if (!id || !session || !can(rule.perm) || rule.blocked.includes(session.scope)) return withPhoto(children);
  return withPhoto(
    <button type="button" onClick={(e) => { e.stopPropagation(); openQuickView({ kind, id, name }); }}
      className={`text-left font-semibold text-[#4a7fd9] underline decoration-[#4a7fd9]/50 underline-offset-2 hover:text-[#2f63bd] hover:decoration-[#2f63bd] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slateblue-600 ${className}`}>
      {children}
    </button>
  );
}
