-- Until now "primaryColor" was stored and shown in Configuration but nothing in the product used it, and its default was the
-- placeholder #0B2545. The product now takes on a company's primary colour, so a company that saved Branding while that
-- placeholder was the default would be recoloured navy overnight without having chosen it. Drop the placeholder from every
-- company's saved settings (and from the history that rollbacks restore from) so those companies keep the look they have today.
-- A company that chose any other colour is untouched.
update org_config
   set config = jsonb_set(config, '{branding}', (config -> 'branding') - 'primaryColor')
 where config -> 'branding' ->> 'primaryColor' = '#0B2545';

update config_history
   set config = jsonb_set(config, '{branding}', (config -> 'branding') - 'primaryColor')
 where config -> 'branding' ->> 'primaryColor' = '#0B2545';
