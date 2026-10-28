import { createContext, useContext } from 'react';

/** Where this module is mounted: '/app/leasing' for staff, '/homes' for the public site. */
export const BaseCtx = createContext('/app/leasing');
export const useBase = () => useContext(BaseCtx);

export const APPLICATION_STEPS = [
  { key: 'SUBMITTED', label: 'Submitted' }, { key: 'SCREENED', label: 'Screened' }, { key: 'OWNER_REVIEW', label: 'Owner review' }, { key: 'APPROVED', label: 'Approved' },
];
export const LEASE_STEPS = [
  { key: 'DRAFT', label: 'Draft' }, { key: 'SENT', label: 'Sent' }, { key: 'TENANT_SIGNED', label: 'Tenant signed' }, { key: 'ACTIVE', label: 'Active' },
];
export const LISTING_STEPS = [
  { key: 'DRAFT', label: 'Draft' }, { key: 'AI_REVIEW', label: 'Review' }, { key: 'APPROVED', label: 'Approved' }, { key: 'LIVE', label: 'Live' },
];
export const labelOf = (s?: string) => (s ?? '').toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
