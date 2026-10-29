# Contributing

## Workflow

1. Branch from `develop`: `feature/<short-name>` or `fix/<short-name>`.
2. Keep changes small and focused; one concern per pull request.
3. Open a pull request into `develop`. CI must pass (backend `mvn verify`, frontend build and tests).
4. Use [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`.

## Backend conventions

* Java 21, constructor injection, final fields. No field injection.
* Controllers only translate HTTP; business rules live in `service` classes; SQL lives in stores or repositories.
* One class, one responsibility. If a class passes roughly 500 lines or you need "and" to describe it, split it (see `DocumentService`, `DocumentClassifier`, `DocumentRequirements`).
* Throw `ApiException` factories (`badRequest`, `forbidden`, `notFound`, `conflict`) with a message a person can act on. Do not catch `Exception` unless the code is a deliberate resilience boundary, and say so in a comment.
* Every endpoint carries `@RequiresPermission` unless it is under `/public/`.
* Every query on tenant data runs inside a transaction so row-level security applies.
* Schema changes: new Flyway migration `V<n>__<name>.sql`; never edit an applied migration.
* Javadoc on every public class and any method whose behaviour is not obvious from its name.
* Add or update tests with the change: `*Test` for logic, `*IT` for anything crossing HTTP and the database.

## Frontend conventions

* TypeScript strict; no `any` without a reason in a comment.
* Shared components, formatters and the API client belong in `packages/ui`.
* Errors shown to people come from `friendlyMessage`, never raw server text.
* Format with Prettier (`.prettierrc.json`) and follow `.editorconfig`.

## Running checks locally

```bash
cd backend && mvn verify
cd frontend && npm test && npm run build
```

## Security

Never commit secrets. Report a vulnerability privately to the maintainers instead of opening a public issue.
