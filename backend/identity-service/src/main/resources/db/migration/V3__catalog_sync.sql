-- Remembers which newly added features were already offered to an organisation's roles, so an admin's later removal is respected.
create table catalog_sync (
    org_id  uuid not null,
    feature varchar(40) not null,
    synced_at timestamptz not null default now(),
    primary key (org_id, feature)
);
