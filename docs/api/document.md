# Document service API

The document library, versions, photos and moderation, e-signature and document requirements.

| | |
| --- | --- |
| Base path | `/api/documents` through the gateway (port 8080) |
| Live contract | `/api/documents/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **36** (3 open to visitors, 0 for platform operators) |
| Source | `backend/document-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [root](#root) (2)
- [by id](#by-id) (10)
- [alerts](#alerts) (1)
- [classify](#classify) (1)
- [deadlines](#deadlines) (1)
- [export](#export) (1)
- [media](#media) (7)
- [meta](#meta) (1)
- [public](#public) (3)
- [requirements](#requirements) (2)
- [signatures](#signatures) (6)
- [summary](#summary) (1)

## root

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents` | `DOCUMENT:VIEW` | Lists documents the user may see, filtered by the query parameters. | `DocumentController.list` |
| POST | `/api/documents` | `DOCUMENT:CREATE` | Uploads a document with its details. | `DocumentController.upload` |

## by id

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/{id}` | `DOCUMENT:VIEW` | Returns one document with its versions and history. | `DocumentController.get` |
| PUT | `/api/documents/{id}` | `DOCUMENT:VIEW` | Changes a document's details and sharing. | `DocumentController.update` |
| POST | `/api/documents/{id}/actions/{action}` | `DOCUMENT:VIEW` | Archives, restores or deletes a document (deletes are soft). | `DocumentController.action` |
| GET | `/api/documents/{id}/file` | `DOCUMENT:VIEW` | Downloads a document, or shows it inline when the type is safe to preview. | `DocumentController.file` |
| POST | `/api/documents/{id}/legal-hold` | `DOCUMENT:CONFIGURE` | Puts a document on or off legal hold, which blocks deletion. | `DocumentController.hold` |
| POST | `/api/documents/{id}/purge` | `DOCUMENT:CONFIGURE` | Permanently removes a document and its stored files. | `DocumentController.purge` |
| POST | `/api/documents/{id}/review` | `DOCUMENT:EDIT` | Accepts or rejects a file; a rejection needs a reason. | `DocumentController.review` |
| POST | `/api/documents/{id}/signature-requests` | `ESIGN:CREATE` | Sends a PDF for signature to the chosen signers. | `DocumentController.requestSignature` |
| GET | `/api/documents/{id}/signers` | `ESIGN:CREATE` | Lists people who can be asked to sign a document. | `DocumentController.signers` |
| POST | `/api/documents/{id}/versions` | `DOCUMENT:CREATE` | Uploads a new version of a document. | `DocumentController.version` |

## alerts

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/documents/alerts/run` | `ALERT:CONFIGURE` | Sends due document alerts now instead of waiting for the daily timer. | `DocumentController.runAlerts` |

## classify

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/documents/classify` | `DOCUMENT:CREATE` | Suggests a category and type for a file name before upload. | `DocumentController.classify` |

## deadlines

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/deadlines` | `ALERT:VIEW` | Lists upcoming document deadlines such as expiries. | `DocumentController.deadlines` |

## export

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/documents/export` | `DOCUMENT:EXPORT` | Downloads the chosen documents as one zip file. | `DocumentController.export` |

## media

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/media` | `MEDIA:UPLOAD` | Lists photos the user may see, filtered by status or subject. | `MediaController.list` |
| POST | `/api/documents/media` | `MEDIA:UPLOAD` | Submits a property or profile photo for checking and review. | `MediaController.upload` |
| GET | `/api/documents/media/{id}` | `MEDIA:UPLOAD` | Returns one photo's details and review history. | `MediaController.get` |
| DELETE | `/api/documents/media/{id}` | `MEDIA:UPLOAD` | Removes a photo. | `MediaController.remove` |
| GET | `/api/documents/media/{id}/content` | `MEDIA:UPLOAD` | Returns the photo's image bytes. | `MediaController.content` |
| POST | `/api/documents/media/{id}/decision` | `MEDIA:REVIEW` | Approves or rejects a photo; a rejection needs a reason. | `MediaController.decide` |
| GET | `/api/documents/media/settings` | `MEDIA:UPLOAD` | Returns the photo rules, checker status and what the signed-in user may do. | `MediaController.settings` |

## meta

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/meta` | `DOCUMENT:VIEW` | Returns the categories, document types, file rules and entity types the upload form needs. | `DocumentController.meta` |

## public

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/public/applications/{applicationId}/files` | open | Public (signed link): lists what an applicant has uploaded, the checklist and the accepted document types. | `PublicDocumentController.files` |
| POST | `/api/documents/public/applications/{applicationId}/files` | open | Public (signed link): adds a file to one application. | `PublicDocumentController.upload` |
| GET | `/api/documents/public/media/{id}` | open | Anyone may fetch an approved photo: it is already published on a listing or profile. | `MediaController.publicPhoto` |

## requirements

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/requirements` | `DOCUMENT:VIEW` | Returns the checklist of required documents for a record and which are present. | `DocumentController.requirements` |
| GET | `/api/documents/requirements/gaps` | `DOCUMENT:VIEW` | Lists records that are missing required documents. | `DocumentController.gaps` |

## signatures

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/signatures` | `ESIGN:VIEW` | Lists signature requests, optionally only those waiting for the signed-in user. | `DocumentController.signatureList` |
| GET | `/api/documents/signatures/{id}` | `ESIGN:VIEW` | Returns one signature request with each signer's status. | `DocumentController.signature` |
| POST | `/api/documents/signatures/{id}/cancel` | `ESIGN:CREATE` | Cancels a signature request that is still open. | `DocumentController.cancel` |
| POST | `/api/documents/signatures/{id}/decline` | `ESIGN:SIGN` | Declines to sign, with a reason. | `DocumentController.decline` |
| POST | `/api/documents/signatures/{id}/remind` | `ESIGN:CREATE` | Re-notifies the signers of an open request. | `DocumentController.remind` |
| POST | `/api/documents/signatures/{id}/sign` | `ESIGN:SIGN` | Signs with a typed name and consent. | `DocumentController.sign` |

## summary

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/documents/summary` | `DOCUMENT:VIEW` | Returns document counts for the dashboard. | `DocumentController.summary` |
