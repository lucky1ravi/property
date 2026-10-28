-- AI audit trail and confirmable actions proposed by the assistant.
create table ai_audit (
    id            uuid primary key,
    org_id        uuid not null,
    user_id       uuid,
    user_name     varchar(160),
    kind          varchar(20) not null,         -- CHAT, CAPABILITY, ACTION
    capability    varchar(40),
    prompt        varchar(2000),
    tools_used    jsonb not null default '[]'::jsonb,
    provider      varchar(60),
    input_tokens  int not null default 0,
    output_tokens int not null default 0,
    blocked       boolean not null default false,
    block_reason  varchar(200),
    fallback      boolean not null default false,
    latency_ms    int,
    created_at    timestamptz not null default now()
);
create index ix_ai_audit_org on ai_audit (org_id, created_at desc);

create table pending_action (
    id          uuid primary key,
    org_id      uuid not null,
    user_id     uuid not null,
    tool        varchar(60) not null,
    args        jsonb not null,
    summary     varchar(400) not null,
    status      varchar(12) not null default 'PENDING',   -- PENDING, CONFIRMED, CANCELLED, EXPIRED
    result      varchar(600),
    created_at  timestamptz not null default now()
);

alter table ai_audit enable row level security;      alter table ai_audit force row level security;
alter table pending_action enable row level security; alter table pending_action force row level security;
create policy ai_audit_rls on ai_audit using (platform.rls_ok(org_id, null, null, null, null) or
    (org_id::text = current_setting('app.org_id', true) and user_id::text = current_setting('app.user_id', true)));
create policy pending_rls on pending_action using (org_id::text = current_setting('app.org_id', true) and user_id::text = current_setting('app.user_id', true));
