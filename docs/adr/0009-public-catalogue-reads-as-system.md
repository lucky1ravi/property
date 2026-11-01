# ADR 9: The public catalogue reads as the system, with explicit filters

Status: accepted

## Context

The homes site must work for visitors with no account, across every company, yet row-level security hides each company's rows from anyone who is not that company ([ADR 5](0005-row-level-security-for-tenancy.md)).

## Decision

`PublicCatalog` and `PublicService` run their reads **as the system** (`UserContextHolder.runAsSystem(null, ...)`) inside a transaction, and apply the visibility rules themselves, in one place:

- only listings whose status is `LIVE`;
- never a company on the suspended list;
- filters are validated and parameterised: values are bound and never concatenated, `%` and `_` in the keyword are escaped, and sort columns are checked against what the query returns;
- the result strips internal references (`org_id`, `owner_id`, the company reference) and adds only public fields: the company's public name, logo and contact details from its branding;
- public routes live under `/public/`, so the gateway lets them through without a token and rate-limits them per client.

## Alternatives considered

| Option | Why not |
| --- | --- |
| A policy that lets anyone read live listings | Spreads public-visibility rules into the database and widens access to the raw tables for every future query |
| A separate read model or search index | More moving parts than the data volume needs today |

## Consequences

- One class owns what the public can see, so a review of "what can a visitor read" is a review of that class and its integration test (`PublicCatalogIT`, which asserts that no internal id leaks).
- The public paths bypass row-level security by design, so they must never accept a company id from the caller or return a row without the live and not-suspended predicates.
- Adding a public field is an explicit edit, not an accident of `select *`.
