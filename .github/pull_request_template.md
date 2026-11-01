## What and why

<!-- One or two sentences: the change and the reason. Say what changes for people first. -->

## How it was tested

<!-- Which tests prove it: new unit or integration tests, system tests, browser specs. Paste the result of anything you ran by hand. -->

## Checklist

- [ ] `mvn verify` passes (backend) and `npm test` and `npm run build` pass (frontend)
- [ ] New behaviour has a test; a fixed bug has a test that failed before the fix
- [ ] No secrets, tokens or personal data in the diff
- [ ] Public classes and endpoints are documented (Javadoc, so the generated API reference has a summary)
- [ ] Database changes are a new Flyway migration (never edit an applied one); tenant tables have `org_id`, forced row-level security and a policy
- [ ] User-facing messages say what happened and what to do next

## Documentation

- [ ] If behaviour, configuration, a permission, an endpoint or a database object changed, the document that describes it is updated in this pull request ([map](../docs/README.md))
- [ ] `node scripts/generate-api-docs.js` re-run if a controller changed
- [ ] New or changed diagrams parse (`npm run docs:check` in `e2e/`)
- [ ] `docs/CHANGELOG.md` has an entry; a migration is described in `docs/DATABASE-MIGRATIONS.md`
- [ ] A decision that is expensive to reverse has an ADR

## Deploying

<!-- Anything the person merging must know: a migration, a new variable, a snapshot to take first, a manual Terraform step. Merging to develop deploys. -->
