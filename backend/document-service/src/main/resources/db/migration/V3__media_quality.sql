-- Listing-photo quality from the same AI pass that checks safety.
alter table media add column if not exists ai_quality integer;
alter table media add column if not exists ai_caption varchar(140);
alter table media add column if not exists ai_quality_issues text[] not null default '{}';
