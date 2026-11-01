-- What residents think of the property and the unit they live in. One rating per resident per property or unit.
create table review (
    id             uuid primary key,
    org_id         uuid not null,
    subject_type   varchar(10) not null check (subject_type in ('PROPERTY', 'UNIT')),
    subject_id     uuid not null,
    property_id    uuid not null,
    unit_id        uuid,
    owner_id       uuid,
    tenant_id      uuid not null,
    rater_user_id  uuid not null,
    rater_name     varchar(160) not null,
    stars          int not null check (stars between 1 and 5),
    comment        varchar(1000),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    unique (subject_type, subject_id, rater_user_id)
);
create index ix_review_subject on review (subject_type, subject_id);

alter table review enable row level security;
alter table review force row level security;
-- Company staff see every review on their properties, an owner those on their own properties, a resident only their own.
create policy review_rls on review using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, null));
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on review to propertyos_app;
  end if;
end $$;
