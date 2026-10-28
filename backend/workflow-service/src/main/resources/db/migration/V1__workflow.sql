-- Workflow designer overrides, automation rules and run history, notifications and simulated delivery log.
create table workflow_def (
    id          uuid primary key,
    org_id      uuid not null,
    key         varchar(40) not null,
    version     int not null default 1,
    definition  jsonb not null,
    updated_by  varchar(160),
    updated_at  timestamptz not null default now(),
    unique (org_id, key)
);

create table workflow_def_history (
    id          uuid primary key,
    org_id      uuid not null,
    key         varchar(40) not null,
    version     int not null,
    definition  jsonb not null,
    changed_by  varchar(160),
    changed_at  timestamptz not null default now()
);

create table automation_rule (
    id             uuid primary key,
    org_id         uuid not null,
    name           varchar(160) not null,
    description    varchar(400),
    trigger_event  varchar(60) not null,
    conditions     jsonb not null default '[]'::jsonb,
    actions        jsonb not null default '[]'::jsonb,
    minutes_saved  int not null default 5,
    enabled        boolean not null default false,
    runs           int not null default 0,
    last_run_at    timestamptz,
    created_by     varchar(160),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create table automation_run (
    id          uuid primary key,
    org_id      uuid not null,
    rule_id     uuid not null references automation_rule (id) on delete cascade,
    rule_name   varchar(160) not null,
    event_id    bigint,
    event_type  varchar(60) not null,
    status      varchar(12) not null,       -- SUCCEEDED, FAILED, SKIPPED
    detail      varchar(500),
    created_at  timestamptz not null default now()
);
create index ix_run_rule on automation_run (rule_id, created_at desc);

create table notification (
    id          uuid primary key,
    org_id      uuid not null,
    user_id     uuid not null,
    title       varchar(200) not null,
    body        varchar(600),
    link        varchar(300),
    priority    varchar(10) not null default 'NORMAL',
    read_at     timestamptz,
    created_at  timestamptz not null default now()
);
create index ix_notification_user on notification (user_id, created_at desc);

create table delivery (
    id              uuid primary key,
    org_id          uuid not null,
    notification_id uuid,
    channel         varchar(8) not null,     -- EMAIL, SMS
    recipient       varchar(200) not null,
    subject         varchar(200),
    body            varchar(600),
    status          varchar(12) not null,    -- SENT, QUEUED, SKIPPED
    note            varchar(200),
    created_at      timestamptz not null default now()
);

do $$
declare t text;
begin
  foreach t in array array['workflow_def','workflow_def_history','automation_rule','automation_run','notification','delivery'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    if t <> 'notification' then
      execute format('create policy %I on %I using (platform.rls_ok(org_id, null, null, null, null))', t || '_rls', t);
    end if;
  end loop;
end $$;

create policy notification_select on notification for select
    using (coalesce(current_setting('app.bypass_rls', true), '') = 'on'
        or (org_id::text = current_setting('app.org_id', true) and user_id::text = current_setting('app.user_id', true)));
create policy notification_insert on notification for insert
    with check (coalesce(current_setting('app.bypass_rls', true), '') = 'on' or org_id::text = current_setting('app.org_id', true));
create policy notification_update on notification for update
    using (org_id::text = current_setting('app.org_id', true) and user_id::text = current_setting('app.user_id', true));
