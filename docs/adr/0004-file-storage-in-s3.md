# 4. Keep uploaded files in S3, behind a storage interface

Status: accepted

## Context

Documents and photos were stored as `bytea` in PostgreSQL. That is simple and transactional, but it grows the database
and its backups with file data, loads the document service and the database on every image view, and cannot use
object-storage features such as versioning or lifecycle rules.

## Decision

The document service writes file bytes through a `FileStore` interface with two implementations:

- `DatabaseFileStore` keeps the bytes in the row (the default, for local runs and tests; no cloud account needed).
- `S3FileStore` writes to a private, encrypted, versioned S3 bucket and the row keeps only the object key (`storage_key`).

`STORAGE_TYPE` (`db` or `s3`) and `STORAGE_BUCKET` choose between them. On AWS the service signs requests with its
ECS task role, which is allowed to use that one bucket and nothing else.

Files still go through the service, not straight from the browser, so the existing permission checks, row-level
security and audit trail apply to every read. Objects are named `<company>/<area>/<id>`.

## Consequences

- The database holds metadata only on AWS, so it stays small and backups are quick.
- Rows written before the switch keep their bytes in the row and still read correctly (`storage_key` is null).
  There is no automatic copy of old rows into S3; a fresh environment starts in S3 from the first upload.
- Reads cost an S3 call. Presigned URLs would take the service out of the path, but would bypass its checks, so
  they are a later step that needs short expiries and an explicit permission decision.
- A file is written to S3 before its row is saved. If the save then fails, an unreferenced object remains; the
  bucket's versioning and lifecycle rules limit the cost, and a periodic orphan sweep is a possible follow-up.
