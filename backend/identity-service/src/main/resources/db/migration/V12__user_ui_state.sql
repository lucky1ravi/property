-- What a person has already been shown in the product: welcome and page tours, dismissed hints, the getting-started checklist.
-- Kept on the account, not in the browser, so it follows them to another device and survives clearing site data.
-- A small JSON object the app reads and merges; it holds no business data.
alter table app_user add column ui_state text not null default '{}';
