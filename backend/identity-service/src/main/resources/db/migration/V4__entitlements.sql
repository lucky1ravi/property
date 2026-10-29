-- Features the PropertyOS operator has switched on or off for one company, over what its plan includes.
-- A feature missing from this map follows the plan. {"ESIGN": false, "AI_ASSISTANT": true}
alter table identity.organization add column if not exists entitlements text not null default '{}';
