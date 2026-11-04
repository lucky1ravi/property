-- A public reply from the company to a resident's review. One reply per review; the resident's own rating and comment are never changed by it.
-- The table's row-level security already limits who can read and write a review, so the new columns need no policy of their own.
alter table review add column reply varchar(1000);
alter table review add column reply_by varchar(160);
alter table review add column replied_at timestamptz;
