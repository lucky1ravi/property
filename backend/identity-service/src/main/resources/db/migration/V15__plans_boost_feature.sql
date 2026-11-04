-- Listing boosts become a plan feature (BOOST) the operator can include or leave out of a plan, or switch for one company.
-- Existing plans keep working as before: every plan that lists its features gains BOOST. A plan whose features are "all" ("*") already has it.
update plan
set features = (features::jsonb || '["BOOST"]'::jsonb)::text,
    updated_at = now()
where features::jsonb <> '["*"]'::jsonb
  and not (features::jsonb @> '["BOOST"]'::jsonb);
