-- Two more plan features the operator can include in a plan or switch for one company:
--   REVIEWS    public replies to resident reviews and the decision to show or hide a comment. Every plan gets it, so nothing changes today.
--   CAMPAIGNS  marketing campaigns with inbound leads and tracking links. Growth gets it; Starter does not (the operator can add it on the Plans page).
-- A plan whose features are "all" ("*") already has both.
update plan
set features = (features::jsonb || '["REVIEWS"]'::jsonb)::text, updated_at = now()
where features::jsonb <> '["*"]'::jsonb and not (features::jsonb @> '["REVIEWS"]'::jsonb);

update plan
set features = (features::jsonb || '["CAMPAIGNS"]'::jsonb)::text, updated_at = now()
where plan_key = 'GROWTH' and features::jsonb <> '["*"]'::jsonb and not (features::jsonb @> '["CAMPAIGNS"]'::jsonb);
