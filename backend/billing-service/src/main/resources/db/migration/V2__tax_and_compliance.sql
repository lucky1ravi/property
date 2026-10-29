-- Invoice numbering, sales tax on invoices, convenience fees on payments, and owner tax profiles (W-9 / 1099 reporting).
create sequence invoice_seq start 1000;

alter table invoice add column invoice_number varchar(30);
alter table invoice add column subtotal      numeric(12, 2);
alter table invoice add column tax_rate      numeric(6, 3) not null default 0;
alter table invoice add column tax_amount    numeric(12, 2) not null default 0;
alter table invoice add column state         varchar(2);

update invoice set subtotal = amount where subtotal is null;
update invoice i set invoice_number = 'INV-' || to_char(i.created_at, 'YYYY') || '-' || lpad(n.rn::text, 6, '0')
  from (select id, row_number() over (order by created_at, id) as rn from invoice) n where n.id = i.id;
select setval('invoice_seq', greatest(1000, (select count(*) + 1000 from invoice)));

-- Rows created without explicit values (seed data, older code paths) still get a number and a subtotal.
create function invoice_defaults() returns trigger language plpgsql as $$
begin
  if new.invoice_number is null then
    new.invoice_number := 'INV-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('billing.invoice_seq')::text, 6, '0');
  end if;
  if new.subtotal is null then
    new.subtotal := new.amount - coalesce(new.tax_amount, 0);
  end if;
  return new;
end $$;
create trigger trg_invoice_defaults before insert on invoice for each row execute function invoice_defaults();
create unique index ux_invoice_number on invoice (org_id, invoice_number);

alter table payment add column fee_amount numeric(12, 2) not null default 0;
-- NACHA / card-network rules: record when the payer authorised us to charge the account.
alter table payment_method add column consent_at timestamptz;

create table owner_tax_profile (
    owner_id       uuid primary key,
    org_id         uuid not null,
    legal_name     varchar(160),
    tin_type       varchar(4),                  -- SSN or EIN; the full number is never stored here
    tin_last4      varchar(4),
    w9_received_at date,
    updated_by     varchar(160),
    updated_at     timestamptz not null default now()
);
alter table owner_tax_profile enable row level security;
alter table owner_tax_profile force row level security;
create policy owner_tax_profile_rls on owner_tax_profile using (platform.rls_ok(org_id, null, owner_id, null, null));

-- Demo continuity: owners with statements start with a W-9 on file, except the last one alphabetically so the compliance check is visible.
insert into owner_tax_profile (owner_id, org_id, legal_name, tin_type, tin_last4, w9_received_at, updated_by)
select owner_id, org_id, max(owner_name), 'SSN', lpad((abs(hashtext(owner_id::text)) % 10000)::text, 4, '0'), current_date - 90, 'Demo data'
from owner_statement s
where owner_name < (select max(owner_name) from owner_statement x where x.org_id = s.org_id)
group by owner_id, org_id
on conflict do nothing;
