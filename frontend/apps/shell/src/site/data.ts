import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@propertyos/ui';

export interface SiteFeature { key: string; title: string; summary: string; product: string }
export interface SitePlan {
  key: string; name: string; tagline: string; audience: string; highlight: boolean;
  pricePerUnit: number; minimumMonthly: number; allFeatures: boolean; features: string[];
}
export interface SiteContent { plans: SitePlan[]; features: SiteFeature[]; topics: string[] }

/** Plans and features come from the platform, so what the website promises is what the product does. */
export function useSite() {
  return useQuery({ queryKey: ['public-site'], queryFn: () => api.get<SiteContent>('/api/identity/public/site'), staleTime: 60_000, retry: 1 });
}

/** Sets the browser tab title for a page. */
export function useTitle(title: string) {
  useEffect(() => { document.title = `${title} | PropertyOS`; }, [title]);
}

export const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: n % 1 === 0 ? 0 : 2 });

/** The areas of the product. Feature lists under each one come from the platform. */
export const PRODUCTS: { key: string; name: string; headline: string; body: string; who: string; path: string[] }[] = [
  {
    key: 'LEASING', name: 'Leasing', headline: 'From first enquiry to signed lease',
    body: 'Listings are written and checked for you, every enquiry gets a reply, showings are booked by area and capacity, and each applicant is scored with the reasons shown. The lease follows your state’s rules and is signed online.',
    who: 'Brokers, leasing agents, applicants', path: ['Listing', 'Enquiry', 'Showing', 'Application', 'Lease'],
  },
  {
    key: 'RENT', name: 'Rent and owners', headline: 'Rent that collects itself, statements that close themselves',
    body: 'Charges are scheduled from the lease, invoices and reminders go out on time, and late fees stay inside state caps. Tenants at risk are offered a payment plan. Owners see their properties and payouts without calling you.',
    who: 'Accountants, owners, tenants', path: ['Charge', 'Invoice', 'Reminder', 'Payment', 'Owner statement'],
  },
  {
    key: 'MAINTENANCE', name: 'Maintenance', headline: 'Repairs dispatched before the second phone call',
    body: 'Residents describe the problem in plain words. PropertyOS works out the trade, priority and likely cost, asks the owner when the amount is above their limit, and sends the best available vendor. The resident confirms and rates the job.',
    who: 'Property managers, vendors, residents', path: ['Request', 'Triage', 'Approval', 'Dispatch', 'Confirmation'],
  },
  {
    key: 'AUTOMATION', name: 'Automation and AI', headline: 'Routine work moves on its own, with reasons',
    body: 'Rules and a workflow designer handle the steps that repeat. Every AI suggestion shows why it was made. Anything involving money, legal terms or safety waits for a person to approve.',
    who: 'Everyone, configured by administrators', path: ['Trigger', 'Rule', 'Suggestion', 'Approval', 'Done'],
  },
  {
    key: 'INSIGHTS', name: 'Broker tools and insights', headline: 'Start the day with the few things that need you',
    body: 'A daily queue puts the decisions in front of the broker, and opportunity reports show where profit and growth are hiding across the portfolio.',
    who: 'Brokers and company owners', path: ['Queue', 'Decide', 'Report'],
  },
  {
    key: 'DOCUMENTS', name: 'Messages and documents', headline: 'One place for the conversation and the paperwork',
    body: 'Residents, owners, vendors and staff talk in one thread per matter. Documents and photos are stored per company and attached to the lease, property or work order they belong to.',
    who: 'Everyone', path: ['Message', 'Attach', 'Find'],
  },
];

export const TOPIC_LABEL: Record<string, string> = {
  DEMO: 'I’d like a demo', PRICING: 'Pricing and plans', SUPPORT: 'Help with my account', PARTNERSHIP: 'Partnership', OTHER: 'Something else',
};
