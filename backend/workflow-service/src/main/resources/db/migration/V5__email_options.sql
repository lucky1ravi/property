-- Emails that carry a secret (a temporary password) keep their text only until they are sent, then it is erased; emails can also be held until a time
-- (a notification that arrives in a person's quiet hours goes out when they end), and the plain-text part is kept as it was sent.
alter table delivery add column text_body text;
alter table delivery add column sensitive boolean not null default false;
alter table delivery add column send_after timestamptz not null default now();
drop index if exists ix_delivery_email_queue;
create index ix_delivery_email_queue on delivery (send_after) where channel = 'EMAIL' and status = 'QUEUED' and html is not null;
