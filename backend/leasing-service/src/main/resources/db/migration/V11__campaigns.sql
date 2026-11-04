-- Marketing campaigns. A company sets one up per channel (ads, a listing site, an email blast). Leads and applications that arrive through it are tagged
-- with the campaign, either from an ad platform posting to the campaign's signed inbound address, or from the campaign's tracking link on the public site.
create table campaign (
    id             uuid primary key,
    org_id         uuid not null,
    name           varchar(160) not null,
    channel        varchar(20) not null check (channel in ('FACEBOOK_ADS', 'GOOGLE_ADS', 'ZILLOW', 'APARTMENTS', 'EMAIL', 'OTHER')),
    status         varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'PAUSED')),
    spend          numeric(12, 2) not null default 0 check (spend >= 0),
    starts_on      date,
    ends_on        date,
    public_key     varchar(40) not null unique,        -- identifies the campaign in the inbound address; not a secret
    secret         varchar(80) not null,               -- signs inbound posts (HMAC). Shown to staff once, when made or rotated; never returned again
    tracking_code  varchar(16) not null unique,        -- goes in the tracking link (?cmp=...) so website visitors are attributed; not a secret
    created_by     varchar(160),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    check (ends_on is null or starts_on is null or ends_on >= starts_on)
);
create index ix_campaign_org on campaign (org_id, created_at desc);

alter table campaign enable row level security;
alter table campaign force row level security;
-- Company staff see their own campaigns. The public inbound address looks a campaign up as the system, never as a visitor.
create policy campaign_rls on campaign using (platform.rls_ok(org_id, null, null, null, null));

-- Where a lead or application came from. Leads already have a source ("WEBSITE"); applications get one, and both can point at a campaign.
alter table lead add column campaign_id uuid references campaign (id);
alter table application add column source varchar(40) not null default 'WEBSITE';
alter table application add column campaign_id uuid references campaign (id);
create index ix_lead_campaign on lead (campaign_id) where campaign_id is not null;
create index ix_application_campaign on application (campaign_id) where campaign_id is not null;
-- Looking up "has this person already asked about this home" is how inbound posts are de-duplicated.
create index ix_lead_listing_email on lead (listing_id, lower(email));

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on campaign to propertyos_app;
  end if;
end $$;
