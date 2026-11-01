# Database changes: how they reach AWS, and what this release changes

Every database change PropertyOS has ever made is a numbered SQL file in the code. Nothing is applied by hand. When a service starts, it
applies the files it has not run yet, in order, and records them. A deploy to AWS is therefore also the data migration.

## How it works

| Fact | Detail |
| --- | --- |
| Where the scripts live | `backend/<service>/src/main/resources/db/migration/V<number>__<name>.sql`, one folder per service |
| Who applies them | The service itself, with Flyway, as it starts. Each service has its own schema and its own numbering |
| In which order | The deploy pipeline rolls out `identity-service` first (it creates the shared `platform` schema, the event log, the audit log and the row-level-security function), then the other services in parallel, then the gateway |
| What if one fails | The service refuses to start, ECS keeps the old task running, and the rollout stops. Nothing half-applied is left: each migration runs in one transaction |
| Can a file be changed later | **No.** Flyway stores a fingerprint of every file it has run and refuses to start if one has changed. Fix forward with a new file |
| Permissions on new tables | Granted automatically at start-up (`CommonConfig`): after migrating, each service gives the application role `propertyos_app` access to every table in its schema. Row-level security stays forced on |
| Safety checks | `node scripts/check-migrations.js` fails on a gap, a repeated number, or (with `--base <tag>`) a released migration that was changed. CI runs it on every push, and the deploy workflow runs it against each service's last-deployed tag before anything is rolled out |

## This release

All changes are additive: new tables, new indexes, and one data correction. Nothing is dropped, renamed or rewritten, so the previous version
of every service keeps working against the new database. That makes a rollback of the application safe: the new tables are simply unused.

| Service | Migration | What it does | Data it touches |
| --- | --- | --- | --- |
| identity | `V13__platform_billing.sql` | Creates `platform_invoice`, `platform_payment` and the invoice number sequence: what companies pay PropertyOS | New tables only |
| identity | `V14__branding_placeholder_colour.sql` | Removes the old placeholder primary colour `#0B2545` from `org_config` and `config_history`, so no company is recoloured navy overnight by the new "the product takes the company's colour" feature. A company that chose another colour is untouched | Updates only the companies that still hold the placeholder |
| leasing | `V4__reviews.sql` | Creates `review` (residents' ratings of their property and unit) with row-level security: staff see their properties' ratings, owners their own properties', a resident only their own | New table only |
| leasing | `V5__amenities.sql` | Creates `amenity` (the catalogue of amenities with icons: the platform's shared list and each company's own) with row-level security, and seeds 26 platform-wide amenities. Properties keep storing amenity names, so existing properties and listings are unchanged | New table, 26 seeded rows |
| maintenance | `V2__vendor_reviews.sql` | Creates `vendor_review` (ratings of vendors by staff, owners and residents) with row-level security | New table only |

No other service has a new migration in this release. The reports are queries over existing tables. The amenity migration changes no existing rows; the public search reads the existing listing, property and unit tables.

### Settings with a data effect

| Setting | Effect |
| --- | --- |
| Company logo | Stored inside the company's Branding setting (`org_config.config -> branding -> logoDataUrl`), at most about 100 KB. It is kept out of the audit history |
| `payments.tax.*`, `branding.*` | Unchanged names. The default primary colour is now `#0F3D3E`, which is the colour the product already used, so nothing changes visually for a company that never chose one |

### Before you deploy

1. **Snapshot the database.** `aws rds create-db-snapshot --db-instance-identifier <instance> --db-snapshot-identifier propertyos-before-<date>`. It takes a minute and is the only step that cannot be redone afterwards.
2. **Run the check against what is live**, from a checkout with tags fetched: `git fetch --tags && for t in $(git tag -l 'deployed/*-service'); do node scripts/check-migrations.js --base $t | tail -1; done`. The pipeline does this for you.
3. **Decide about subscription billing.** `PROPERTYOS_PLATFORM_BILLING_AUTO` is off in production by default, so the first night after deploy no company is invoiced. Turn it on (set it to `true` in the task definition, `infra/aws/ecs.tf`) when the plan prices are final, or use "Run billing now" on the Subscription payments page.
4. **Set the rent cycle's time** if 2 a.m. Central is wrong for your customers (`PROPERTYOS_BILLING_CYCLE_CRON`). The rent cycle runs by itself: it creates next month's rent invoices, charges late fees and collects autopay.

### After you deploy

Run these against the database (read-only):

```sql
-- every service applied its migrations (each row is one applied file, success = true)
select 'identity' as service, version, description, success from identity.flyway_schema_history where version::int >= 13
union all select 'leasing', version, description, success from leasing.flyway_schema_history where version::int >= 4
union all select 'maintenance', version, description, success from maintenance.flyway_schema_history where version::int >= 2
order by 1, 2;

-- the new tables exist, are protected by row-level security, and the application role can use them
select schemaname, tablename, rowsecurity from pg_tables where tablename in ('review', 'vendor_review', 'amenity');
select has_table_privilege('propertyos_app', 'leasing.review', 'insert') as leasing_review,
       has_table_privilege('propertyos_app', 'leasing.amenity', 'insert') as leasing_amenity,
       has_table_privilege('propertyos_app', 'maintenance.vendor_review', 'insert') as maintenance_vendor_review,
       has_table_privilege('propertyos_app', 'identity.platform_invoice', 'insert') as platform_invoice;

-- the platform amenity list was seeded
select count(*) as platform_amenities from leasing.amenity where org_id is null;   -- expect 26 or more

-- the placeholder colour is gone
select count(*) as still_navy from identity.org_config where config -> 'branding' ->> 'primaryColor' = '#0B2545';   -- expect 0
```

Then, as the platform administrator, open **Service health** (all eight lines running) and **Subscription payments** (the page loads and shows zero billed until billing runs).

## If you have to go back

1. **The application only.** Redeploy the previous image of any service. The new tables and the corrected colours stay, and the old code ignores them. This is the normal rollback.
2. **The database too** (only if the data is wrong, not the code): restore the snapshot from step 1 into a new instance and point the services at it. Do not delete rows by hand from the migration history tables.

## Everything the database holds today

```
node scripts/check-migrations.js
```

prints the inventory. At the time of writing: identity 14, billing 3, document 4, leasing 5, maintenance 2, workflow 2, ai 1.

## Multiple instances

Production runs more than one copy of each service (`infra/k8s/*.yaml`, `desired_count` in `infra/aws`). Two things need care when a timer fires on every copy:

- **The nightly rent cycle** takes a database lock per company (`pg_try_advisory_xact_lock`), so only one copy runs it, and autopay can never be collected twice.
- **The nightly platform billing run and the overdue alert** are safe to repeat: a company can have only one subscription invoice per month (a unique index), and an alert carries a key that stops it being sent twice.
