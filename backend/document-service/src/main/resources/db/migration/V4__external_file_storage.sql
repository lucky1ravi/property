-- Files can live in an S3 bucket instead of the row. A row with a storage_key has its bytes in the bucket and no content.
alter table blob  alter column content drop not null;
alter table blob  add column storage_key varchar(300);
alter table media alter column content drop not null;
alter table media add column storage_key varchar(300);
