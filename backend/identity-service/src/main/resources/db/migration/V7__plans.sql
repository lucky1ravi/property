-- Plans become data the PropertyOS operator manages: what each plan costs and which features it includes.
-- This replaces the plan list that was fixed in config/defaults.json, and the site_plan presentation table.
create table plan (
    plan_key         varchar(30) primary key,
    name             varchar(60)  not null,
    tagline          varchar(200) not null default '',
    audience         varchar(160) not null default '',
    highlight        boolean      not null default false,
    visible          boolean      not null default true,
    price_per_unit   numeric(8, 2)  not null default 0,
    minimum_monthly  numeric(10, 2) not null default 0,
    features         text         not null default '[]',
    sort_order       int          not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

insert into plan (plan_key, name, tagline, audience, highlight, visible, price_per_unit, minimum_monthly, features, sort_order)
select s.plan_key, initcap(s.plan_key), s.tagline, s.audience, s.highlight, s.visible,
       coalesce(s.price_per_unit, case s.plan_key when 'STARTER' then 1.25 when 'GROWTH' then 1.75 else 2.50 end),
       coalesce(s.minimum_monthly, case s.plan_key when 'STARTER' then 125 when 'GROWTH' then 300 else 1500 end),
       case s.plan_key
         when 'STARTER' then '["LEASING","PAYMENTS","MAINTENANCE","OWNER_PORTAL","DOCUMENTS"]'
         when 'GROWTH'  then '["LEASING","PAYMENTS","MAINTENANCE","OWNER_PORTAL","VENDOR_PORTAL","AUTOMATIONS","AI_ASSISTANT","RENEWALS","RECURRING_MAINTENANCE","MESSAGING","DOCUMENTS","ESIGN"]'
         else '["*"]' end,
       s.sort_order
from site_plan s;

drop table site_plan;
alter table organization alter column plan type varchar(30);
