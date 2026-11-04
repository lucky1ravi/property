-- Webhooks: a company subscribes outside systems (a CRM, Slack, Zapier) to events, and every delivery attempt is kept in a log.

create table webhook_endpoint (
    id                    uuid primary key,
    org_id                uuid not null,
    name                  varchar(120) not null,
    url                   varchar(500) not null,
    secret                varchar(80) not null,              -- signs each delivery (HMAC-SHA256). Shown to staff once, when made or rotated; never returned again
    events                text[] not null,                   -- event keys such as application.submitted
    active                boolean not null default true,
    disabled_reason       varchar(200),                      -- why it is off, when PropertyOS switched it off after repeated failures
    consecutive_failures  int not null default 0,
    last_delivery_at      timestamptz,
    created_by            varchar(160),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    check (cardinality(events) > 0)
);
create index ix_webhook_endpoint_org on webhook_endpoint (org_id, created_at desc);

create table webhook_delivery (
    id               uuid primary key,
    org_id           uuid not null,
    endpoint_id      uuid not null references webhook_endpoint (id) on delete cascade,
    event_id         varchar(40) not null,                   -- the id receivers see, e.g. evt_123; the same event has the same id at every endpoint
    event_type       varchar(40) not null,                   -- the key, e.g. lease.signed
    payload          jsonb not null,                         -- the JSON that is sent
    status           varchar(10) not null default 'PENDING' check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempts         int not null default 0,
    last_error       varchar(500),
    response_code    int,                                    -- the receiver's HTTP status on the last attempt, when it answered
    duration_ms      int,
    next_attempt_at  timestamptz,                            -- when a PENDING delivery is next tried
    last_attempt_at  timestamptz,
    delivered_at     timestamptz,
    test             boolean not null default false,         -- sent by the "send test event" button, not by something happening
    created_at       timestamptz not null default now(),
    unique (endpoint_id, event_id)
);
create index ix_webhook_delivery_due on webhook_delivery (next_attempt_at) where status = 'PENDING';
create index ix_webhook_delivery_endpoint on webhook_delivery (endpoint_id, created_at desc);
create index ix_webhook_delivery_org on webhook_delivery (org_id, created_at desc);

alter table webhook_endpoint enable row level security;
alter table webhook_endpoint force row level security;
create policy webhook_endpoint_rls on webhook_endpoint using (platform.rls_ok(org_id, null, null, null, null));
alter table webhook_delivery enable row level security;
alter table webhook_delivery force row level security;
create policy webhook_delivery_rls on webhook_delivery using (platform.rls_ok(org_id, null, null, null, null));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on webhook_endpoint, webhook_delivery to propertyos_app;
  end if;
end $$;
