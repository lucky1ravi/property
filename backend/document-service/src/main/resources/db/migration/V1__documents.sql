-- Document management: files, versions, e-signature requests and signers.
-- Visibility is enforced by row-level security: a document reaches an owner, resident or vendor only when it is
-- linked to them AND explicitly shared with that party type. Staff see documents on the properties they cover.

create table blob (
    id         uuid primary key,
    org_id     uuid not null,
    content    bytea not null,
    created_at timestamptz not null default now()
);

create table document (
    id               uuid primary key,
    org_id           uuid not null,
    title            varchar(200) not null,
    category         varchar(30)  not null,
    doc_type         varchar(80),
    description      varchar(1000),
    status           varchar(12)  not null default 'ACTIVE',     -- ACTIVE, ARCHIVED, DELETED (in trash)
    source           varchar(12)  not null default 'UPLOAD',     -- UPLOAD, GENERATED, ESIGN
    entity_type      varchar(20),                                 -- APPLICATION, LEASE, WORK_ORDER, INVOICE, STATEMENT, PROPERTY, OWNER, VENDOR, TENANT
    entity_id        uuid,
    entity_label     varchar(200),
    property_id      uuid,
    property_name    varchar(160),
    unit_id          uuid,
    owner_id         uuid,
    tenant_id        uuid,
    vendor_id        uuid,
    share_owner      boolean not null default false,
    share_tenant     boolean not null default false,
    share_vendor     boolean not null default false,
    sensitive        boolean not null default false,
    tags             text[]  not null default '{}',
    expires_on       date,
    retain_until     date,
    legal_hold       boolean not null default false,
    review_status    varchar(10) not null default 'NONE',         -- NONE, PENDING, ACCEPTED, REJECTED
    review_note      varchar(500),
    signature_status varchar(10) not null default 'NONE',         -- NONE, PENDING, PARTIAL, SIGNED, DECLINED, CANCELLED
    current_version  int not null default 1,
    file_name        varchar(255),
    mime_type        varchar(120),
    size_bytes       bigint,
    sha256           varchar(64),
    uploaded_by      uuid,
    uploaded_by_name varchar(160),
    uploader_scope   varchar(12),
    deleted_at       timestamptz,
    deleted_by_name  varchar(160),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);
create index ix_document_org on document (org_id, status, updated_at desc);
create index ix_document_entity on document (entity_type, entity_id);
create index ix_document_property on document (property_id);
create index ix_document_expiry on document (expires_on) where expires_on is not null;
create index ix_document_hash on document (org_id, sha256);

create table document_version (
    id           uuid primary key,
    document_id  uuid not null references document (id) on delete cascade,
    org_id       uuid not null,
    version      int  not null,
    file_name    varchar(255) not null,
    mime_type    varchar(120) not null,
    size_bytes   bigint not null,
    sha256       varchar(64) not null,
    blob_id      uuid not null references blob (id),
    note         varchar(300),
    created_by   uuid,
    created_by_name varchar(160),
    created_at   timestamptz not null default now(),
    unique (document_id, version)
);

create table signature_request (
    id              uuid primary key,
    org_id          uuid not null,
    document_id     uuid not null references document (id) on delete cascade,
    property_id     uuid,
    title           varchar(200) not null,
    message         varchar(1000),
    status          varchar(10) not null default 'PENDING',      -- PENDING, COMPLETED, DECLINED, CANCELLED, EXPIRED
    sequential      boolean not null default false,
    document_sha256 varchar(64) not null,
    due_on          date,
    certificate_id  uuid,
    created_by      uuid,
    created_by_name varchar(160),
    created_at      timestamptz not null default now(),
    completed_at    timestamptz,
    last_reminded_at timestamptz
);
create index ix_sigreq_doc on signature_request (document_id);

create table signature_signer (
    id             uuid primary key,
    request_id     uuid not null references signature_request (id) on delete cascade,
    document_id    uuid not null,
    org_id         uuid not null,
    signer_order   int  not null default 1,
    user_id        uuid,
    party_type     varchar(10),
    party_id       uuid,
    name           varchar(160) not null,
    email          varchar(200),
    role_label     varchar(60),
    status         varchar(10) not null default 'PENDING',        -- PENDING, SIGNED, DECLINED
    viewed_at      timestamptz,
    signed_at      timestamptz,
    typed_signature varchar(160),
    consent        boolean not null default false,
    ip_address     varchar(60),
    user_agent     varchar(300),
    decline_reason varchar(500)
);
create index ix_signer_request on signature_signer (request_id);
create index ix_signer_party on signature_signer (party_id);
create index ix_signer_user on signature_signer (user_id);

-- One row per organisation once demo documents have been created.
create table seed_state (
    org_id     uuid primary key,
    seeded_at  timestamptz not null default now()
);

alter table blob enable row level security;              alter table blob force row level security;
create policy blob_rls on blob using (coalesce(current_setting('app.bypass_rls', true), '') = 'on' or org_id::text = current_setting('app.org_id', true));

alter table signature_signer enable row level security;  alter table signature_signer force row level security;
create policy signer_rls on signature_signer using (
    platform.rls_ok(org_id, null, null, null, null)
    or (org_id::text = current_setting('app.org_id', true)
        and (party_id::text = current_setting('app.party_id', true) or user_id::text = current_setting('app.user_id', true))));

alter table document enable row level security;          alter table document force row level security;
create policy document_rls on document using (
    platform.rls_ok(org_id, property_id,
        case when share_owner then owner_id end,
        case when share_tenant then tenant_id end,
        case when share_vendor then vendor_id end)
    or (org_id::text = current_setting('app.org_id', true) and uploaded_by::text = current_setting('app.user_id', true))
    or exists (select 1 from document.signature_signer s where s.document_id = document.id
               and (s.party_id::text = current_setting('app.party_id', true) or s.user_id::text = current_setting('app.user_id', true))));

alter table document_version enable row level security;  alter table document_version force row level security;
create policy version_rls on document_version using (exists (select 1 from document.document d where d.id = document_id));

alter table signature_request enable row level security; alter table signature_request force row level security;
create policy sigreq_rls on signature_request using (exists (select 1 from document.document d where d.id = document_id));

alter table seed_state enable row level security;        alter table seed_state force row level security;
create policy seed_rls on seed_state using (coalesce(current_setting('app.bypass_rls', true), '') = 'on' or org_id::text = current_setting('app.org_id', true));
