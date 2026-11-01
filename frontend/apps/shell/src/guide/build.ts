import { guideFor, type PageGuide, type Step } from './content';
import { findTarget } from './TourOverlay';

/** Things the shared components expose on every page, with what to say about them. Only the ones really on the page are used. */
const COMMON: Step[] = [
  { target: '[data-tour="page-actions"]', title: 'Start here', body: 'The main things you can do on this page are here. You only see the ones your role allows.' },
  { target: '[data-tour="tabs"]', title: 'Switch views', body: 'Tabs show different slices of the same information. The number beside a tab is how many items it holds.' },
  { target: '[data-tour="table-search"]', title: 'Search', body: 'Type any word to narrow the list: a name, an address, a status. It looks in every column, across all pages.' },
  { target: '[data-tour="table-filters"]', title: 'Filters', body: 'Open Filters to narrow by column: pick a status, a date range or an amount. Click Clear to start over.' },
  { target: '[data-tour="table"]', title: 'Open a record', body: 'Each row is one record. Click a row to open it, or click a column heading to sort by it.' },
  { target: '[data-tour="table-export"]', title: 'Take it to a spreadsheet', body: 'Export CSV saves everything that matches your search and filters, not only the page you can see.' },
  { target: '[data-tour="table-paging"]', title: 'Move through pages', body: 'Long lists show 20 at a time. Choose 50 or 100 rows per page, or use the arrows to move.' },
];

const HELP_STEP: Step = {
  target: '[data-tour="header-help"]',
  title: 'Help is always one click away',
  body: 'Click Help, or press ? on your keyboard, for a plain-language guide to whatever page you are on. Press Ctrl+K to search for anything.',
};

/** A tour of the page the person is on, built from what is actually on screen plus our notes about the page, if we have any. */
export function buildPageTour(pathname: string): { guide?: PageGuide; steps: Step[] } {
  const guide = guideFor(pathname);
  const heading = document.querySelector<HTMLElement>('[data-tour="page-title"] h1')?.textContent?.trim();
  const steps: Step[] = [];
  steps.push({
    target: '[data-tour="page-title"]',
    title: guide?.title ?? heading ?? 'This page',
    body: guide?.summary ?? 'This is where you work with this part of the product. Here is a quick look at what is on it.',
  });
  for (const s of guide?.steps ?? []) if (!s.target || findTarget(s.target)) steps.push(s);
  const custom = new Set((guide?.steps ?? []).map((s) => s.target));
  for (const s of COMMON) if (!custom.has(s.target) && findTarget(s.target)) steps.push(s);
  if (findTarget(HELP_STEP.target)) steps.push(HELP_STEP);
  return { guide, steps: steps.filter((s) => !s.target || findTarget(s.target)) };
}

const ROLE_LINE: Record<string, string> = {
  platform: 'You manage the companies that use PropertyOS.',
  admin: 'You can set up the company, its people and its rules.',
  manager: 'You run day-to-day operations for your properties.',
  broker: 'You work leads, showings and applications.',
  agent: 'You work leads, showings and applications.',
  accountant: 'You work with rent, statements and payouts.',
  owner: 'You can see how your properties are doing.',
  tenant: 'You can pay rent, read your lease and ask for repairs.',
  vendor: 'You can see the jobs you are assigned.',
};

/** The first-time tour of the whole workspace. */
export function buildAppTour(firstName: string, persona: string): Step[] {
  const steps: Step[] = [
    { title: `Welcome, ${firstName}`, body: `This takes about a minute. ${ROLE_LINE[persona] ?? ''} You can leave at any time with Esc, and take the tour again whenever you like from Help.` },
    { target: '[data-tour="sidebar"]', title: 'Everything starts in this menu', body: 'Each entry opens a part of the product. It only lists what your role can use, so nothing here is off limits. Click an entry with an arrow to see what is inside.' },
    { target: '[data-tour="header-search"]', title: 'Find anything in seconds', body: 'Click here or press Ctrl+K (⌘K on a Mac). Type a name, an address or a page, such as “invoices”, and jump straight to it. It searches people, properties, units, leases and more.' },
    { target: '[data-tour="header-notifications"]', title: 'Alerts', body: 'The bell shows things that need you: a lease about to end, a payment that failed, a message waiting. The number is how many you have not read.' },
    { target: '[data-tour="assistant"]', title: 'Ask the assistant', body: 'Stuck? Ask in plain words, for example “which leases end next month?”. The assistant only uses information you are allowed to see.' },
    { target: '[data-tour="header-help"]', title: 'Help on every page', body: 'Click Help, or press ?, for a short guide to whichever page you are on, with steps for the common jobs and a tour of that page.' },
    { target: '[data-tour="header-user"]', title: 'Your account', body: 'Change your photo and details, set which alerts reach you, or sign out.' },
    { title: 'That is it', body: 'When you open a page for the first time we will offer a quick tour of it. You can always say “Not now”. Start with the Getting started list on your home page.' },
  ];
  return steps.filter((s) => !s.target || findTarget(s.target));
}
