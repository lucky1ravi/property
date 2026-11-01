import { useState } from 'react';
import { Download } from 'lucide-react';
import { api } from '../lib/api';
import { fmt } from '../lib/format';
import { useSession } from '../lib/session';

type Rgb = [number, number, number];

function rgbOf(hex: string | undefined, fallback: Rgb): Rgb {
  return hex && /^#[0-9a-fA-F]{6}$/.test(hex) ? [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)] : fallback;
}

/** The natural size of a picture, or null when it cannot be read. */
function loadImage(src: string): Promise<{ w: number; h: number } | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

/**
 * The invoice as a PDF: the company's colours and logo, who it is from and to, the amount due up front, an itemised table, the
 * tax worked out, what has been paid, and how and when to pay. Built in the browser, so nothing is sent anywhere to make it.
 */
export async function buildInvoicePdf(inv: any, ctx: { org: string; invoiceSettings?: any; branding?: any; property?: any }) {
  const hdr = ctx.invoiceSettings ?? {};
  const org = hdr.legalName || ctx.branding?.companyName || ctx.org || 'PropertyOS';
  const brand = rgbOf(ctx.branding?.primaryColor, [15, 61, 62]);
  const accent = rgbOf(ctx.branding?.accentColor, [200, 150, 62]);
  const green: Rgb = [46, 125, 91], red: Rgb = [179, 38, 30], white: Rgb = [255, 255, 255];
  const ink: Rgb = [29, 37, 36], muted: Rgb = [102, 114, 111], rule: Rgb = [213, 220, 217], wash: Rgb = [245, 247, 246];
  const { jsPDF } = await import('jspdf');
  const d = new jsPDF({ unit: 'pt', format: 'letter' });
  const W = d.internal.pageSize.getWidth(), H = d.internal.pageSize.getHeight(), M = 48;
  const number = inv.invoice_number ?? String(inv.id).slice(0, 8).toUpperCase();
  const balance = Number(inv.balance ?? Number(inv.amount) - Number(inv.paid ?? 0));
  const overdue = balance > 0 && inv.status !== 'VOID' && new Date(inv.due_date) < new Date(new Date().toDateString());
  const status: { label: string; c: Rgb } = inv.status === 'VOID' ? { label: 'VOID', c: muted } : balance <= 0 ? { label: 'PAID', c: green }
    : overdue ? { label: 'OVERDUE', c: red } : Number(inv.paid) > 0 ? { label: 'PART PAID', c: accent } : { label: 'DUE', c: brand };
  const text = (s: string, x: number, y: number, o: { size?: number; bold?: boolean; color?: Rgb; align?: 'left' | 'right' | 'center'; max?: number } = {}) => {
    d.setFont('helvetica', o.bold ? 'bold' : 'normal'); d.setFontSize(o.size ?? 10); d.setTextColor(...(o.color ?? ink));
    if (o.max) { const lines = d.splitTextToSize(s, o.max) as string[]; d.text(lines, x, y, { align: o.align }); return lines.length; }
    d.text(s, x, y, { align: o.align }); return 1;
  };
  const money = (n: unknown) => fmt.usd(n as number, true);

  // Colour band and header: logo (or the company name) on the left, the document title on the right.
  d.setFillColor(...brand); d.rect(0, 0, W, 10, 'F'); d.setFillColor(...accent); d.rect(0, 10, W, 2.5, 'F');
  const y0 = 52;
  let ly = y0;
  const logo = ctx.branding?.logoDataUrl as string | undefined;
  const size = logo ? await loadImage(logo) : null;
  if (logo && size) {
    const scale = Math.min(150 / size.w, 54 / size.h);
    d.addImage(logo, logo.startsWith('data:image/png') ? 'PNG' : 'JPEG', M, y0 - 14, size.w * scale, size.h * scale);
    ly = y0 - 14 + size.h * scale + 16;
    text(org, M, ly, { size: 10, bold: true }); ly += 13;
  } else { text(org, M, y0 + 6, { size: 18, bold: true, color: brand }); ly = y0 + 28; }
  for (const line of [hdr.remitToAddress, hdr.taxId ? `EIN ${hdr.taxId}` : '', ctx.branding?.emailSender, ctx.branding?.portalDomain].filter(Boolean) as string[]) { text(line, M, ly, { size: 8.5, color: muted, max: 230 }); ly += 11; }
  text('INVOICE', W - M, y0 + 6, { size: 28, bold: true, color: brand, align: 'right' });
  text(`No. ${number}`, W - M, y0 + 24, { size: 10, color: muted, align: 'right' });
  d.setDrawColor(...status.c); d.setLineWidth(1.4); d.roundedRect(W - M - 92, y0 + 34, 92, 24, 3, 3, 'S');
  text(status.label, W - M - 46, y0 + 50, { size: 11, bold: true, color: status.c, align: 'center' });
  let y = Math.max(ly, y0 + 70) + 14;
  d.setDrawColor(...rule); d.setLineWidth(0.6); d.line(M, y, W - M, y); y += 22;

  // Billed to / details / amount due.
  const col = (label: string, lines: string[], x: number, width: number) => {
    text(label.toUpperCase(), x, y, { size: 7.5, bold: true, color: muted });
    let yy = y + 14;
    lines.filter(Boolean).forEach((l, i) => { yy += text(l, x, yy, { size: i === 0 ? 11 : 9.5, bold: i === 0, color: i === 0 ? ink : muted, max: width }) * (i === 0 ? 14 : 12); });
    return yy;
  };
  const prop = ctx.property;
  const addr = prop ? [prop.street, [prop.city, prop.state, prop.zip].filter(Boolean).join(', ')].filter(Boolean).join(' · ') : '';
  const b1 = col('Billed to', [inv.tenant_name, inv.unit_label, addr], M, 170);
  const b2 = col('Invoice details', [`Issued  ${fmt.date(inv.created_at)}`, `Due  ${fmt.date(inv.due_date)}`, inv.period ? `Period  ${inv.period}` : '', hdr.paymentTerms ? `Terms  ${hdr.paymentTerms}` : ''], M + 200, 150);
  d.setFillColor(...wash); d.roundedRect(W - M - 160, y - 12, 160, 74, 4, 4, 'F');
  text('AMOUNT DUE', W - M - 148, y + 2, { size: 7.5, bold: true, color: muted });
  text(money(balance), W - M - 148, y + 30, { size: 22, bold: true, color: balance > 0 ? brand : green });
  text(balance > 0 ? `by ${fmt.date(inv.due_date)}` : 'Nothing owing', W - M - 148, y + 48, { size: 9, color: muted });
  y = Math.max(b1, b2, y + 62) + 24;

  // Itemised table.
  const colAmt = W - M - 8;
  d.setFillColor(...brand); d.rect(M, y, W - 2 * M, 24, 'F');
  text('DESCRIPTION', M + 10, y + 16, { size: 8.5, bold: true, color: white });
  text('TAX', colAmt - 110, y + 16, { size: 8.5, bold: true, color: white, align: 'right' });
  text('AMOUNT', colAmt, y + 16, { size: 8.5, bold: true, color: white, align: 'right' });
  y += 24;
  const taxAmt = Number(inv.tax_amount ?? 0), subtotal = Number(inv.subtotal ?? Number(inv.amount) - taxAmt);
  const kinds: Record<string, string> = { RENT: 'Rent', LATE_FEE: 'Late fee', DEPOSIT: 'Security deposit', UTILITY: 'Utility', APPLICATION: 'Application fee', NSF_FEE: 'Returned payment fee' };
  const kind = kinds[inv.kind] ?? 'Charge';
  const lines = d.splitTextToSize(String(inv.description ?? 'Invoice'), 300) as string[];
  const rowH = 20 + (lines.length - 1) * 12 + 16;
  d.setFillColor(...wash); d.rect(M, y, W - 2 * M, rowH, 'F');
  text(lines.join('\n'), M + 10, y + 16, { size: 10.5, bold: true });
  text(`${kind}${inv.period ? ` · ${inv.period}` : ''}`, M + 10, y + 16 + lines.length * 12, { size: 8.5, color: muted });
  text(taxAmt > 0 ? money(taxAmt) : '-', colAmt - 110, y + 16, { size: 10.5, align: 'right' });
  text(money(subtotal), colAmt, y + 16, { size: 10.5, align: 'right' });
  y += rowH + 8; d.setDrawColor(...rule); d.line(M, y, W - M, y); y += 20;

  // Totals, with the tax note to their left so the reader sees why there is or is not tax.
  const tx = W - M - 220;
  text(taxAmt > 0 ? `Sales tax of ${Number(inv.tax_rate ?? 0)}% is charged on this item under ${inv.state ?? 'state'} rules. Residential rent is normally not taxed.` : 'No sales tax applies to this item. Residential rent and deposits are normally not taxed.', M, y, { size: 8.5, color: muted, max: tx - M - 24 });
  const total = (label: string, value: string, o: { bold?: boolean; color?: Rgb; size?: number } = {}) => {
    text(label, tx, y, { size: o.size ?? 10, bold: o.bold, color: o.color ?? (o.bold ? ink : muted) }); text(value, colAmt, y, { size: o.size ?? 10, bold: o.bold, color: o.color ?? ink, align: 'right' }); y += (o.size ?? 10) + 8;
  };
  total('Subtotal', money(subtotal));
  if (taxAmt > 0) total(`Tax (${Number(inv.tax_rate ?? 0)}%${inv.state ? `, ${inv.state}` : ''})`, money(taxAmt));
  d.setDrawColor(...rule); d.line(tx, y - 6, W - M, y - 6); y += 6;
  total('Total', money(inv.amount), { bold: true, size: 11 });
  if (Number(inv.paid) > 0) total('Paid to date', `- ${money(inv.paid)}`, { color: green });
  d.setFillColor(...brand); d.roundedRect(tx - 10, y - 4, W - M - tx + 10, 30, 3, 3, 'F');
  text('BALANCE DUE', tx, y + 15, { size: 9, bold: true, color: white }); text(money(balance), colAmt, y + 16, { size: 13, bold: true, color: white, align: 'right' });
  y += 46;

  // Payments received.
  const pays: any[] = inv.payments ?? [];
  if (pays.length) {
    if (y > H - 220) { d.addPage(); y = 60; }
    text('PAYMENTS RECEIVED', M, y, { size: 7.5, bold: true, color: muted }); y += 8; d.setDrawColor(...rule); d.line(M, y, W - M, y); y += 14;
    pays.forEach((p) => {
      if (y > H - 110) { d.addPage(); y = 60; }
      const how = p.method === 'ACH' ? 'Bank transfer' : p.method === 'CARD' ? 'Card' : String(p.method ?? '');
      text(fmt.date(p.created_at), M, y, { size: 9.5 });
      text(`${how}${p.method_last4 ? ` ending ${p.method_last4}` : ''}`, M + 90, y, { size: 9.5 });
      text(p.reference ?? '', M + 250, y, { size: 9, color: muted });
      text(`${p.status === 'FAILED' ? 'Failed  ' : ''}${money(p.amount)}`, colAmt, y, { size: 9.5, align: 'right', color: p.status === 'FAILED' ? red : ink }); y += 16;
    });
  }

  // How to pay, then the footer on every page.
  if (balance > 0) {
    if (y > H - 150) { d.addPage(); y = 60; }
    y += 14; d.setFillColor(...wash); d.roundedRect(M, y, W - 2 * M, 48, 4, 4, 'F');
    text('HOW TO PAY', M + 12, y + 16, { size: 7.5, bold: true, color: muted });
    text(`Sign in to your resident portal${ctx.branding?.portalDomain ? ` at ${ctx.branding.portalDomain}` : ''} and choose Pay now, or set up autopay under Payments.${hdr.remitToAddress ? ` To pay by cheque, send it to ${hdr.remitToAddress}.` : ''}`, M + 12, y + 31, { size: 9, max: W - 2 * M - 24 });
  }
  const pages = d.getNumberOfPages();
  for (let p = 1; p <= pages; p++) {
    d.setPage(p);
    d.setDrawColor(...rule); d.line(M, H - 54, W - M, H - 54);
    if (hdr.footerNote) text(String(hdr.footerNote), M, H - 40, { size: 8, color: muted, max: W - 2 * M - 80 });
    text(`${org} · Invoice ${number} · generated ${new Date().toLocaleDateString('en-US')} · all amounts in USD`, M, H - 24, { size: 7.5, color: muted });
    text(`Page ${p} of ${pages}`, W - M, H - 24, { size: 7.5, color: muted, align: 'right' });
  }
  d.save(`invoice-${String(number).replace(/[^A-Za-z0-9]+/g, '-').toLowerCase()}.pdf`);
}

/** Downloads an invoice as a PDF. Pass `invoice` if already loaded, otherwise it is fetched by id on click. */
export function InvoiceDownload({ id, invoice, label, className = '' }: { id: string; invoice?: any; label?: string; className?: string }) {
  const { session } = useSession();
  const [busy, setBusy] = useState(false);
  const run = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setBusy(true);
    try {
      const cfg = await api.get<any>('/api/billing/settings').catch(() => ({}));
      const inv = invoice ?? (await api.get<any>(`/api/billing/invoices/${id}`));
      const property = inv.property_id ? await api.get<any>(`/api/leasing/properties/${inv.property_id}`).catch(() => null) : null;
      await buildInvoicePdf(inv, { org: session?.organization?.name ?? 'PropertyOS', invoiceSettings: cfg?.invoice, branding: session?.branding, property });
    } catch { /* ignore */ } finally { setBusy(false); }
  };
  return (
    <button type="button" onClick={run} disabled={busy} aria-label={label ?? 'Download invoice PDF'} title="Download PDF"
      className={`inline-flex items-center gap-1.5 rounded border border-limestone-300 bg-white px-2 py-1 text-xs font-semibold text-graphite-700 hover:bg-limestone-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-slateblue-600 disabled:opacity-50 ${className}`}>
      <Download className="h-3.5 w-3.5" />{label}
    </button>
  );
}
