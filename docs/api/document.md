# Document service API

Base path `/api/document` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/document/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/document-service`: **36 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| GET | `/api/documents` | DOCUMENT:VIEW | `DocumentController.list` |
| POST | `/api/documents` | DOCUMENT:CREATE | `DocumentController.upload` |
| POST | `/api/documents/alerts/run` | ALERT:CONFIGURE | `DocumentController.runAlerts` |
| POST | `/api/documents/classify` | DOCUMENT:CREATE | `DocumentController.classify` |
| GET | `/api/documents/deadlines` | ALERT:VIEW | `DocumentController.deadlines` |
| POST | `/api/documents/export` | DOCUMENT:EXPORT | `DocumentController.export` |
| GET | `/api/documents/media` | MEDIA:UPLOAD | `MediaController.list` |
| POST | `/api/documents/media` | MEDIA:UPLOAD | `MediaController.upload` |
| GET | `/api/documents/media/settings` | MEDIA:UPLOAD | `MediaController.settings` |
| DELETE | `/api/documents/media/{id}` | MEDIA:UPLOAD | `MediaController.remove` |
| GET | `/api/documents/media/{id}` | MEDIA:UPLOAD | `MediaController.get` |
| GET | `/api/documents/media/{id}/content` | MEDIA:UPLOAD | `MediaController.content` |
| POST | `/api/documents/media/{id}/decision` | MEDIA:REVIEW | `MediaController.decide` |
| GET | `/api/documents/meta` | DOCUMENT:VIEW | `DocumentController.meta` |
| GET | `/api/documents/public/applications/{applicationId}/files` | open | `PublicDocumentController.files` |
| POST | `/api/documents/public/applications/{applicationId}/files` | open | `PublicDocumentController.upload` |
| GET | `/api/documents/public/media/{id}` | open | `MediaController.publicPhoto` |
| GET | `/api/documents/requirements` | DOCUMENT:VIEW | `DocumentController.requirements` |
| GET | `/api/documents/requirements/gaps` | DOCUMENT:VIEW | `DocumentController.gaps` |
| GET | `/api/documents/signatures` | ESIGN:VIEW | `DocumentController.signatureList` |
| GET | `/api/documents/signatures/{id}` | ESIGN:VIEW | `DocumentController.signature` |
| POST | `/api/documents/signatures/{id}/cancel` | ESIGN:CREATE | `DocumentController.cancel` |
| POST | `/api/documents/signatures/{id}/decline` | ESIGN:SIGN | `DocumentController.decline` |
| POST | `/api/documents/signatures/{id}/remind` | ESIGN:CREATE | `DocumentController.remind` |
| POST | `/api/documents/signatures/{id}/sign` | ESIGN:SIGN | `DocumentController.sign` |
| GET | `/api/documents/summary` | DOCUMENT:VIEW | `DocumentController.summary` |
| GET | `/api/documents/{id}` | DOCUMENT:VIEW | `DocumentController.get` |
| PUT | `/api/documents/{id}` | DOCUMENT:VIEW | `DocumentController.update` |
| POST | `/api/documents/{id}/actions/{action}` | DOCUMENT:VIEW | `DocumentController.action` |
| GET | `/api/documents/{id}/file` | DOCUMENT:VIEW | `DocumentController.file` |
| POST | `/api/documents/{id}/legal-hold` | DOCUMENT:CONFIGURE | `DocumentController.hold` |
| POST | `/api/documents/{id}/purge` | DOCUMENT:CONFIGURE | `DocumentController.purge` |
| POST | `/api/documents/{id}/review` | DOCUMENT:EDIT | `DocumentController.review` |
| POST | `/api/documents/{id}/signature-requests` | ESIGN:CREATE | `DocumentController.requestSignature` |
| GET | `/api/documents/{id}/signers` | ESIGN:CREATE | `DocumentController.signers` |
| POST | `/api/documents/{id}/versions` | DOCUMENT:CREATE | `DocumentController.version` |
