/** The seeded demo users (DemoData in the backend). All share the demo password. */
export type PersonaKey = 'platform' | 'admin' | 'manager' | 'broker' | 'broker2' | 'agent' | 'accountant' | 'owner' | 'owner2' | 'tenant' | 'tenant2' | 'vendor' | 'harbor';

export interface Persona { key: PersonaKey; name: string; email: string; role: string; ui: 'platform' | 'admin' | 'manager' | 'broker' | 'agent' | 'accountant' | 'owner' | 'tenant' | 'vendor'; company: string }

export const PERSONAS: Record<PersonaKey, Persona> = {
  platform: { key: 'platform', name: 'Alex Morgan', email: 'alex.morgan@propertyos.io', role: 'Platform Admin', ui: 'platform', company: 'PropertyOS Platform' },
  admin: { key: 'admin', name: 'Sarah Mitchell', email: 'sarah.mitchell@summitridgepg.com', role: 'Org Admin', ui: 'admin', company: 'Summit Ridge Property Group' },
  manager: { key: 'manager', name: 'David Chen', email: 'david.chen@summitridgepg.com', role: 'Property Manager', ui: 'manager', company: 'Summit Ridge Property Group' },
  broker: { key: 'broker', name: 'Marcus Johnson', email: 'marcus.johnson@summitridgepg.com', role: 'Broker', ui: 'broker', company: 'Summit Ridge Property Group' },
  broker2: { key: 'broker2', name: 'Emily Rodriguez', email: 'emily.rodriguez@summitridgepg.com', role: 'Broker', ui: 'broker', company: 'Summit Ridge Property Group' },
  agent: { key: 'agent', name: 'Jordan Lee', email: 'jordan.lee@summitridgepg.com', role: 'Leasing Agent', ui: 'agent', company: 'Summit Ridge Property Group' },
  accountant: { key: 'accountant', name: 'Priya Patel', email: 'priya.patel@summitridgepg.com', role: 'Accountant', ui: 'accountant', company: 'Summit Ridge Property Group' },
  owner: { key: 'owner', name: 'Robert Williams', email: 'robert.williams@example.com', role: 'Property Owner', ui: 'owner', company: 'Summit Ridge Property Group' },
  owner2: { key: 'owner2', name: 'Nathan Greene', email: 'nathan.greene@greenfieldcap.com', role: 'Property Owner', ui: 'owner', company: 'Summit Ridge Property Group' },
  tenant: { key: 'tenant', name: 'Michael Brown', email: 'michael.brown@example.com', role: 'Resident', ui: 'tenant', company: 'Summit Ridge Property Group' },
  tenant2: { key: 'tenant2', name: 'Ashley Davis', email: 'ashley.davis@example.com', role: 'Resident', ui: 'tenant', company: 'Summit Ridge Property Group' },
  vendor: { key: 'vendor', name: 'Carlos Ramirez', email: 'carlos@lonestarplumbing.com', role: 'Vendor', ui: 'vendor', company: 'Summit Ridge Property Group' },
  harbor: { key: 'harbor', name: 'Kevin Park', email: 'kevin.park@harborviewres.com', role: 'Org Admin', ui: 'admin', company: 'Harborview Residential' },
};

export const STAFF: PersonaKey[] = ['admin', 'manager', 'broker', 'agent', 'accountant'];
export const ALL: PersonaKey[] = Object.keys(PERSONAS) as PersonaKey[];
