import { useState } from 'react';
import { PageHeader, Panel, DataTable, type Column, useApi, Button, Field, Input, Textarea, Modal, Badge, useAction, api, Toggle, ConfirmDialog } from '@propertyos/ui';

const money = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
const BASE = '/api/identity/platform/plans';

interface Plan {
  plan_key: string; name: string; tagline: string; audience: string; highlight: boolean; visible: boolean;
  price_per_unit: number; minimum_monthly: number; features: string[]; company_count: number;
}
interface Feature { feature_key: string; title: string; summary: string; product: string }

/** Create or change one plan: wording, price and exactly which features it includes. */
function PlanEditor({ plan, features, onClose }: { plan: Plan | null; features: Feature[]; onClose: () => void }) {
  const editing = !!plan;
  const [f, setF] = useState({
    key: plan?.plan_key ?? '', name: plan?.name ?? '', tagline: plan?.tagline ?? '', audience: plan?.audience ?? '',
    pricePerUnit: plan ? String(plan.price_per_unit) : '', minimumMonthly: plan ? String(plan.minimum_monthly) : '',
    visible: plan?.visible ?? true, highlight: plan?.highlight ?? false,
  });
  const [all, setAll] = useState(plan?.features.includes('*') ?? false);
  const [chosen, setChosen] = useState<Set<string>>(new Set(plan?.features.filter((x) => x !== '*') ?? []));
  const toggle = (k: string) => setChosen((s) => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n; });
  const payload = () => ({
    ...f, features: all ? ['*'] : [...chosen],
  });
  const save = useAction(() => editing ? api.put(`${BASE}/${plan!.plan_key}`, payload()) : api.post(BASE, payload()),
    { success: editing ? 'Plan saved' : 'Plan created', invalidate: [BASE, '/api/identity/platform'], onSuccess: onClose });
  const products = [...new Set(features.map((x) => x.product))];
  const valid = f.name.trim().length >= 2 && (editing || /^[A-Za-z][A-Za-z0-9_ ]{1,29}$/.test(f.key.trim())) && f.pricePerUnit !== '' && f.minimumMonthly !== '';
  return (
    <Modal open onClose={onClose} size="lg" title={editing ? `Edit ${plan!.name}` : 'New plan'}
      footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!valid} onClick={() => save.mutate(undefined)}>{editing ? 'Save plan' : 'Create plan'}</Button></>}>
      <div className="space-y-4">
        {editing && plan!.company_count > 0 && <p className="rounded bg-warn-50 px-3 py-2 text-sm text-warn-700">{plan!.company_count} {plan!.company_count === 1 ? 'company is' : 'companies are'} on this plan. Changes to its features reach them straight away.</p>}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Plan name" required><Input maxLength={60} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label="Plan key" required hint={editing ? 'The key can’t change.' : 'Capital letters, digits and underscores, for example PRO.'}>
            <Input maxLength={30} disabled={editing} value={f.key} onChange={(e) => setF({ ...f, key: e.target.value.toUpperCase() })} />
          </Field>
          <Field label="Price per unit, per month" required><Input inputMode="decimal" value={f.pricePerUnit} onChange={(e) => setF({ ...f, pricePerUnit: e.target.value })} /></Field>
          <Field label="Monthly minimum" required><Input inputMode="decimal" value={f.minimumMonthly} onChange={(e) => setF({ ...f, minimumMonthly: e.target.value })} /></Field>
        </div>
        <Field label="Tagline" hint="One sentence under the plan name on the website."><Textarea rows={2} maxLength={200} value={f.tagline} onChange={(e) => setF({ ...f, tagline: e.target.value })} /></Field>
        <Field label="Who it’s for"><Input maxLength={160} value={f.audience} onChange={(e) => setF({ ...f, audience: e.target.value })} /></Field>
        <div className="flex flex-wrap gap-x-8 gap-y-2">
          <label className="flex items-center gap-2 text-sm font-semibold text-graphite-800"><Toggle checked={f.visible} onChange={(v) => setF({ ...f, visible: v })} label="Show on the website" />Show on the website</label>
          <label className="flex items-center gap-2 text-sm font-semibold text-graphite-800"><Toggle checked={f.highlight} onChange={(v) => setF({ ...f, highlight: v })} label="Mark as most popular" />Most popular</label>
        </div>

        <section>
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-extrabold text-graphite-950">Features included</h3>
            <label className="flex items-center gap-2 text-sm font-semibold text-graphite-800"><Toggle checked={all} onChange={setAll} label="Include every feature" />Every feature, including new ones</label>
          </div>
          {!all && (
            <div className="mt-3 max-h-72 space-y-4 overflow-y-auto rounded border border-limestone-200 p-3">
              {products.map((p) => (
                <div key={p}>
                  <div className="text-xs font-bold text-federal-700">{p.charAt(0) + p.slice(1).toLowerCase()}</div>
                  <ul className="mt-1.5 space-y-1.5">
                    {features.filter((x) => x.product === p).map((x) => (
                      <li key={x.feature_key}>
                        <label className="flex cursor-pointer items-start gap-2.5 text-sm">
                          <input type="checkbox" className="mt-1 h-4 w-4 accent-federal-800" checked={chosen.has(x.feature_key)} onChange={() => toggle(x.feature_key)} />
                          <span><span className="font-semibold text-graphite-900">{x.title}</span><span className="block text-xs text-graphite-600">{x.summary}</span></span>
                        </label>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
          <p className="mt-2 text-xs text-graphite-600">{all ? 'Companies on this plan get every feature.' : `${chosen.size} of ${features.length} features selected.`} Features can also be switched on or off for one company from the Platform page.</p>
        </section>
      </div>
    </Modal>);
}

/** Platform admins only: the plans companies can be on. */
export function Plans() {
  const plans = useApi<Plan[]>(BASE);
  const features = useApi<Feature[]>('/api/identity/platform/site/features');
  const [editing, setEditing] = useState<Plan | 'new' | null>(null);
  const [removing, setRemoving] = useState<Plan | null>(null);
  const remove = useAction((key: string) => api.del(`${BASE}/${key}`), { success: 'Plan deleted', invalidate: [BASE, '/api/identity/platform'], onSuccess: () => setRemoving(null) });
  const patch = useAction((v: { key: string; body: any }) => api.put(`${BASE}/${v.key}`, v.body), { success: 'Plan updated', invalidate: [BASE, '/api/identity/platform'] });

  const cols: Column<Plan>[] = [
    { key: 'name', header: 'Plan', render: (r) => <><span className="font-semibold">{r.name}</span><span className="block text-xs text-graphite-600">{r.plan_key}</span></> },
    { key: 'price_per_unit', header: 'Per unit, per month', align: 'right', render: (r) => money(r.price_per_unit) },
    { key: 'minimum_monthly', header: 'Monthly minimum', align: 'right', hideOnMobile: true, render: (r) => money(r.minimum_monthly) },
    { key: 'features', header: 'Features', value: (r) => r.features.length, render: (r) => r.features.includes('*') ? <Badge tone="brand">All features</Badge> : `${r.features.length} included` },
    { key: 'company_count', header: 'Companies', align: 'right', render: (r) => r.company_count },
    { key: 'highlight', header: 'Most popular', hideOnMobile: true, render: (r) => <Toggle checked={r.highlight} onChange={(v) => patch.mutate({ key: r.plan_key, body: { highlight: v } })} label={`Mark ${r.name} as most popular`} /> },
    { key: 'visible', header: 'On website', hideOnMobile: true, render: (r) => <Toggle checked={r.visible} onChange={(v) => patch.mutate({ key: r.plan_key, body: { visible: v } })} label={`Show ${r.name} on the website`} /> },
    { key: 'actions', header: '', render: (r) => <span className="flex gap-2"><Button variant="secondary" onClick={() => setEditing(r)}>Edit</Button><Button variant="quiet" onClick={() => setRemoving(r)}>Delete</Button></span> },
  ];

  return (<>
    <PageHeader title="Plans" description="The plans companies can be on. What a plan includes is what its companies can use, and the same plans appear on the public website."
      actions={<Button variant="primary" onClick={() => setEditing('new')} disabled={!features.data}>New plan</Button>} />
    <Panel padded={false}><DataTable rows={plans.data} columns={cols} rowKey={(r) => r.plan_key} loading={plans.isLoading} /></Panel>
    {editing && features.data && <PlanEditor plan={editing === 'new' ? null : editing} features={features.data} onClose={() => setEditing(null)} />}
    <ConfirmDialog open={!!removing} title={`Delete ${removing?.name ?? ''}?`} confirmLabel="Delete plan" tone="danger" loading={remove.isPending}
      onClose={() => setRemoving(null)} onConfirm={() => removing && remove.mutate(removing.plan_key)}
      body={removing && removing.company_count > 0
        ? `${removing.company_count} ${removing.company_count === 1 ? 'company is' : 'companies are'} on this plan, so it can’t be deleted. Move them to another plan first.`
        : 'It disappears from the website. This can’t be undone.'} />
  </>);
}
