# ADR 8: Platform operators are refused company business data

Status: accepted

## Context

The PropertyOS team must onboard companies, set plans, bill them and keep the service healthy, but customers entrust it with leases, payments and personal data. The vendor's own staff are an insider risk and a compliance question.

## Decision

- A platform administrator's token has scope `PLATFORM`. `FeatureGateFilter` refuses every company API for it with `PLATFORM_NO_COMPANY_DATA`, except a short allow-list of operator paths: identity (companies, plans, configuration, audit), the operator notification and delivery endpoints, the shared amenity list, and the AI status.
- The **delivery log** is operational: it shows counts, outcomes and a **masked** recipient (the first letter for email, the last two digits for SMS), never a subject. Opening a delivery in full requires a **reason**, which is written to **that company's own audit log**, so the customer can see that the operator looked and why.
- Suspension requires a reason, is audited, and takes effect everywhere within about ten seconds.
- The operator's menu shows only operator pages.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Operators see everything for support | Contradicts the isolation promise; large customers and auditors will not accept it |
| No way to see a delivery | Support could not diagnose "I never got the email"; an audited exception is the compromise |

## Consequences

- Support work that needs company data goes through the company's own admin or an audited reveal.
- New operator features must be added to the allow-list deliberately, and a test (`PlatformGovernanceIT`) fails if a company path becomes reachable.
- The masking is covered by integration and browser tests, after a defect where the mask hid the first letter showed why.
