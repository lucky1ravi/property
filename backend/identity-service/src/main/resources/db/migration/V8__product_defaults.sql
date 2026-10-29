-- Product-level defaults the PropertyOS operator sets once for every company.
-- Layered between the built-in defaults and each company's own changes.
create table product_default (
    section     varchar(40) primary key,
    value       text        not null,
    updated_by  varchar(120) not null,
    updated_at  timestamptz not null default now()
);
