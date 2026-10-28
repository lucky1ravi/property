-- Maintenance: vendors, work orders, comments and recurring tasks.
create table vendor (
    id                uuid primary key,
    org_id            uuid not null,
    name              varchar(160) not null,
    contact           varchar(160),
    phone             varchar(40),
    email             varchar(200),
    trades            jsonb not null default '[]'::jsonb,
    cities            jsonb not null default '[]'::jsonb,
    rating            numeric(3, 2) not null default 4.0,
    jobs_completed    int not null default 0,
    avg_job_cost      numeric(10, 2) not null default 250,
    avg_response_hours numeric(5, 1) not null default 6,
    insurance_expires date,
    active            boolean not null default true,
    created_at        timestamptz not null default now()
);

create table work_order (
    id               uuid primary key,
    org_id           uuid not null,
    property_id      uuid not null,
    owner_id         uuid not null,
    unit_id          uuid,
    tenant_id        uuid,
    vendor_id        uuid,
    property_name    varchar(160) not null,
    unit_label       varchar(60),
    tenant_name      varchar(160),
    title            varchar(200) not null,
    description      text,
    category         varchar(20) not null default 'GENERAL',
    priority         varchar(12) not null default 'NORMAL',   -- EMERGENCY, HIGH, NORMAL, LOW
    status           varchar(20) not null default 'SUBMITTED',
    source           varchar(12) not null default 'TENANT',   -- TENANT, STAFF, RECURRING, INSPECTION
    estimate         numeric(10, 2),
    final_cost       numeric(10, 2),
    vendor_name      varchar(160),
    permission_to_enter boolean not null default true,
    scheduled_for    timestamptz,
    sla_due_at       timestamptz,
    sla_breached     boolean not null default false,
    ai_triage        jsonb,
    completion_notes text,
    rating           int,
    rating_comment   varchar(400),
    photos           jsonb not null default '[]'::jsonb,
    created_by       varchar(160),
    completed_at     timestamptz,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);
create index ix_wo_status on work_order (org_id, status);
create index ix_wo_vendor on work_order (vendor_id);

create table work_order_comment (
    id            uuid primary key,
    org_id        uuid not null,
    work_order_id uuid not null references work_order (id) on delete cascade,
    property_id   uuid not null,
    owner_id      uuid not null,
    tenant_id     uuid,
    vendor_id     uuid,
    author        varchar(160) not null,
    body          text not null,
    internal      boolean not null default false,
    created_at    timestamptz not null default now()
);

create table recurring_task (
    id            uuid primary key,
    org_id        uuid not null,
    property_id   uuid not null,
    owner_id      uuid not null,
    property_name varchar(160) not null,
    title         varchar(200) not null,
    category      varchar(20) not null,
    every_months  int not null,
    next_due      date not null,
    last_run      date,
    active        boolean not null default true
);

do $$
declare t text;
begin
  foreach t in array array['vendor','work_order','work_order_comment','recurring_task'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
  end loop;
end $$;

create policy vendor_rls on vendor using (platform.rls_ok(org_id, null, null, null, id));
create policy wo_rls on work_order using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, vendor_id));
create policy wo_comment_rls on work_order_comment using (platform.rls_ok(org_id, property_id, owner_id, tenant_id, vendor_id) and not (internal and current_setting('app.scope', true) in ('TENANT', 'VENDOR', 'OWNER')));
create policy recurring_rls on recurring_task using (platform.rls_ok(org_id, property_id, owner_id, null, null));
