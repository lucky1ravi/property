-- A person's own profile picture, stored as a small image data URL (the app crops and shrinks it first).
alter table app_user add column photo text;
