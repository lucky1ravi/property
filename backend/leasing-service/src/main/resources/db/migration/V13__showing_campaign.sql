-- A tour requested through a campaign's tracking link counts for the campaign, like an enquiry or an application does.
alter table showing add column source varchar(40) not null default 'WEBSITE';
alter table showing add column campaign_id uuid references campaign (id);
create index ix_showing_campaign on showing (campaign_id) where campaign_id is not null;
