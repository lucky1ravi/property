-- A company pays to boost a live listing for a period. The boost is raised as an invoice on the platform billing side (identity.platform_invoice)
-- and starts only when that invoice is paid: PENDING until then, ACTIVE for the period, EXPIRED afterwards (or when it was never paid for).
create table listing_boost (
    id              uuid primary key,
    org_id          uuid not null,
    listing_id      uuid not null references listing (id),
    property_id     uuid not null,
    owner_id        uuid not null,
    days            int  not null check (days > 0),
    starts_at       timestamptz not null,              -- planned when requested; reset to the moment of payment when it becomes ACTIVE
    ends_at         timestamptz not null,
    amount          numeric(10, 2) not null check (amount > 0),
    status          varchar(10) not null default 'PENDING' check (status in ('PENDING', 'ACTIVE', 'EXPIRED')),
    invoice_id      uuid not null,                     -- identity.platform_invoice; a separate service owns it, so no foreign key
    ending_notified boolean not null default false,    -- the "ends in 24 hours" notice has been sent
    created_by      varchar(160),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);
-- A listing has at most one boost that is waiting for payment or running at any time.
create unique index ux_listing_boost_open on listing_boost (listing_id) where status in ('PENDING', 'ACTIVE');
create unique index ux_listing_boost_invoice on listing_boost (invoice_id);
create index ix_listing_boost_status on listing_boost (status, ends_at);
create index ix_listing_boost_org on listing_boost (org_id, created_at desc);

alter table listing_boost enable row level security;
alter table listing_boost force row level security;
-- Same visibility as the listing it belongs to: company staff, and an owner for their own properties. Residents and vendors see none.
create policy listing_boost_rls on listing_boost using (platform.rls_ok(org_id, property_id, owner_id, null, null));
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on listing_boost to propertyos_app;
  end if;
end $$;
