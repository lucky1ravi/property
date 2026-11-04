-- Operator controls for listing boosts: a company's own price, promo codes, and boosts the operator grants for free.

-- A boost can now be a free grant from the operator (no invoice, amount 0) and records how its price was reached.
alter table listing_boost alter column invoice_id drop not null;
alter table listing_boost drop constraint listing_boost_amount_check;
alter table listing_boost add constraint listing_boost_amount_check check (amount >= 0);
alter table listing_boost add column list_price    numeric(10, 2);               -- the standard price for the period, before any discount
alter table listing_boost add column discount_note varchar(300);                 -- how the price was reached, in words
alter table listing_boost add column promo_code    varchar(40);
alter table listing_boost add column source        varchar(8) not null default 'PURCHASE' check (source in ('PURCHASE', 'GRANT'));
alter table listing_boost add column granted_by    varchar(160);
alter table listing_boost add column grant_reason  varchar(300);

-- A price agreed with one company for one period. It replaces the standard price for that company and period.
create table boost_company_price (
    org_id      uuid not null,
    days        int  not null check (days > 0),
    price       numeric(10, 2) not null check (price > 0),
    note        varchar(200),
    created_by  varchar(160),
    updated_at  timestamptz not null default now(),
    primary key (org_id, days)
);
alter table boost_company_price enable row level security;
alter table boost_company_price force row level security;
-- A company can read its own agreed prices (that is how a purchase finds them); only the operator, acting as the system, can change them.
create policy boost_company_price_rls on boost_company_price using (platform.rls_ok(org_id, null, null, null, null));

-- Promo codes: a percentage or a fixed amount off, optionally for one company, with an expiry and a use limit.
create table boost_promo (
    code         varchar(40) primary key,                    -- always upper case
    org_id       uuid,                                       -- null: any company may use it
    percent_off  int check (percent_off between 1 and 100),
    amount_off   numeric(10, 2) check (amount_off > 0),
    valid_from   date,
    valid_until  date,
    max_uses     int check (max_uses > 0),
    uses         int not null default 0,
    active       boolean not null default true,
    note         varchar(200),
    created_by   varchar(160),
    created_at   timestamptz not null default now(),
    check ((percent_off is not null) <> (amount_off is not null))
);
alter table boost_promo enable row level security;
alter table boost_promo force row level security;
create policy boost_promo_rls on boost_promo using (org_id is null or platform.rls_ok(org_id, null, null, null, null));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on boost_company_price, boost_promo to propertyos_app;
  end if;
end $$;
