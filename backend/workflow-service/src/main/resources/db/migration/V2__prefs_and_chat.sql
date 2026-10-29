-- Notification categories, per-user preferences, alert de-duplication, and conversations between parties.
alter table notification add column category varchar(20);

create table notification_pref (
    user_id  uuid not null,
    org_id   uuid not null,
    category varchar(20) not null,
    in_app   boolean not null default true,
    email    boolean not null default true,
    sms      boolean not null default true,
    updated_at timestamptz not null default now(),
    primary key (user_id, category)
);

create table notification_dedupe (
    org_id     uuid not null,
    dedupe_key varchar(200) not null,
    created_at timestamptz not null default now(),
    primary key (org_id, dedupe_key)
);

create table conversation (
    id            uuid primary key,
    org_id        uuid not null,
    subject       varchar(200) not null,
    context_type  varchar(20) not null default 'GENERAL',   -- GENERAL, WORK_ORDER, INVOICE, LEASE, APPLICATION, PROPERTY
    context_id    uuid,
    context_label varchar(200),
    status        varchar(10) not null default 'OPEN',      -- OPEN, RESOLVED
    created_by    uuid not null,
    created_at    timestamptz not null default now(),
    last_message_at timestamptz not null default now()
);
create index ix_conversation_org on conversation (org_id, last_message_at desc);
create index ix_conversation_context on conversation (context_type, context_id);

create table conversation_member (
    conversation_id uuid not null references conversation (id) on delete cascade,
    org_id          uuid not null,
    user_id         uuid not null,
    user_name       varchar(160) not null,
    persona         varchar(20),
    last_read_at    timestamptz,
    primary key (conversation_id, user_id)
);
create index ix_member_user on conversation_member (user_id);

create table message (
    id              uuid primary key,
    conversation_id uuid not null references conversation (id) on delete cascade,
    org_id          uuid not null,
    sender_id       uuid,
    sender_name     varchar(160) not null,
    sender_persona  varchar(20),
    kind            varchar(10) not null default 'USER',    -- USER, SYSTEM
    body            varchar(4000) not null,
    created_at      timestamptz not null default now()
);
create index ix_message_conv on message (conversation_id, created_at);

do $$
declare t text;
begin
  foreach t in array array['notification_pref','notification_dedupe','conversation','conversation_member','message'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    -- Organisation isolation here; who may read a conversation is enforced by membership in the service.
    execute format('create policy %I on %I using (coalesce(current_setting(''app.bypass_rls'', true), '''') = ''on'' or org_id::text = current_setting(''app.org_id'', true))', t || '_rls', t);
  end loop;
end $$;
