-- A listing made for a property with no picture used to store [null]: a list with one empty slot. It counted as "has a photo", so the listing passed the
-- "add at least one photo" check and went live showing a blank image. Keep only real picture addresses, so such a home shows the placeholder (and fails the
-- check) until a photo is added. Listings and properties that already have real pictures are not changed.
update listing
set photos = coalesce((select jsonb_agg(e) from jsonb_array_elements(photos) e where jsonb_typeof(e) = 'string' and e <> '""'::jsonb), '[]'::jsonb)
where exists (select 1 from jsonb_array_elements(photos) e where jsonb_typeof(e) <> 'string' or e = '""'::jsonb);

update property
set photos = coalesce((select jsonb_agg(e) from jsonb_array_elements(photos) e where jsonb_typeof(e) = 'string' and e <> '""'::jsonb), '[]'::jsonb)
where exists (select 1 from jsonb_array_elements(photos) e where jsonb_typeof(e) <> 'string' or e = '""'::jsonb);

update property set photo_url = null where photo_url = '';
