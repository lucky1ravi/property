## What and why

<!-- One or two sentences: the change and the reason. -->

## Checklist

- [ ] `mvn verify` passes (backend) and `npm test` passes (frontend)
- [ ] New behaviour has a test; a fixed bug has a test that failed before the fix
- [ ] No secrets, tokens or personal data in the diff
- [ ] Public classes and endpoints are documented (Javadoc, OpenAPI summary or `@Operation`)
- [ ] Database changes are a new Flyway migration (never edit an applied one) and tenant tables have RLS policies
- [ ] User-facing messages say what happened and what to do next
