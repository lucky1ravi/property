import { Api, apiAs, rows, tokenFor } from './api';
import { SUFFIX } from './env';

export interface Company { name: string; orgId: string; adminEmail: string; adminPassword: string; token: string; api: Api; sfx: string }

/** Creates a brand-new company as the platform administrator and signs its admin in (changing the temporary password). Local mode only. */
export async function onboardCompany(opts: { plan?: string; state?: string } = {}): Promise<Company> {
  const sfx = SUFFIX();
  const platform = await apiAs('platform');
  const adminEmail = `admin.${sfx}@e2e.test`;
  const name = `E2E Realty ${sfx}`;
  const r = await platform.post('/api/identity/platform/orgs', { name, state: opts.state ?? 'TX', adminName: 'Ada Admin', adminEmail, plan: opts.plan ?? 'ENTERPRISE' });
  if (r.status !== 200) throw new Error(`onboard failed: ${r.status} ${JSON.stringify(r.body)}`);
  const adminPassword = `E2e-${sfx}-pass1`;
  const pub = await Api.create();
  const c = await pub.post('/api/identity/public/change-password', { email: adminEmail, currentPassword: r.body.temporaryPassword, newPassword: adminPassword });
  if (c.status !== 200) throw new Error(`first sign-in failed: ${c.status} ${JSON.stringify(c.body)}`);
  return { name, orgId: r.body.orgId, adminEmail, adminPassword, token: c.body.token, api: await Api.create(c.body.token), sfx };
}

/** Finds a built-in role id of a company by its key (ORG_ADMIN, BROKER, OWNER, TENANT, VENDOR, ...). */
export async function roleId(admin: Api, key: string): Promise<string> {
  const role = rows(await admin.get('/api/identity/roles')).find((x: any) => x.role_key === key);
  if (!role) throw new Error(`no role ${key}`);
  return role.id;
}

/** Creates a user, then signs them in by changing the temporary password. Returns their token and credentials. */
export async function createSignedInUser(admin: Api, input: { email: string; name: string; roleKey: string; partyType?: string; partyId?: string; propertyIds?: string[] }) {
  const body: Record<string, unknown> = { email: input.email, name: input.name, roleIds: [await roleId(admin, input.roleKey)] };
  if (input.partyType) { body.partyType = input.partyType; body.partyId = input.partyId; }
  if (input.propertyIds) body.propertyIds = input.propertyIds;
  const r = await admin.post('/api/identity/users', body);
  if (r.status !== 200) throw new Error(`create user failed: ${r.status} ${JSON.stringify(r.body)}`);
  const password = `E2e-${SUFFIX()}-pass1`;
  const c = await (await Api.create()).post('/api/identity/public/change-password', { email: input.email, currentPassword: r.body.temporaryPassword, newPassword: password });
  if (c.status !== 200) throw new Error(`first sign-in failed for ${input.email}: ${c.status} ${JSON.stringify(c.body)}`);
  return { token: c.body.token as string, password, userId: r.body.id as string, api: await Api.create(c.body.token) };
}

export { tokenFor };
