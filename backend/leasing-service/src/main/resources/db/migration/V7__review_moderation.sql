-- AI moderation of a resident's written comment. The score (0 to 100) decides whether the comment is shown on the public listing page; the star
-- rating always counts. A comment with no score yet (older rows) is scored the first time it would be shown.
alter table review add column ai_score int check (ai_score between 0 and 100);
alter table review add column ai_source varchar(10);
alter table review add column ai_reasons text;
