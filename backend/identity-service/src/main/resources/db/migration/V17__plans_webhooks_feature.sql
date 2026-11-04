-- Webhooks become a plan feature (WEBHOOKS). Growth gets it; Starter does not (the operator can add it on the Plans page, or switch it for one company).
-- A plan whose features are "all" ("*") already has it.
update plan
set features = (features::jsonb || '["WEBHOOKS"]'::jsonb)::text, updated_at = now()
where plan_key = 'GROWTH' and features::jsonb <> '["*"]'::jsonb and not (features::jsonb @> '["WEBHOOKS"]'::jsonb);
