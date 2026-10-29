# API documentation

Every service publishes an OpenAPI 3 spec at `/api/<service>/public/v3/api-docs`, aggregated in Swagger UI at `/swagger-ui.html` on the gateway (sign in via `POST /api/identity/public/login`, then Authorize). Summaries, tags, required permission and error responses are generated from the code by `OperationDocumenter`, so they cannot drift.

The pages below are static endpoint inventories generated from the controllers, with the permission each endpoint requires.

| Service | Endpoints | Page |
| --- | --- | --- |
| identity | 49 | [identity.md](identity.md) |
| leasing | 47 | [leasing.md](leasing.md) |
| billing | 28 | [billing.md](billing.md) |
| maintenance | 15 | [maintenance.md](maintenance.md) |
| workflow | 25 | [workflow.md](workflow.md) |
| ai | 12 | [ai.md](ai.md) |
| document | 36 | [document.md](document.md) |
