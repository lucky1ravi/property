# ADR 3: One correlation id per user action

Status: accepted

## Context
A single click crosses the gateway and several services. Support needs to follow it in the logs.

## Decision
The gateway (or the first service reached) creates `X-Correlation-Id`, accepts a caller's value only if it is 8-64 characters of letters, digits, dot, dash or underscore, puts it in the logging context of every request, returns it in the response and forwards it on every service call. One access log line is written per request.

## Consequences
Searching the log store for one id shows the whole journey. Validation stops callers from forging log lines. Spans with timing (OpenTelemetry) are a possible later addition.
