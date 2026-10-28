-- Leasing: portfolio, listings, enquiries, showings, applications and leases.
create table owner (
    id                    uuid primary key,
    org_id                uuid not null,
    name                  varchar(160) not null,
    contact               varchar(160),
    email                 varchar(200),
    phone                 varchar(40),
    approval_limit        numeric(12, 2) not null default 500,
    management_fee_pct    numeric(5, 2)  not null default 8,
    bank_last4            varchar(4),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create table property (
    id          uuid primary key,
    org_id      uuid not null,
    owner_id    uuid not null references owner (id),
    name        varchar(160) not null,
    street      varchar(200) not null,
    city        varchar(100) not null,
    state       varchar(2)   not null,
    zip         varchar(10)  not null,
    lat         double precision,
    lng         double precision,
    type        varchar(30)  not null,
    year_built  int,
    amenities   jsonb not null default '[]'::jsonb,
    photo_url   varchar(500),
    status      varchar(20) not null default 'ACTIVE',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table unit (
    id            uuid primary key,
    org_id        uuid not null,
    property_id   uuid not null references property (id),
    owner_id      uuid not null,
    number        varchar(20) not null,
    beds          int not null,
    baths         numeric(3, 1) not null,
    sqft          int not null,
    market_rent   numeric(10, 2) not null,
    current_rent  numeric(10, 2),
    status        varchar(20) not null default 'VACANT',
    vacant_since  date,
    features      jsonb not null default '{}'::jsonb,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    unique (property_id, number)
);

-- Residents and applicants.
create table person (
    id               uuid primary key,
    org_id           uuid not null,
    first_name       varchar(80) not null,
    last_name        varchar(80) not null,
    email            varchar(200) not null,
    phone            varchar(40),
    annual_income    numeric(12, 2),
    employer         varchar(160),
    months_employed  int,
    ssn_last4        varchar(4),
    date_of_birth    date,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);
create index ix_person_email on person (org_id, email);

create table listing (
    id              uuid primary key,
    org_id          uuid not null,
    unit_id         uuid not null references unit (id),
    property_id     uuid not null,
    owner_id        uuid not null,
    title           varchar(200) not null,
    description     text,
    rent            numeric(10, 2) not null,
    deposit         numeric(10, 2) not null,
    available_date  date,
    photos          jsonb not null default '[]'::jsonb,
    highlights      jsonb not null default '[]'::jsonb,
    status          varchar(20) not null default 'DRAFT',
    ai_review       jsonb,
    rent_suggestion jsonb,
    ai_written      boolean not null default false,
    published_at    timestamptz,
    views           int not null default 0,
    enquiries       int not null default 0,
    created_by      varchar(160),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
create index ix_listing_status on listing (org_id, status);

create table lead (
    id              uuid primary key,
    org_id          uuid not null,
    listing_id      uuid references listing (id),
    property_id     uuid,
    owner_id        uuid,
    name            varchar(160) not null,
    email           varchar(200) not null,
    phone           varchar(40),
    message         text,
    source          varchar(40) not null default 'WEBSITE',
    status          varchar(20) not null default 'NEW',
    ai_reply        text,
    ai_score        int,
    ai_intent       varchar(40),
    responded_at    timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table showing (
    id              uuid primary key,
    org_id          uuid not null,
    listing_id      uuid not null references listing (id),
    property_id     uuid not null,
    owner_id        uuid not null,
    lead_id         uuid,
    prospect_name   varchar(160) not null,
    prospect_email  varchar(200) not null,
    broker_user_id  uuid,
    broker_name     varchar(160),
    scheduled_at    timestamptz not null,
    type            varchar(20) not null default 'BROKER_LED',
    status          varchar(20) not null default 'REQUESTED',
    notes           text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table application (
    id                uuid primary key,
    org_id            uuid not null,
    listing_id        uuid not null references listing (id),
    unit_id           uuid not null,
    property_id       uuid not null,
    owner_id          uuid not null,
    applicant_id      uuid not null references person (id),
    applicant_name    varchar(160) not null,
    applicant_email   varchar(200) not null,
    unit_label        varchar(60) not null,
    status            varchar(20) not null default 'SUBMITTED',
    monthly_income    numeric(12, 2),
    employer          varchar(160),
    months_employed   int,
    current_rent      numeric(10, 2),
    landlord_reference varchar(400),
    occupants         int not null default 1,
    pets              varchar(200),
    move_in_date      date,
    documents         jsonb not null default '[]'::jsonb,
    screening         jsonb,
    credit_score      int,
    background_clear  boolean,
    evictions         int not null default 0,
    decision_reason   varchar(600),
    decided_by        varchar(160),
    decided_at        timestamptz,
    fee_paid          boolean not null default false,
    submitted_at      timestamptz not null default now(),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);
create index ix_application_status on application (org_id, status);

create table lease (
    id                 uuid primary key,
    org_id             uuid not null,
    unit_id            uuid not null references unit (id),
    property_id        uuid not null,
    owner_id           uuid not null,
    tenant_id          uuid not null references person (id),
    application_id     uuid,
    status             varchar(20) not null default 'DRAFT',
    start_date         date not null,
    end_date           date not null,
    rent               numeric(10, 2) not null,
    deposit            numeric(10, 2) not null,
    state              varchar(2) not null,
    rules_snapshot     jsonb not null default '{}'::jsonb,
    terms              jsonb not null default '{}'::jsonb,
    ai_summary         jsonb,
    document_text      text,
    tenant_signature   varchar(160),
    tenant_signed_at   timestamptz,
    tenant_sign_ip     varchar(60),
    manager_signature  varchar(160),
    manager_signed_at  timestamptz,
    renewal            jsonb,
    renewal_notices    jsonb not null default '[]'::jsonb,
    notice_date        date,
    move_out_date      date,
    unit_label         varchar(60) not null,
    property_name      varchar(160) not null,
    property_address   varchar(300),
    tenant_name        varchar(160) not null,
    tenant_email       varchar(200),
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);
create index ix_lease_status on lease (org_id, status);
create index ix_lease_tenant on lease (tenant_id);

-- Row-level security. platform.rls_ok(org, property, owner, tenant, vendor):
-- brokers see assigned properties, owners their own, residents their own lease.
alter table owner enable row level security;       alter table owner force row level security;
create policy owner_rls on owner using (platform.rls_ok(org_id, null, id, null, null));

alter table property enable row level security;    alter table property force row level security;
create policy property_rls on property using (platform.rls_ok(org_id, id, owner_id, null, null));

alter table unit enable row level security;        alter table unit force row level security;
create policy unit_rls on unit using (platform.rls_ok(org_id, property_id, owner_id, null, null));

alter table person enable row level security;      alter table person force row level security;
create policy person_rls on person using (platform.rls_ok(org_id, null, null, id, null));

alter table listing enable row level security;     alter table listing force row level security;
create policy listing_rls on listing using (platform.rls_ok(org_id, property_id, owner_id, null, null));

alter table lead enable row level security;        alter table lead force row level security;
create policy lead_rls on lead using (platform.rls_ok(org_id, property_id, owner_id, null, null));

alter table showing enable row level security;     alter table showing force row level security;
create policy showing_rls on showing using (platform.rls_ok(org_id, property_id, owner_id, null, null));

alter table application enable row level security; alter table application force row level security;
create policy application_rls on application using (platform.rls_ok(org_id, property_id, owner_id, applicant_id, null));

alter table lease enable row level security;       alter table lease force row level security;
create policy lease_rls on lease using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
