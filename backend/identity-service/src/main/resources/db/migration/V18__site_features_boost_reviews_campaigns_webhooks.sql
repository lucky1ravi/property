-- The features added to the plans in V15 to V17 also get a public-website description, so they show on the operator's Features page
-- and on the website's Features, Products and Plans pages (the operator can still hide or reword them).
insert into site_feature (feature_key, title, summary, product, sort_order) values
 ('BOOST',     'Listing boosts',      'Pay to lift a listing in public search for 7, 14 or 30 days, clearly labelled Sponsored.', 'LEASING',    5),
 ('REVIEWS',   'Reviews and replies', 'Residents rate and comment; staff reply in public, and abusive comments are held back.',   'LEASING',    6),
 ('CAMPAIGNS', 'Marketing campaigns', 'Tracking links and signed inbound leads, with results per campaign.',                      'LEASING',    7),
 ('WEBHOOKS',  'Webhooks',            'Send events to other systems as signed JSON, with retries and a delivery log.',            'AUTOMATION', 4)
on conflict (feature_key) do nothing;
