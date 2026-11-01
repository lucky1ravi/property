-- What each company pays PropertyOS for its subscription: monthly invoices, the payments against them, and a running number.
create sequence platform_invoice_seq start 1;

create table platform_invoice (
    id           uuid primary key,
    org_id       uuid not null references organization (id),
    number       varchar(24) not null unique,
    period       varchar(7),                         -- yyyy-MM for the monthly subscription, null for a one-off charge
    kind         varchar(14) not null default 'SUBSCRIPTION',   -- SUBSCRIPTION, ONE_OFF
    plan         varchar(40),
    units        integer,                            -- units managed when the invoice was made
    unit_price   numeric(8, 2),
    minimum      numeric(10, 2),
    description  varchar(300) not null,
    amount       numeric(12, 2) not null,
    tax_amount   numeric(12, 2) not null default 0,
    paid         numeric(12, 2) not null default 0,
    due_date     date not null,
    status       varchar(10) not null default 'OPEN', -- OPEN, PARTIAL, PAID, VOID
    void_reason  varchar(300),
    created_by   varchar(160),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index ix_platform_invoice_org on platform_invoice (org_id, created_at desc);
create index ix_platform_invoice_due on platform_invoice (status, due_date);
-- One subscription invoice per company per month, however many times the monthly run is repeated.
create unique index ux_platform_invoice_period on platform_invoice (org_id, period) where kind = 'SUBSCRIPTION' and status <> 'VOID';

create table platform_payment (
    id           uuid primary key,
    org_id       uuid not null references organization (id),
    invoice_id   uuid not null references platform_invoice (id),
    amount       numeric(12, 2) not null,
    method       varchar(10) not null,               -- CARD, ACH, WIRE, CHEQUE, CREDIT
    last4        varchar(4),
    reference    varchar(40) not null,
    status       varchar(10) not null,               -- SUCCEEDED, FAILED
    failure_reason varchar(200),
    note         varchar(300),
    recorded_by  varchar(160),
    created_at   timestamptz not null default now()
);
create index ix_platform_payment_org on platform_payment (org_id, created_at desc);
create index ix_platform_payment_invoice on platform_payment (invoice_id);
