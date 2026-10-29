import { useState } from 'react';
import { Download } from 'lucide-react';
import { api } from '../lib/api';
import { fmt } from '../lib/format';
import { useSession } from '../lib/session';

async function buildPdf(inv: any, org: string, cfg: any = {}) {
  const hdr = cfg?.invoice ?? {};
  org = hdr.legalName || org;
  const { jsPDF } = await import('jspdf');
  const d = new jsPDF({ unit: 'pt', format: 'letter' });
  const W = d.internal.pageSize.getWidth();
  const M = 56;
  let y = 64;
  d.setFont('helvetica', 'bold'); d.setFontSize(20); d.setTextColor(15, 61, 62); d.text(org || 'PropertyOS', M, y);
  d.setFontSize(26); d.setTextColor(30, 30, 30); d.text('INVOICE', W - M, y, { align: 'right' });
  y += 20; d.setFont('helvetica', 'normal'); d.setFontSize(10); d.setTextColor(110, 110, 110);
  d.text(`Invoice ${inv.invoice_number ?? String(inv.id).slice(0, 8).toUpperCase()}`, W - M, y, { align: 'right' });
  if (hdr.remitToAddress || hdr.taxId) { d.setFontSize(9); d.text([hdr.remitToAddress, hdr.taxId ? `EIN ${hdr.taxId}` : ''].filter(Boolean).join('  ·  '), M, y); d.setFontSize(10); }
  d.setDrawColor(210, 210, 205); d.line(M, y + 14, W - M, y + 14);
  y += 44;
  const block = (label: string, lines: string[], x: number) => {
    d.setFont('helvetica', 'bold'); d.setFontSize(9); d.setTextColor(110, 110, 110); d.text(label.toUpperCase(), x, y);
    d.setFont('helvetica', 'normal'); d.setFontSize(11); d.setTextColor(30, 30, 30);
    lines.filter(Boolean).forEach((l, i) => d.text(l, x, y + 16 + i * 15));
  };
  block('Billed to', [inv.tenant_name, inv.unit_label], M);
  block('Details', [`Status: ${fmt.title(inv.status)}`, `Issued: ${fmt.date(inv.created_at)}`, `Due: ${fmt.date(inv.due_date)}`, inv.period ? `Period: ${inv.period}` : ''], W / 2 + 20);
  y += 90;
  d.setFillColor(243, 242, 238); d.rect(M, y, W - 2 * M, 26, 'F');
  d.setFont('helvetica', 'bold'); d.setFontSize(10); d.setTextColor(60, 60, 60);
  d.text('Description', M + 10, y + 17); d.text('Amount', W - M - 10, y + 17, { align: 'right' });
  y += 46; d.setFont('helvetica', 'normal'); d.setFontSize(11); d.setTextColor(30, 30, 30);
  d.text(String(inv.description ?? 'Invoice'), M + 10, y); d.text(fmt.usd(inv.amount, true), W - M - 10, y, { align: 'right' });
  y += 16; d.setDrawColor(225, 225, 220); d.line(M, y, W - M, y); y += 30;
  const row = (l: string, v: string, bold = false) => {
    d.setFont('helvetica', bold ? 'bold' : 'normal'); d.setFontSize(bold ? 13 : 11);
    d.text(l, W - M - 170, y); d.text(v, W - M - 10, y, { align: 'right' }); y += bold ? 0 : 20;
  };
  if (Number(inv.tax_amount) > 0) { row('Subtotal', fmt.usd(inv.subtotal, true)); row(`Tax (${Number(inv.tax_rate)}%${inv.state ? `, ${inv.state}` : ''})`, fmt.usd(inv.tax_amount, true)); }
  row('Total', fmt.usd(inv.amount, true)); row('Paid', fmt.usd(inv.paid, true)); y += 4; row('Balance due', fmt.usd(inv.balance, true), true);
  const pays: any[] = inv.payments ?? [];
  if (pays.length) {
    y += 46; d.setFont('helvetica', 'bold'); d.setFontSize(9); d.setTextColor(110, 110, 110); d.text('PAYMENTS RECEIVED', M, y); y += 18;
    d.setFont('helvetica', 'normal'); d.setFontSize(10); d.setTextColor(30, 30, 30);
    pays.forEach((p) => { d.text(`${fmt.date(p.created_at)}  ·  ${p.method}${p.method_last4 ? ` ····${p.method_last4}` : ''}  ·  ${p.reference ?? ''}`, M, y); d.text(fmt.usd(p.amount, true), W - M, y, { align: 'right' }); y += 16; });
  }
  d.setFontSize(9); d.setTextColor(140, 140, 140);
  const note = [hdr.paymentTerms, hdr.footerNote].filter(Boolean).join('  ·  ');
  if (note) d.text(note, M, d.internal.pageSize.getHeight() - 56);
  d.text(`Generated ${new Date().toLocaleDateString('en-US')} by ${org || 'PropertyOS'}. All amounts in USD.`, M, d.internal.pageSize.getHeight() - 40);
  d.save(`invoice-${String(inv.description ?? inv.id).replace(/[^A-Za-z0-9]+/g, '-').toLowerCase()}.pdf`);
}

/** Downloads an invoice as a PDF. Pass `invoice` if already loaded, otherwise it is fetched by id on click. */
export function InvoiceDownload({ id, invoice, label, className = '' }: { id: string; invoice?: any; label?: string; className?: string }) {
  const { session } = useSession();
  const [busy, setBusy] = useState(false);
  const run = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setBusy(true);
    try { const cfg = await api.get<any>('/api/billing/settings').catch(() => ({})); await buildPdf(invoice ?? (await api.get<any>(`/api/billing/invoices/${id}`)), session?.organization?.name ?? 'PropertyOS', cfg); } catch { /* ignore */ } finally { setBusy(false); }
  };
  return (
    <button type="button" onClick={run} disabled={busy} aria-label={label ?? 'Download invoice PDF'} title="Download PDF"
      className={`inline-flex items-center gap-1.5 rounded border border-limestone-300 bg-white px-2 py-1 text-xs font-semibold text-graphite-700 hover:bg-limestone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600 disabled:opacity-50 ${className}`}>
      <Download className="h-3.5 w-3.5" />{label}
    </button>
  );
}
