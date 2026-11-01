/**
 * What each page is for, in plain language, for people who have never seen the product.
 * A page without an entry still gets a tour built from whatever is on screen.
 */
export interface Task { label: string; hint: string; to?: string }
export interface Step {
  /** CSS selector of the thing to point at. Without one (or when it is not on the page) the step is shown in the middle. */
  target?: string;
  title: string;
  body: string;
}
export interface PageGuide {
  id: string;
  match: RegExp;
  title: string;
  /** One or two plain sentences: what is this page, and why would I come here. */
  summary: string;
  tasks: Task[];
  /** Extra steps for things only this page has. The common ones (title, buttons, search, filters, paging) are added for you. */
  steps?: Step[];
}

export const PAGE_GUIDES: PageGuide[] = [
  {
    id: 'home', match: /^\/app\/?$/, title: 'Home',
    summary: 'Your starting point. It shows what needs your attention today and the numbers that matter, based on your role.',
    tasks: [
      { label: 'See what needs my attention', hint: 'Look at the cards at the top. Each one opens the list behind it.' },
      { label: 'Find anything quickly', hint: 'Press Ctrl+K (⌘K on a Mac), or click Search at the top, then type a name, address or page.' },
      { label: 'Get set up', hint: 'Follow the Getting started list on this page. It ticks itself off as you go.' },
    ],
  },
  {
    id: 'messages', match: /^\/app\/messages/, title: 'Messages',
    summary: 'Conversations with residents, owners, vendors and your team. Each one can be tied to a property, lease or repair so nothing gets lost.',
    tasks: [
      { label: 'Start a conversation', hint: 'Click New conversation, search for the people, write a subject and your message.' },
      { label: 'Find an old conversation', hint: 'Type in the search box above the list. It looks at the subject, the people and the last message.' },
      { label: 'Catch up on a long thread', hint: 'Open it and click Catch up for a short summary and a suggested reply.' },
    ],
  },
  {
    id: 'leasing-overview', match: /^\/app\/leasing\/?$/, title: 'Leasing overview',
    summary: 'The whole leasing pipeline on one page: homes being advertised, people enquiring, showings booked, applications and leases.',
    tasks: [
      { label: 'Advertise a vacant home', hint: 'Go to Listings and click New listing.', to: '/app/leasing/listings' },
      { label: 'Follow up an enquiry', hint: 'Go to Leads, open the person and send the suggested reply.', to: '/app/leasing/leads' },
      { label: 'Review an application', hint: 'Go to Applications, open one and run screening.', to: '/app/leasing/applications' },
    ],
  },
  {
    id: 'listings', match: /^\/app\/leasing\/listings\/?$/, title: 'Listings',
    summary: 'Homes you are advertising. A listing moves from Draft to Review to Approved to Live, and appears on your public website once it is Live.',
    tasks: [
      { label: 'Advertise a vacant unit', hint: 'Click New listing, search for the unit and save. Rent and photos are filled in from the unit.' },
      { label: 'Publish a listing', hint: 'Open it and click Submit for review. An automatic check runs, and a listing that passes goes live.' },
      { label: 'See what is advertised right now', hint: 'Use the search box, or type Live to see only the listings that are on your website.' },
    ],
  },
  {
    id: 'listing', match: /^\/app\/leasing\/listings\/[^/]+/, title: 'A listing',
    summary: 'One advertised home. The bar at the top shows where it is in the process, and the buttons next to it are the next steps you can take.',
    tasks: [
      { label: 'Move it forward', hint: 'Use the buttons beside the title, for example Submit for review. Only the steps you are allowed to take are shown.' },
      { label: 'Write the description with AI', hint: 'Click Draft with AI, read it, then edit before you save.' },
      { label: 'Take it off the market', hint: 'Click Pause to hide it from the website without losing it. Resume brings it back.' },
    ],
  },
  {
    id: 'leads', match: /^\/app\/leasing\/leads/, title: 'Leads',
    summary: 'People who asked about a home. Each one is scored so you can start with the most likely to rent.',
    tasks: [
      { label: 'Reply to an enquiry', hint: 'Click the person. A reply is already drafted: read it, change it if you like, and send.' },
      { label: 'Find the hottest leads', hint: 'The list starts with the highest score. Click the AI score heading to flip it.' },
    ],
  },
  {
    id: 'showings', match: /^\/app\/leasing\/showings/, title: 'Showings',
    summary: 'Tours of homes that people have asked for. Confirm them, then mark whether the visitor came.',
    tasks: [
      { label: 'Confirm a tour request', hint: 'Find the row with status Requested and click Confirm.' },
      { label: 'Record what happened', hint: 'After the visit, click Mark attended or Mark no-show.' },
      { label: 'Contact the visitor', hint: 'Their email is in the Email column. Click it to write to them.' },
    ],
  },
  {
    id: 'applications', match: /^\/app\/leasing\/applications\/?$/, title: 'Applications',
    summary: 'People who applied to rent. Open one to run credit and background screening, then approve or decline.',
    tasks: [
      { label: 'Screen an applicant', hint: 'Open the application and click Run screening. It checks income, credit and background.' },
      { label: 'Approve and create the lease', hint: 'Click Approve, then Create lease on the same application.' },
    ],
  },
  {
    id: 'application', match: /^\/app\/leasing\/applications\/[^/]+/, title: 'An application',
    summary: 'Everything about one applicant: income, history, screening results and the decision.',
    tasks: [
      { label: 'Decide', hint: 'Use Approve or Reject at the top. Rejecting asks for a reason, which is kept for your records.' },
      { label: 'Ask for what is missing', hint: 'Click Request documents, or Copy upload link to send the applicant a link.' },
      { label: 'Draft the lease', hint: 'Once approved, click Create lease.' },
    ],
  },
  {
    id: 'leases', match: /^\/app\/leasing\/leases\/?$/, title: 'Leases',
    summary: 'Every lease, with the rent, the end date and how many days are left. Click a lease to send it for signing or to renew it.',
    tasks: [
      { label: 'Find leases ending soon', hint: 'The list is sorted by end date. The Days left column turns orange when 60 days or fewer remain.' },
      { label: 'Send a lease for signing', hint: 'Open the lease and click Send for signature. The resident signs, then you click Countersign and activate.' },
    ],
  },
  {
    id: 'lease', match: /^\/app\/leasing\/(leases\/[^/]+|my-lease)/, title: 'A lease',
    summary: 'The agreement, its terms and who has signed. The progress bar shows the stage, and the buttons are the steps you can take next.',
    tasks: [
      { label: 'Sign as the resident', hint: 'Type your full legal name in the Sign your lease box and click Sign lease. Staff can click Tenant signs to record it for a resident in person.' },
      { label: 'Countersign', hint: 'After the resident signs, click Countersign and activate.' },
      { label: 'Prepare a renewal', hint: 'On an active lease, click Prepare renewal.' },
    ],
  },
  {
    id: 'properties', match: /^\/app\/leasing\/properties/, title: 'Properties',
    summary: 'The buildings you manage, with their units, occupancy and rent roll.',
    tasks: [
      { label: 'Add a property', hint: 'Click Add property. You need an owner first: if you have none, add one under Owners.' },
      { label: 'Add a unit to a property', hint: 'Click Add unit, then search for the property.' },
      { label: 'Edit or archive a property', hint: 'Click its row. Archiving hides it but keeps leases and history.' },
    ],
  },
  {
    id: 'units', match: /^\/app\/leasing\/units/, title: 'Units',
    summary: 'Every apartment or home across your properties, and whether it is vacant, occupied or being prepared.',
    tasks: [
      { label: 'See what is vacant', hint: 'Type Vacant in the search box, or open Filters and type Vacant in the Status box.' },
      { label: 'Add a unit', hint: 'Click Add unit and search for the property it belongs to.' },
    ],
  },
  {
    id: 'owners', match: /^\/app\/leasing\/owners/, title: 'Owners',
    summary: 'The people or companies who own the properties you manage, with their contact details and management terms.',
    tasks: [
      { label: 'Add an owner', hint: 'Click Add owner. Leave "Create a sign-in" ticked to give them access to their own statements.' },
      { label: 'Change their fee or approval limit', hint: 'Click the owner and edit Management fee or Repair approval limit.' },
    ],
  },
  {
    id: 'payments', match: /^\/app\/payments\/?$/, title: 'Payments overview',
    summary: 'Money in and out: what is owed, what has been collected and what is overdue.',
    tasks: [
      { label: 'See who is overdue', hint: 'Open Collections for a ranked list with a suggested next step for each.', to: '/app/payments/collections' },
      { label: 'Charge a resident', hint: 'Open Invoices and click New charge.', to: '/app/payments/invoices' },
    ],
  },
  {
    id: 'invoices', match: /^\/app\/payments\/invoices/, title: 'Invoices',
    summary: 'Every charge raised to a resident, with what has been paid and how overdue it is.',
    tasks: [
      { label: 'Raise a charge', hint: 'Click New charge, search for the resident, then enter the amount and reason.' },
      { label: 'Cancel a charge', hint: 'Open the invoice and click Void invoice. A reason is required, and invoices with payments cannot be voided.' },
    ],
  },
  {
    id: 'collections', match: /^\/app\/payments\/collections/, title: 'Collections',
    summary: 'Residents with a past-due balance, ranked by how likely they are to pay late, with a recommended next step for each.',
    tasks: [
      { label: 'Remind one resident', hint: 'Click Send reminder on their row.' },
      { label: 'Remind everyone who is behind', hint: 'Click Remind everyone overdue at the top.' },
    ],
  },
  {
    id: 'payments-received', match: /^\/app\/payments\/payments/, title: 'Payments received',
    summary: 'A record of every payment, including card and bank payments and any that failed.',
    tasks: [{ label: 'Find a payment', hint: 'Search by resident name or reference. Use Filters to narrow by date or amount.' }],
  },
  {
    id: 'statements', match: /^\/app\/payments\/statements/, title: 'Owner statements',
    summary: 'Monthly statements that show each owner what was collected, what was spent and what they are paid.',
    tasks: [
      { label: 'Create a statement', hint: 'Click Generate statement, search for the owner and choose the month.' },
      { label: 'Share it with the owner', hint: 'Open the draft, check it, then click Publish to owner.' },
      { label: 'Add an expense', hint: 'Click Record expense, search for the property and describe it.' },
    ],
  },
  {
    id: 'deposits', match: /^\/app\/payments\/deposits/, title: 'Deposits',
    summary: 'Security deposits held for residents, and what has been returned or kept.',
    tasks: [{ label: 'Settle a deposit', hint: 'Open the deposit and click Settle. Enter any deductions and the rest is refunded.' }],
  },
  {
    id: 'tax', match: /^\/app\/payments\/tax/, title: 'Tax and compliance',
    summary: 'Tax collected on charges and the 1099 worksheet for owners. It is preparation for your accountant, not a filed return.',
    tasks: [{ label: 'Prepare 1099s', hint: 'Choose the year, then record each owner’s W-9 before the first payout.' }],
  },
  {
    id: 'work-orders', match: /^\/app\/maintenance\/?$/, title: 'Work orders',
    summary: 'Repair and maintenance requests, from the first report to the finished job. Urgent ones are at the top.',
    tasks: [
      { label: 'Log a problem', hint: 'Click New request, search for the resident, and describe what is wrong.' },
      { label: 'Send a vendor', hint: 'Open the request and click Assign vendor, then search for the vendor.' },
      { label: 'Close it out', hint: 'When the work is done, click Mark complete and enter the cost. Then Confirm fixed and close.' },
    ],
  },
  {
    id: 'work-order', match: /^\/app\/maintenance\/(?!vendors|recurring)[^/]+/, title: 'A work order',
    summary: 'One repair job: the problem, who is doing it, the schedule, the cost and the full history.',
    tasks: [
      { label: 'Move the job on', hint: 'Use the buttons at the top. Each step records who did it and when.' },
      { label: 'Add photos or a quote', hint: 'Use Photos and files on this page.' },
    ],
  },
  {
    id: 'vendors', match: /^\/app\/maintenance\/vendors/, title: 'Vendors',
    summary: 'The contractors you can send to jobs, ranked on rating, price, response time and current workload.',
    tasks: [
      { label: 'Add a vendor', hint: 'Click Add vendor and enter their trades and the cities they serve.' },
      { label: 'Check their insurance', hint: 'The Insurance column shows when each certificate expires.' },
    ],
  },
  {
    id: 'recurring', match: /^\/app\/maintenance\/recurring/, title: 'Preventive maintenance',
    summary: 'Inspections and filter changes that repeat on a schedule. They turn into work orders when they are due.',
    tasks: [{ label: 'Add a recurring task', hint: 'Click Add recurring task, search for the property and choose how often.' }],
  },
  {
    id: 'documents', match: /^\/app\/documents\/?$/, title: 'Files and documents',
    summary: 'Every lease, ID, invoice and certificate in one place. Tabs group files by kind, and each file is attached to the record it belongs to.',
    tasks: [
      { label: 'Upload a file', hint: 'Click Upload, choose the file, then say what it is and what it belongs to.' },
      { label: 'Get something signed', hint: 'Open a PDF and click Request signature, then search for the people.' },
      { label: 'Find an expiring certificate', hint: 'Open the Expiring tab.' },
    ],
  },
  {
    id: 'signatures', match: /^\/app\/documents\/signatures/, title: 'E-signatures',
    summary: 'Documents out for signature and the ones waiting for yours.',
    tasks: [
      { label: 'Sign a document', hint: 'Open it, read it, type your full name and tick the agreement box.' },
      { label: 'Chase someone', hint: 'Open the request and click Send reminder.' },
    ],
  },
  {
    id: 'photos', match: /^\/app\/documents\/photos/, title: 'Photos and review',
    summary: 'Property and profile photos are checked automatically. Anything the checker is unsure about waits here for a person.',
    tasks: [{ label: 'Review a photo', hint: 'Open the Needs review tab, then approve or reject with a note.' }],
  },
  {
    id: 'reports', match: /^\/app\/insights\/?$/, title: 'Reports',
    summary: 'Charts and figures across the portfolio: income, occupancy, collections and maintenance.',
    tasks: [{ label: 'Export a table', hint: 'Use Export CSV on any table to open it in a spreadsheet.' }],
  },
  {
    id: 'ai-tools', match: /^\/app\/insights\/ai/, title: 'AI tools',
    summary: 'Ready-made helpers: summarise a lease, explain an owner statement, or forecast what residents may do next.',
    tasks: [{ label: 'Summarise a lease', hint: 'Search for the lease, then click Summarise lease.' }],
  },
  {
    id: 'broker', match: /^\/app\/broker\/?$/, title: 'Broker cockpit',
    summary: 'Your daily to-do list, ranked by what is most likely to make money today.',
    tasks: [{ label: 'Work the queue', hint: 'Start at the top. Each card says why it matters and what to do.' }],
  },
  {
    id: 'opportunities', match: /^\/app\/broker\/opportunities/, title: 'Opportunities',
    summary: 'Ways to earn or save money that were spotted automatically: renewals at below-market rent, long vacancies, late payers.',
    tasks: [{ label: 'Act on one', hint: 'Open a row and use its button, for example Prepare renewal.' }],
  },
  {
    id: 'deadlines', match: /^\/app\/deadlines/, title: 'Deadlines and alerts',
    summary: 'Every date that has a legal or financial consequence: lease expiries, insurance, tax and deposit returns, in one list.',
    tasks: [{ label: 'Send due alerts now', hint: 'Click Send alerts. Anything already sent is not repeated.' }],
  },
  {
    id: 'access', match: /^\/app\/admin\/access/, title: 'Users and roles',
    summary: 'Who can sign in, and exactly what each person can see and do. Roles are bundles of permissions you can create and change.',
    tasks: [
      { label: 'Add a person', hint: 'Click Add user, enter their name and email and choose a role. You get a temporary password to give them.' },
      { label: 'Link an owner, resident or vendor', hint: 'Choose a role of that kind, then search for the record they belong to.' },
      { label: 'Create a custom role', hint: 'Open the Roles tab and click New role.' },
    ],
  },
  {
    id: 'configuration', match: /^\/app\/admin\/configuration/, title: 'Configuration',
    summary: 'Fees, approvals, screening rules, service levels and branding. Every change is versioned and can be rolled back.',
    tasks: [{ label: 'Undo a change', hint: 'Open Change history and roll back to an earlier version.' }],
  },
  {
    id: 'audit', match: /^\/app\/admin\/audit/, title: 'Audit log',
    summary: 'A permanent record of who did what and when. Entries cannot be edited or deleted.',
    tasks: [{ label: 'Find what changed', hint: 'Use the record type filter, or search for a name.' }],
  },
  {
    id: 'platform', match: /^\/app\/admin\/platform/, title: 'Platform',
    summary: 'Every company on PropertyOS. Onboard new companies, change their plan and features, and suspend or remove them.',
    tasks: [
      { label: 'Onboard a company', hint: 'Click Onboard company. You get a temporary password for its administrator.' },
      { label: 'Turn a feature on or off', hint: 'Click Edit (pencil) on a company and scroll to Features.' },
    ],
  },
  {
    id: 'plans', match: /^\/app\/admin\/plans/, title: 'Plans',
    summary: 'The plans companies can be on, their prices and what each includes. The same plans appear on the public website.',
    tasks: [{ label: 'Create a plan', hint: 'Click New plan and choose which features it includes.' }],
  },
  {
    id: 'automations', match: /^\/app\/admin\/automations/, title: 'Automation rules',
    summary: 'Routine follow-up that happens by itself: “when this happens, then do that”. Every run is logged.',
    tasks: [{ label: 'Create a rule', hint: 'Click New automation, choose the trigger, then what should happen.' }],
  },
  {
    id: 'workflows', match: /^\/app\/admin\/workflows/, title: 'Workflow designer',
    summary: 'The approval paths in the product, such as how a lease or repair moves from step to step and who can move it.',
    tasks: [{ label: 'Change who can approve', hint: 'Open a workflow and change the permission on a step.' }],
  },
  {
    id: 'profile', match: /^\/app\/profile/, title: 'Your profile',
    summary: 'Your name, job title, phone and photo, as other people see them.',
    tasks: [{ label: 'Change your photo', hint: 'Click Upload photo (or Change photo), pick a picture, then Save.' }],
  },
  {
    id: 'preferences', match: /^\/app\/preferences/, title: 'Notification preferences',
    summary: 'Choose which alerts reach you and how: in the app, by email, or both.',
    tasks: [{ label: 'Quieten an alert', hint: 'Turn off the channel you do not want for that alert.' }],
  },
];

export function guideFor(pathname: string): PageGuide | undefined {
  return PAGE_GUIDES.find((g) => g.match.test(pathname));
}

/** Plain-English meanings for words people meet in the product. */
export const GLOSSARY: { term: string; meaning: string }[] = [
  { term: 'Unit', meaning: 'One apartment or home that can be rented. A property is made of one or more units.' },
  { term: 'Listing', meaning: 'An advertisement for a vacant unit. It becomes visible on your website when it is Live.' },
  { term: 'Lead', meaning: 'A person who has asked about a home but has not applied yet.' },
  { term: 'Showing', meaning: 'A tour of a home by a prospective resident.' },
  { term: 'Application', meaning: 'A request to rent. It includes screening and an approve or decline decision.' },
  { term: 'Lease', meaning: 'The signed agreement between you and a resident, with rent, dates and rules.' },
  { term: 'Resident', meaning: 'A person who lives in a unit under a lease. Also called a tenant.' },
  { term: 'Owner', meaning: 'The person or company who owns a property and is paid from the rent you collect.' },
  { term: 'Owner statement', meaning: 'A monthly summary for an owner: money collected, money spent and what they are paid.' },
  { term: 'Work order', meaning: 'A repair or maintenance job, from report to completion.' },
  { term: 'Vendor', meaning: 'A contractor or company you send to do repairs.' },
  { term: 'Deposit', meaning: 'Money held at the start of a lease and returned, less any deductions, at the end.' },
  { term: 'Role', meaning: 'A named set of permissions, for example Property manager. People get one or more roles.' },
  { term: 'Data scope', meaning: 'How much of the company a person can see: everything, certain properties, or only their own records.' },
];
