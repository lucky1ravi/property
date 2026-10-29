# ADR 2: Separate liveness from readiness, and keep neighbours out of both

Status: accepted

## Context
If a probe checks the database or other services, a short outage there makes the platform restart or remove every instance at once.

## Decision
Liveness reports only whether the process is running. Readiness adds the service's own database. Neighbour state is shown on a separate informational endpoint.

## Consequences
An outage of one service degrades only the features that need it. Instances are removed from rotation only when they cannot reach their own database.
