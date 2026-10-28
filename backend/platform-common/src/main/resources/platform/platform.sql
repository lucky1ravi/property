-- Shared platform objects used by every service. Idempotent; run at each service start.
create schema if not exists platform;

-- Restricted role used for every business transaction (SET LOCAL ROLE), so row-level
-- security applies even when the service connects as a superuser or table owner.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    begin
      create role propertyos_app nologin nobypassrls;
    exception when insufficient_privilege then
      raise notice 'Cannot create propertyos_app; relying on FORCE ROW LEVEL SECURITY with a non-superuser owner';
    end;
  end if;
end $$;

-- Transactional outbox / event log. Services insert in the same transaction as their
-- business change, so an event is never lost. Consumers track their own offsets.
-- In production this log is relayed to Kafka without changing domain code.
create table if not exists platform.event_log (
    id            bigserial primary key,
    org_id        uuid,
    event_type    varchar(80)  not null,
    aggregate_type varchar(60),
    aggregate_id  uuid,
    payload       jsonb        not null default '{}'::jsonb,
    actor_id      uuid,
    actor_name    varchar(160),
    created_at    timestamptz  not null default now()
);
create index if not exists ix_event_log_type on platform.event_log (event_type, id);
create index if not exists ix_event_log_org on platform.event_log (org_id, created_at desc);

create table if not exists platform.consumer_offset (
    subscriber varchar(80) primary key,
    last_id    bigint      not null default 0,
    updated_at timestamptz not null default now()
);

create table if not exists platform.dead_letter (
    id         bigserial primary key,
    subscriber varchar(80),
    event_id   bigint,
    error      text,
    created_at timestamptz not null default now()
);

-- Append-only audit trail of every state change, configuration change and AI decision.
create table if not exists platform.audit_event (
    id          bigserial primary key,
    org_id      uuid,
    entity_type varchar(60),
    entity_id   uuid,
    action      varchar(80),
    from_state  varchar(40),
    to_state    varchar(40),
    actor_id    uuid,
    actor_name  varchar(160),
    details     jsonb,
    created_at  timestamptz not null default now()
);
create index if not exists ix_audit_entity on platform.audit_event (entity_id, created_at);
create index if not exists ix_audit_org on platform.audit_event (org_id, created_at desc);

-- Row-level security decision used by every policy in every schema.
-- Settings are written per transaction by RlsTransactionManager from the caller's JWT.
create or replace function platform.rls_ok(p_org uuid, p_property uuid, p_owner uuid, p_tenant uuid, p_vendor uuid)
returns boolean
language sql stable
as $$
  select coalesce(current_setting('app.bypass_rls', true), '') = 'on'
      or (p_org is not null and p_org::text = current_setting('app.org_id', true) and (
             coalesce(current_setting('app.scope', true), '') = 'ORG'
          or (current_setting('app.scope', true) = 'PROPERTY'
              and (p_property is null
                   or p_property::text = any (string_to_array(coalesce(current_setting('app.property_ids', true), ''), ','))))
          or (current_setting('app.scope', true) = 'OWNER'  and p_owner  is not null and p_owner::text  = current_setting('app.party_id', true))
          or (current_setting('app.scope', true) = 'TENANT' and p_tenant is not null and p_tenant::text = current_setting('app.party_id', true))
          or (current_setting('app.scope', true) = 'VENDOR' and p_vendor is not null and p_vendor::text = current_setting('app.party_id', true))
      ))
$$;

alter table platform.audit_event enable row level security;
alter table platform.audit_event force row level security;
do $$
begin
  if not exists (select 1 from pg_policies where schemaname = 'platform' and tablename = 'audit_event' and policyname = 'audit_rls') then
    create policy audit_rls on platform.audit_event
      using (platform.rls_ok(org_id, null, null, null, null))
      with check (true);
  end if;
end $$;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant usage on schema platform to propertyos_app;
    grant select, insert, update, delete on all tables in schema platform to propertyos_app;
    grant usage, select on all sequences in schema platform to propertyos_app;
    grant execute on function platform.rls_ok(uuid, uuid, uuid, uuid, uuid) to propertyos_app;
  end if;
end $$;
