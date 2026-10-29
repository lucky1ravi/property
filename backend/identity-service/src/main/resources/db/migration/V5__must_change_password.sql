-- A user given a temporary password must choose their own before they can sign in.
alter table app_user add column if not exists must_change_password boolean not null default false;
