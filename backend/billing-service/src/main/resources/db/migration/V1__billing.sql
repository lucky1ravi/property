-- Billing: invoices, payments, ledger, deposits, owner statements and expenses.
create table invoice (
    id           uuid primary key,
    org_id       uuid not null,
    property_id  uuid not null,
    owner_id     uuid not null,
    tenant_id    uuid not null,
    lease_id     uuid,
    tenant_name  varchar(160) not null,
    unit_label   varchar(100) not null,
    kind         varchar(20) not null,          -- RENT, LATE_FEE, DEPOSIT, UTILITY, APPLICATION, OTHER
    description  varchar(300) not null,
    period       varchar(7),                    -- yyyy-MM for rent
    amount       numeric(12, 2) not null,
    paid         numeric(12, 2) not null default 0,
    due_date     date not null,
    status       varchar(10) not null default 'OPEN',   -- OPEN, PARTIAL, PAID, VOID
    void_reason  varchar(300),
    created_by   varchar(160),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index ix_invoice_tenant on invoice (tenant_id, status);
create index ix_invoice_due on invoice (org_id, due_date);
create unique index ux_invoice_rent_period on invoice (lease_id, kind, period) where kind in ('RENT', 'LATE_FEE') and status <> 'VOID';

create table payment (
    id           uuid primary key,
    org_id       uuid not null,
    property_id  uuid not null,
    owner_id     uuid not null,
    tenant_id    uuid not null,
    tenant_name  varchar(160) not null,
    amount       numeric(12, 2) not null,
    method       varchar(10) not null,          -- ACH, CARD
    method_last4 varchar(4),
    status       varchar(12) not null,          -- SUCCEEDED, FAILED, REFUNDED
    reference    varchar(40) not null,
    failure_reason varchar(200),
    allocations  jsonb not null default '[]'::jsonb,
    source       varchar(12) not null default 'MANUAL',   -- MANUAL, AUTOPAY
    created_at   timestamptz not null default now()
);
create index ix_payment_tenant on payment (tenant_id, created_at desc);

create table payment_method (
    id         uuid primary key,
    org_id     uuid not null,
    tenant_id  uuid not null,
    type       varchar(10) not null,
    last4      varchar(4) not null,
    label      varchar(80) not null,
    autopay    boolean not null default false,
    created_at timestamptz not null default now()
);

create table ledger_entry (
    id          uuid primary key,
    org_id      uuid not null,
    property_id uuid not null,
    owner_id    uuid not null,
    tenant_id   uuid,
    type        varchar(16) not null,           -- CHARGE, PAYMENT, FEE, REFUND, EXPENSE, MGMT_FEE, PAYOUT
    amount      numeric(12, 2) not null,        -- positive increases what the tenant owes / what the owner earns
    memo        varchar(300),
    ref_type    varchar(20),
    ref_id      uuid,
    created_at  timestamptz not null default now()
);
create index ix_ledger_owner on ledger_entry (owner_id, created_at);

create table deposit (
    id           uuid primary key,
    org_id       uuid not null,
    property_id  uuid not null,
    owner_id     uuid not null,
    tenant_id    uuid not null,
    lease_id     uuid not null unique,
    tenant_name  varchar(160) not null,
    unit_label   varchar(100) not null,
    amount       numeric(12, 2) not null,
    held         numeric(12, 2) not null default 0,
    status       varchar(12) not null default 'PENDING', -- PENDING, HELD, REFUND_DUE, SETTLED
    deductions   jsonb not null default '[]'::jsonb,
    refund       numeric(12, 2),
    state        varchar(2),
    refund_deadline date,
    settled_at   timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create table expense (
    id           uuid primary key,
    org_id       uuid not null,
    property_id  uuid not null,
    owner_id     uuid not null,
    description  varchar(300) not null,
    category     varchar(30) not null default 'MAINTENANCE',
    amount       numeric(12, 2) not null,
    incurred_on  date not null default current_date,
    ref_id       uuid,
    created_at   timestamptz not null default now()
);

create table owner_statement (
    id            uuid primary key,
    org_id        uuid not null,
    owner_id      uuid not null,
    owner_name    varchar(160) not null,
    period        varchar(7) not null,
    rent_collected numeric(12, 2) not null,
    fees_collected numeric(12, 2) not null default 0,
    mgmt_fee      numeric(12, 2) not null,
    expenses      numeric(12, 2) not null,
    net_payout    numeric(12, 2) not null,
    lines         jsonb not null default '[]'::jsonb,
    status        varchar(12) not null default 'DRAFT',  -- DRAFT, PUBLISHED, PAID
    published_at  timestamptz,
    created_at    timestamptz not null default now(),
    unique (owner_id, period)
);

do $$
declare t text;
begin
  foreach t in array array['invoice','payment','payment_method','ledger_entry','deposit','expense','owner_statement'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
  end loop;
end $$;

create policy invoice_rls on invoice using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
create policy payment_rls on payment using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
create policy payment_method_rls on payment_method using (platform.rls_ok(org_id, null, null, tenant_id, null));
create policy ledger_rls on ledger_entry using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
create policy deposit_rls on deposit using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
create policy expense_rls on expense using (platform.rls_ok(org_id, property_id, owner_id, null, null));
create policy statement_rls on owner_statement using (platform.rls_ok(org_id, null, owner_id, null, null));
