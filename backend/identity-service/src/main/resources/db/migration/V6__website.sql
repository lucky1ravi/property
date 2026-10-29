-- Public website content the PropertyOS operator edits: how each plan is presented, how each feature is described,
-- and enquiries from the contact form. What a plan actually includes still comes from the plan definitions
-- (config/defaults.json), so the website and the product cannot disagree.
create table site_plan (
    plan_key         varchar(20) primary key,
    tagline          varchar(200) not null,
    audience         varchar(160) not null default '',
    highlight        boolean      not null default false,
    visible          boolean      not null default true,
    price_per_unit   numeric(8, 2),
    minimum_monthly  numeric(10, 2),
    sort_order       int          not null default 0,
    updated_at       timestamptz  not null default now()
);

create table site_feature (
    feature_key  varchar(60) primary key,
    title        varchar(100) not null,
    summary      varchar(300) not null,
    product      varchar(30)  not null,
    visible      boolean      not null default true,
    sort_order   int          not null default 0,
    updated_at   timestamptz  not null default now()
);

create table contact_request (
    id          uuid primary key,
    name        varchar(120) not null,
    email       varchar(200) not null,
    company     varchar(160),
    phone       varchar(40),
    units       int,
    topic       varchar(20)  not null default 'DEMO',
    message     varchar(2000) not null,
    status      varchar(20)  not null default 'NEW',
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);
create index ix_contact_status on contact_request (status, created_at desc);

-- Only the operator (or the system) reads enquiries, never a company's staff.
alter table contact_request enable row level security;
alter table contact_request force row level security;
create policy contact_rls on contact_request using (platform.rls_ok(null, null, null, null, null));

insert into site_plan (plan_key, tagline, audience, highlight, sort_order) values
 ('STARTER',    'Everything an independent landlord needs to rent, collect and repair.',         'Up to a few hundred doors', false, 1),
 ('GROWTH',     'Adds automation, the AI assistant, renewals and portals for owners and vendors.', 'Growing property managers',  true,  2),
 ('ENTERPRISE', 'Every feature, including broker tools, opportunities and the voice agent.',     'Large portfolios and brokerages', false, 3);

insert into site_feature (feature_key, title, summary, product, sort_order) values
 ('LEASING',               'Leasing',                'Listings, leads, showings, applications and leases in one pipeline.',         'LEASING',     1),
 ('SELF_GUIDED_SHOWINGS',  'Self-guided showings',   'Prospects tour a home on their own, with access managed for you.',            'LEASING',     2),
 ('ESIGN',                 'E-signatures',           'Send leases and addenda for signing without leaving PropertyOS.',             'LEASING',     3),
 ('RENEWALS',              'Renewals',               'Renewal offers and tracking, started before the lease ends.',                 'LEASING',     4),
 ('PAYMENTS',              'Payments',               'Invoices, rent collection, late fees within state caps and deposits.',        'RENT',        1),
 ('OWNER_PORTAL',          'Owner portal',           'Owners sign in to see their properties, statements and payouts.',             'RENT',        2),
 ('MAINTENANCE',           'Maintenance',            'Residents report in plain words; work orders, vendors and approvals follow.', 'MAINTENANCE', 1),
 ('RECURRING_MAINTENANCE', 'Preventive maintenance', 'Recurring schedules so filters, inspections and servicing never slip.',      'MAINTENANCE', 2),
 ('VENDOR_PORTAL',         'Vendor portal',          'Vendors sign in to see their jobs, quote and close them out.',                'MAINTENANCE', 3),
 ('AUTOMATIONS',           'Automations',            'Rules and a workflow designer for the steps that repeat.',                    'AUTOMATION',  1),
 ('AI_ASSISTANT',          'AI assistant',           'A chat assistant and suggestions that give reasons you can check.',           'AUTOMATION',  2),
 ('AI_VOICE_AGENT',        'AI voice agent',         'Answers calls from prospects and books showings.',                            'AUTOMATION',  3),
 ('BROKER_COCKPIT',        'Broker cockpit',         'A daily queue and productivity tools for brokers.',                           'INSIGHTS',    1),
 ('OPPORTUNITIES',         'Opportunities',          'Profit and growth insights across your portfolio.',                           'INSIGHTS',    2),
 ('MESSAGING',             'Messages',               'Conversations between residents, owners, vendors and staff.',                 'DOCUMENTS',   1),
 ('DOCUMENTS',             'Files and documents',    'A document library with uploads and photos, kept per company.',               'DOCUMENTS',   2);
