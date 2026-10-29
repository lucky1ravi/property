-- Property and profile photos. Each photo is checked by AI before it is published; low-confidence or unsafe ones wait for a person.
create table media (
    id               uuid primary key,
    org_id           uuid not null,
    kind             varchar(10) not null,                  -- PROPERTY, PROFILE
    subject_id       uuid not null,                         -- property id, or user id for a profile photo
    subject_label    varchar(200),
    property_id      uuid,
    file_name        varchar(200) not null,
    mime_type        varchar(60)  not null,
    size_bytes       bigint not null,
    sha256           varchar(64) not null,
    content          bytea not null,
    status           varchar(14) not null,                  -- PENDING_REVIEW, APPROVED, REJECTED
    decided_by       varchar(8),                            -- AI, PERSON
    ai_verdict       varchar(8),                            -- SAFE, UNSAFE, UNKNOWN
    ai_confidence    numeric(5,4),
    ai_category      varchar(20),
    ai_labels        text[] not null default '{}',
    ai_reason        varchar(500),
    ai_provider      varchar(120),
    ai_simulated     boolean not null default false,
    threshold_used   integer,
    flag_reason      varchar(300),
    reviewer_id      uuid,
    reviewer_name    varchar(120),
    decision_note    varchar(500),
    decided_at       timestamptz,
    uploaded_by      uuid not null,
    uploaded_by_name varchar(120),
    created_at       timestamptz not null default now()
);
create index ix_media_org_status on media (org_id, status, created_at desc);
create index ix_media_subject on media (subject_id);
create index ix_media_uploader on media (uploaded_by);

alter table media enable row level security; alter table media force row level security;
create policy media_rls on media using (
    coalesce(current_setting('app.bypass_rls', true), '') = 'on' or org_id::text = current_setting('app.org_id', true));
