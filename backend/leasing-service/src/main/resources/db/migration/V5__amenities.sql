-- The amenities a property can offer (Pool, Covered parking, Pets welcome ...), each with an icon, so they can be picked from a list
-- instead of typed, and shown with a picture on the public listing page.
--   org_id null      offered to every company; managed by the platform administrator
--   org_id = company the company's own additions; managed by that company
-- A property keeps the amenity's name (its "amenities" list is unchanged), so existing properties and listings keep working, and
-- an amenity that is later removed from the catalogue still shows on the properties that have it.
create table amenity (
    id          uuid primary key,
    org_id      uuid,
    name        varchar(60) not null,
    icon        varchar(40) not null,
    active      boolean not null default true,
    created_by  varchar(160),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
-- One amenity of a given name per company, and one platform-wide, ignoring capital letters.
create unique index ux_amenity_name on amenity (coalesce(org_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

alter table amenity enable row level security;
alter table amenity force row level security;
-- Everyone reads the platform-wide ones and their own company's; only the platform administrator (who bypasses row-level security) writes the platform-wide ones.
create policy amenity_read on amenity for select using (org_id is null or platform.rls_ok(org_id, null, null, null, null));
create policy amenity_write on amenity for all using (org_id is not null and platform.rls_ok(org_id, null, null, null, null))
    with check (org_id is not null and platform.rls_ok(org_id, null, null, null, null));
create policy amenity_platform on amenity for all using (coalesce(current_setting('app.bypass_rls', true), '') = 'on')
    with check (coalesce(current_setting('app.bypass_rls', true), '') = 'on');

insert into amenity (id, org_id, name, icon, created_by) values
    (gen_random_uuid(), null, 'Covered parking',     'Car',            'Platform'),
    (gen_random_uuid(), null, 'Garage',              'Warehouse',      'Platform'),
    (gen_random_uuid(), null, 'Swimming pool',       'Waves',          'Platform'),
    (gen_random_uuid(), null, 'Fitness centre',      'Dumbbell',       'Platform'),
    (gen_random_uuid(), null, 'Washer and dryer',    'WashingMachine', 'Platform'),
    (gen_random_uuid(), null, 'Pets welcome',        'PawPrint',       'Platform'),
    (gen_random_uuid(), null, 'Central air',         'Snowflake',      'Platform'),
    (gen_random_uuid(), null, 'Central heating',     'Flame',          'Platform'),
    (gen_random_uuid(), null, 'Dishwasher',          'Utensils',       'Platform'),
    (gen_random_uuid(), null, 'Microwave',           'Microwave',      'Platform'),
    (gen_random_uuid(), null, 'Refrigerator',        'Refrigerator',   'Platform'),
    (gen_random_uuid(), null, 'Private balcony',     'Sun',            'Platform'),
    (gen_random_uuid(), null, 'Storage',             'Package',        'Platform'),
    (gen_random_uuid(), null, 'Elevator',            'ArrowUpDown',    'Platform'),
    (gen_random_uuid(), null, 'High-speed internet', 'Wifi',           'Platform'),
    (gen_random_uuid(), null, 'Secure gated entry',  'ShieldCheck',    'Platform'),
    (gen_random_uuid(), null, 'EV charging',         'PlugZap',        'Platform'),
    (gen_random_uuid(), null, 'Bike storage',        'Bike',           'Platform'),
    (gen_random_uuid(), null, 'Hardwood floors',     'Layers',         'Platform'),
    (gen_random_uuid(), null, 'Clubhouse',           'Users',          'Platform'),
    (gen_random_uuid(), null, 'Rooftop lounge',      'Sofa',           'Platform'),
    (gen_random_uuid(), null, 'Garden',              'Trees',          'Platform'),
    (gen_random_uuid(), null, 'Furnished',           'BedDouble',      'Platform'),
    (gen_random_uuid(), null, 'Trash pickup',        'Trash2',         'Platform'),
    (gen_random_uuid(), null, '24-hour security',    'Lock',           'Platform'),
    (gen_random_uuid(), null, 'Playground',          'Baby',           'Platform');

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'propertyos_app') then
    grant select, insert, update, delete on amenity to propertyos_app;
  end if;
end $$;
