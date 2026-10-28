import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, getToken } from './api';

export interface Session {
  userId: string;
  name: string;
  email: string;
  roles: string[];
  permissions: string[];
  scope: 'PLATFORM' | 'ORG' | 'PROPERTY' | 'OWNER' | 'TENANT' | 'VENDOR';
  partyId?: string;
  propertyIds: string[];
  limits: Record<string, number>;
  fieldRules: Record<string, string>;
  organization: { id: string; name: string; plan: string; state: string };
  features: Record<string, boolean>;
  branding: { companyName: string; primaryColor: string; accentColor: string };
}

/** Re-renders when the token changes (sign-in, sign-out, refresh) in this or another bundle. */
export function useToken() {
  const [token, setTok] = useState(getToken());
  useEffect(() => {
    const on = () => setTok(getToken());
    window.addEventListener('pos:session-changed', on);
    window.addEventListener('storage', on);
    return () => {
      window.removeEventListener('pos:session-changed', on);
      window.removeEventListener('storage', on);
    };
  }, []);
  return token;
}

export function useSession() {
  const token = useToken();
  const q = useQuery<Session>({
    queryKey: ['session', token],
    queryFn: () => api.get<Session>('/api/identity/me'),
    enabled: !!token,
    staleTime: 60_000,
  });
  const s = q.data;
  const can = (perm: string) => {
    if (!s) return false;
    if (s.scope === 'PLATFORM') return true;
    const [f] = perm.split(':');
    return s.permissions.includes(perm) || s.permissions.includes(`${f}:*`);
  };
  const feature = (key: string) => !!s && (s.features?.[key] ?? true);
  return { session: s, loading: q.isLoading, error: q.error, can, feature, token };
}

export type Persona = 'platform' | 'admin' | 'manager' | 'broker' | 'agent' | 'accountant' | 'owner' | 'tenant' | 'vendor';

/** Which home experience to show, derived from scope and permissions. */
export function personaOf(s?: Session): Persona {
  if (!s) return 'broker';
  switch (s.scope) {
    case 'PLATFORM':
      return 'platform';
    case 'OWNER':
      return 'owner';
    case 'TENANT':
      return 'tenant';
    case 'VENDOR':
      return 'vendor';
    case 'PROPERTY':
      return s.permissions.includes('APPLICATION:APPROVE') ? 'broker' : 'agent';
    default:
      if (s.permissions.includes('ROLE:CONFIGURE')) return 'admin';
      if (s.permissions.includes('PAYMENT:APPROVE') && !s.permissions.includes('LISTING:CREATE')) return 'accountant';
      return 'manager';
  }
}

/** Applies field-level rules returned by the API: masked values arrive as "•••• 1234"; hidden ones are absent. */
export function fieldAccess(s: Session | undefined, field: string): 'VISIBLE' | 'MASKED' | 'HIDDEN' {
  if (!s || s.scope === 'PLATFORM') return 'VISIBLE';
  return (s.fieldRules?.[field] as any) ?? 'VISIBLE';
}
