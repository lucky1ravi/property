-- A staff decision that overrides the AI score for one comment: SHOW publishes it, HIDE keeps it off the public page. Null means the AI score decides.
-- Who decided, when and why are kept so the decision can be audited. The star rating is never affected.
alter table review add column moderation_override varchar(4) check (moderation_override in ('SHOW', 'HIDE'));
alter table review add column override_by varchar(160);
alter table review add column override_at timestamptz;
alter table review add column override_reason varchar(300);
