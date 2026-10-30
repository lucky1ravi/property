import { useState } from 'react';
import { PageHeader, Panel, DataTable, type Column, Select, useApi, fmt, Button, Field, Input, Textarea, Modal, Badge, useAction, api, Toggle, RowActions } from '@propertyos/ui';

const STATUS_TONE: Record<string, 'warn' | 'brand' | 'neutral'> = { NEW: 'warn', CONTACTED: 'brand', CLOSED: 'neutral' };

function FeatureEditor({ feature, onClose }: { feature: any; onClose: () => void }) {
  const [f, setF] = useState({ title: feature.title as string, summary: feature.summary as string });
  const save = useAction(() => api.put(`/api/identity/platform/site/features/${feature.feature_key}`, f), { success: 'Feature saved', invalidate: ['/api/identity/platform/site'], onSuccess: onClose });
  return (
    <Modal open onClose={onClose} title="Edit feature wording"
      footer={<><Button variant="quiet" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.isPending} disabled={!f.title.trim() || !f.summary.trim()} onClick={() => save.mutate(undefined)}>Save feature</Button></>}>
      <div className="space-y-3">
        <Field label="Title" required><Input maxLength={100} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Field>
        <Field label="Summary" required hint="One or two sentences visitors read on the Features and Products pages."><Textarea rows={3} maxLength={300} value={f.summary} onChange={(e) => setF({ ...f, summary: e.target.value })} /></Field>
      </div>
    </Modal>);
}

export function Website() {
  const features = useApi<any[]>('/api/identity/platform/site/features');
  const [status, setStatus] = useState('');
  const inbox = useApi<any[]>(`/api/identity/platform/site/contact-requests${status ? `?status=${status}` : ''}`);
  const [feature, setFeature] = useState<any | null>(null);
  const [open, setOpen] = useState<any | null>(null);
  const changeFeature = useAction((v: { key: string; body: any }) => api.put(`/api/identity/platform/site/features/${v.key}`, v.body), { success: 'Feature updated', invalidate: ['/api/identity/platform/site'] });
  const setEnquiry = useAction((v: { id: string; status: string }) => api.put(`/api/identity/platform/site/contact-requests/${v.id}`, { status: v.status }), { success: 'Enquiry updated', invalidate: ['/api/identity/platform/site'] });

  const featureCols: Column<any>[] = [
    { key: 'title', header: 'Feature', render: (r) => <span className="font-semibold">{r.title}</span> },
    { key: 'product', header: 'Product', render: (r) => <Badge>{r.product.charAt(0) + r.product.slice(1).toLowerCase()}</Badge> },
    { key: 'summary', header: 'Summary', hideOnMobile: true },
    { key: 'visible', header: 'Shown', render: (r) => <Toggle checked={r.visible} onChange={(v) => changeFeature.mutate({ key: r.feature_key, body: { visible: v } })} label={`Show ${r.title} on the website`} /> },
    { key: 'edit', header: '', render: (r) => <RowActions name={r.title ?? r.feature_key} onEdit={() => setFeature(r)} /> },
  ];
  const inboxCols: Column<any>[] = [
    { key: 'created_at', header: 'Received', value: (r) => r.created_at, render: (r) => fmt.date(r.created_at) },
    { key: 'name', header: 'From', render: (r) => <><span className="font-semibold">{r.name}</span><span className="block text-xs text-graphite-600">{r.company ?? ''}</span></> },
    { key: 'topic', header: 'About', render: (r) => <Badge>{r.topic.charAt(0) + r.topic.slice(1).toLowerCase()}</Badge> },
    { key: 'status', header: 'Status', render: (r) => <Select aria-label={`Status for ${r.name}`} value={r.status} onChange={(e) => setEnquiry.mutate({ id: r.id, status: e.target.value })}><option value="NEW">New</option><option value="CONTACTED">Contacted</option><option value="CLOSED">Closed</option></Select> },
    { key: 'view', header: '', render: (r) => <Button variant="secondary" onClick={() => setOpen(r)}>Read</Button> },
  ];

  return (<>
    <PageHeader title="Website" description="How features are described on the public pages, and the enquiries from the contact form. Plans and prices are managed under Plans." />
    <Panel title="Enquiries" description="Sent from the Contact us page." padded={false} className="mb-6">
      <DataTable rows={inbox.data} columns={inboxCols} rowKey={(r) => r.id} loading={inbox.isLoading} initialSort={{ key: 'created_at', dir: 'desc' }}
        toolbar={<Select aria-label="Filter by status" value={status} onChange={(e) => setStatus(e.target.value)}><option value="">All enquiries</option><option value="NEW">New</option><option value="CONTACTED">Contacted</option><option value="CLOSED">Closed</option></Select>} />
    </Panel>
    <Panel title="Features" description="Hidden features disappear from the Features, Products and Plans pages." padded={false}><DataTable rows={features.data} columns={featureCols} rowKey={(r) => r.feature_key} loading={features.isLoading} /></Panel>
    {feature && <FeatureEditor feature={feature} onClose={() => setFeature(null)} />}
    <Modal open={!!open} onClose={() => setOpen(null)} title={open?.name ?? ''} footer={<>
      {open && <Button variant="secondary" onClick={() => { setEnquiry.mutate({ id: open.id, status: 'CONTACTED' }); setOpen(null); }}>Mark as contacted</Button>}
      <Button variant="primary" onClick={() => setOpen(null)}>Close</Button></>}>
      {open && <div className="space-y-3 text-sm">
        <dl className="divide-y divide-limestone-200">
          {[['Email', <a key="e" className="text-federal-800 underline" href={`mailto:${open.email}`}>{open.email}</a>], ['Company', open.company], ['Phone', open.phone], ['Units', open.units], ['About', open.topic.charAt(0) + open.topic.slice(1).toLowerCase()], ['Received', fmt.date(open.created_at)]]
            .map(([k, v]: any) => <div key={k} className="flex justify-between gap-4 py-1.5"><dt className="text-graphite-600">{k}</dt><dd className="text-right font-semibold text-graphite-900">{v ?? 'Not given'}</dd></div>)}
        </dl>
        <p className="whitespace-pre-wrap rounded bg-limestone-100 p-3 text-graphite-800">{open.message}</p>
        <Badge tone={STATUS_TONE[open.status] ?? 'neutral'}>{open.status.charAt(0) + open.status.slice(1).toLowerCase()}</Badge>
      </div>}
    </Modal>
  </>);
}
