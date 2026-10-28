-- Identity and configuration: organisations, users, custom roles, permission matrix, configuration.
create table organization (
    id          uuid primary key,
    org_id      uuid not null,
    name        varchar(160) not null,
    legal_name  varchar(200),
    state       varchar(2)   not null,
    plan        varchar(20)  not null default 'GROWTH',
    status      varchar(20)  not null default 'ACTIVE',
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create table app_user (
    id            uuid primary key,
    org_id        uuid not null references organization (id),
    email         varchar(200) not null unique,
    name          varchar(160) not null,
    title         varchar(160),
    phone         varchar(40),
    party_type    varchar(20),
    party_id      uuid,
    status        varchar(20) not null default 'ACTIVE',
    mfa_enabled   boolean not null default false,
    persona       varchar(80),
    last_login_at timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
create index ix_user_org on app_user (org_id);
create index ix_user_party on app_user (party_id);

create table role (
    id          uuid primary key,
    org_id      uuid not null references organization (id),
    role_key    varchar(60),
    name        varchar(100) not null,
    description varchar(400),
    scope       varchar(20)  not null,
    system_role boolean      not null default false,
    color       varchar(20),
    field_rules jsonb        not null default '{}'::jsonb,
    version     int          not null default 1,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    unique (org_id, name)
);

create table role_permission (
    id        uuid primary key,
    org_id    uuid not null,
    role_id   uuid not null references role (id) on delete cascade,
    feature   varchar(40) not null,
    action    varchar(20) not null,
    max_amount numeric(12, 2),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (role_id, feature, action)
);

create table user_role (
    user_id uuid not null references app_user (id) on delete cascade,
    role_id uuid not null references role (id) on delete cascade,
    org_id  uuid not null,
    primary key (user_id, role_id)
);

create table user_property (
    user_id     uuid not null references app_user (id) on delete cascade,
    property_id uuid not null,
    org_id      uuid not null,
    primary key (user_id, property_id)
);

create table org_config (
    org_id     uuid primary key references organization (id),
    config     jsonb not null default '{}'::jsonb,
    version    int   not null default 1,
    updated_by varchar(160),
    updated_at timestamptz not null default now()
);

create table config_history (
    id         bigserial primary key,
    org_id     uuid not null,
    version    int  not null,
    config     jsonb not null,
    section    varchar(60),
    summary    varchar(400),
    changed_by varchar(160),
    created_at timestamptz not null default now()
);

create table login_otp (
    email      varchar(200) primary key,
    code_hash  varchar(100) not null,
    attempts   int not null default 0,
    expires_at timestamptz not null
);

-- Row-level security: every table filtered by organisation (platform admins bypass).
alter table organization enable row level security;
alter table organization force row level security;
create policy org_rls on organization using (platform.rls_ok(id, null, null, null, null));

alter table app_user enable row level security;
alter table app_user force row level security;
create policy user_rls on app_user using (platform.rls_ok(org_id, null, null, null, null)
    or id::text = current_setting('app.user_id', true));

alter table role enable row level security;
alter table role force row level security;
create policy role_rls on role using (platform.rls_ok(org_id, null, null, null, null));

alter table role_permission enable row level security;
alter table role_permission force row level security;
create policy role_perm_rls on role_permission using (platform.rls_ok(org_id, null, null, null, null));

alter table user_role enable row level security;
alter table user_role force row level security;
create policy user_role_rls on user_role using (platform.rls_ok(org_id, null, null, null, null));

alter table user_property enable row level security;
alter table user_property force row level security;
create policy user_prop_rls on user_property using (platform.rls_ok(org_id, null, null, null, null));

alter table org_config enable row level security;
alter table org_config force row level security;
create policy org_config_rls on org_config using (platform.rls_ok(org_id, null, null, null, null));

alter table config_history enable row level security;
alter table config_history force row level security;
create policy config_history_rls on config_history using (platform.rls_ok(org_id, null, null, null, null));
