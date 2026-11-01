-- Ratings and comments about a vendor, from the people who dealt with them: staff, owners whose properties they worked on,
-- and residents whose repair they did. One per person per job.
create table vendor_review (
    id             uuid primary key,
    org_id         uuid not null,
    vendor_id      uuid not null references vendor (id),
    work_order_id  uuid,
    property_id    uuid,
    owner_id       uuid,
    tenant_id      uuid,
    rater_user_id  uuid not null,
    rater_name     varchar(160) not null,
    rater_role     varchar(12) not null,          -- STAFF, OWNER, RESIDENT
    stars          int not null check (stars between 1 and 5),
    comment        varchar(1000),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);
create unique index ux_vendor_review on vendor_review (vendor_id, rater_user_id, coalesce(work_order_id, '00000000-0000-0000-0000-000000000000'::uuid));
create index ix_vendor_review_vendor on vendor_review (vendor_id, created_at desc);

alter table vendor_review enable row level security;
alter table vendor_review force row level security;
create policy vendor_review_rls on vendor_review using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, vendor_id));
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on vendor_review to propertyos_app;
  end if;
end $$;
