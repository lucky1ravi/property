# ADR 6: Integrate services with a transactional outbox, not a broker

Status: accepted

## Context

Services must react to each other's facts: an activated lease opens a deposit, a completed repair becomes an expense, an onboarded company gets default automations. Writing to the database and then publishing to a broker is a **dual write**: if the process dies between the two, state and event disagree.

## Decision

A service publishes by inserting a row into `platform.event_log` **in the same transaction** as the change (`EventPublisher`). Each service runs an `EventPoller` every 1.5 seconds that reads events after its own `consumer_offset` per subscriber, hands them to `EventHandler` beans, and advances the offset. A handler that throws has its event written to `platform.dead_letter` and the stream moves on. Delivery is at least once, so handlers are idempotent.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Kafka, SNS and SQS, or RabbitMQ | Another system to run, secure, monitor and pay for, for volumes a database handles easily; it still needs an outbox to avoid the dual write |
| Calling services synchronously | Couples availability: one slow service stalls the caller |
| Two-phase commit | Not supported across these resources, and fragile |

## Consequences

- No change without its event, no event without its change. Events survive restarts.
- Latency is the poll interval (about 1.5 s), which suits business events.
- The event table needs housekeeping as it grows, and a stuck dead-letter row needs a person to look at it ([Operations](../OPERATIONS.md)).
- Moving to a broker later means replacing the poller only; publishers and handlers keep their interfaces.
