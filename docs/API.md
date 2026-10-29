# API guide

## Swagger / OpenAPI

* Each service publishes its spec at `/api/<service>/public/v3/api-docs` (the `public` segment keeps it readable without a token).
* The gateway serves one Swagger UI at `/swagger-ui.html` with a drop-down for all seven services. Click **Authorize** and paste a JWT from `POST /api/identity/public/login`.
* `/internal/**` endpoints are excluded from the spec.
* Set `API_DOCS_ENABLED=false` to turn the spec and UI off (recommended in production).

Documentation is generated, not hand-maintained. `OperationDocumenter` fills in, for every endpoint: a summary from the method and HTTP verb, a tag from the resource path, the required permission (read from `@RequiresPermission`), the standard error responses, and the security requirement (removed for `/public/` paths). Add `@Operation` or `@Schema` on a method or DTO to override anything.

## Conventions

* JSON in and out, ISO-8601 dates, amounts in USD.
* Paths: `/api/<service>/<resource>[/{id}[/<action>]]`.
* Errors are `application/problem+json`: `{ "title", "status", "detail", "code" }`. Status codes: 400 invalid input, 401 not signed in, 403 not allowed, 404 not found (also used for other companies' records), 405 wrong method, 409 conflict with current state.
* List endpoints accept filters as query parameters.

## Known limitation

Many request bodies are `Map<String, Object>`, so Swagger shows a generic object. Moving each to a typed record gives full schemas and bean validation; it is the next planned cleanup.
