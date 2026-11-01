# ADR 7: Behaviour and lifecycles are data, layered and versioned

Status: accepted

## Context

Property managers differ: late fees, approval limits, screening rules, notification hours, state-law caps and even the steps of a lease or a repair vary by company and by state. Hard-coding them means a release for every change and a fork for every large customer.

## Decision

- **Settings** resolve through four layers: `defaults.json` shipped with the product, operator **product defaults** (an allow-listed set of sections), the company's **overrides**, then **plan gating** that turns off what the plan lacks. The result is cached per service and refreshed within seconds by a `ConfigurationChanged` event.
- Every override save is validated, **versioned** (`config_history`), audited and can be rolled back.
- **Lifecycles** (listing, application, lease, showing, work order) are JSON state machines (`workflows/*.json`) copied per company. Each transition names an action and the permission it needs. The API returns the actions available now, and the UI draws its buttons from that list.
- The admin screens render their editors from the same structure, so a new setting needs no new form.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Constants and feature flags in code | A deploy per tweak; no per-company variation |
| A rules engine product | Heavy, opaque, another system to learn and secure |
| Per-customer branches | Unmaintainable |

## Consequences

- A company or the operator changes behaviour without a release, with history and rollback.
- Validation must live with each section (colours, logo size and contact details are checked on save), or bad data reaches every page.
- Settings are untyped JSON, so tests assert the effective values per section, and plans and shipped defaults are tested to resolve as documented.
