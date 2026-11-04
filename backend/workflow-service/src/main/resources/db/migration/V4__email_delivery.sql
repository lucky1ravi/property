-- Branded emails (a reply to a lead, for example) are kept as sent, and retried a few times when the mail service fails.
alter table delivery add column html text;
alter table delivery add column attempts int not null default 0;
create index ix_delivery_email_queue on delivery (created_at) where channel = 'EMAIL' and status = 'QUEUED' and html is not null;
