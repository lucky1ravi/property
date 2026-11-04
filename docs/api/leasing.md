# Leasing service API

Owners, properties, units, amenities, listings, leads, showings, applications, leases, reviews, reports and the public homes search.

| | |
| --- | --- |
| Base path | `/api/leasing` through the gateway (port 8080) |
| Live contract | `/api/leasing/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **101** (9 open to visitors, 15 for platform operators) |
| Source | `backend/leasing-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [alerts](#alerts) (1)
- [amenities](#amenities) (5)
- [applications](#applications) (6)
- [boosts](#boosts) (2)
- [campaigns](#campaigns) (9)
- [deadlines](#deadlines) (1)
- [leads](#leads) (2)
- [leases](#leases) (5)
- [listings](#listings) (11)
- [owners](#owners) (6)
- [platform](#platform) (15)
- [properties](#properties) (8)
- [public](#public) (9)
- [reports](#reports) (6)
- [residents](#residents) (1)
- [reviews](#reviews) (6)
- [showings](#showings) (2)
- [units](#units) (6)

## alerts

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/leasing/alerts/run` | `ALERT:CONFIGURE` | Sends due lease alerts now instead of waiting for the daily timer. | `AlertController.run` |

## amenities

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/amenities` | `PROPERTY:VIEW` | Everything the signed-in company can pick: the platform's amenities and its own, with their icons. | `AmenityController.available` |
| POST | `/api/leasing/amenities` | `PROPERTY:EDIT` | &nbsp; | `AmenityController.add` |
| PUT | `/api/leasing/amenities/{id}` | `PROPERTY:EDIT` | &nbsp; | `AmenityController.change` |
| DELETE | `/api/leasing/amenities/{id}` | `PROPERTY:EDIT` | &nbsp; | `AmenityController.remove` |
| GET | `/api/leasing/amenities/mine` | `PROPERTY:VIEW` | The company's own amenities (the ones it can change). | `AmenityController.mine` |

## applications

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/applications` | `APPLICATION:VIEW` | Lists applications, optionally filtered by status. | `LeasingController.applications` |
| GET | `/api/leasing/applications/{id}` | `APPLICATION:VIEW` | Returns one application with its screening results and allowed actions. | `LeasingController.application` |
| POST | `/api/leasing/applications/{id}/actions/{action}` | `APPLICATION:VIEW` | Moves an application through its workflow, for example approve or reject. | `LeasingController.applicationAction` |
| POST | `/api/leasing/applications/{id}/lease` | `LEASE:CREATE` | Creates a lease from an approved application. | `LeasingController.leaseFromApplication` |
| POST | `/api/leasing/applications/{id}/screen` | `APPLICATION:EDIT` | Runs screening on an application. | `LeasingController.screen` |
| POST | `/api/leasing/applications/{id}/upload-link` | `APPLICATION:EDIT` | A link the leasing team can send so the applicant uploads documents without an account. | `LeasingController.uploadLink` |

## boosts

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/boosts/options` | `BOOST:VIEW` | The boost periods and prices on offer, and what a boost does. | `BoostController.options` |
| GET | `/api/leasing/boosts/quote` | `BOOST:VIEW` | What a period would cost this company, with an optional promo code (query: days, code). | `BoostController.quote` |

## campaigns

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/campaigns` | `CAMPAIGN:VIEW` | The company's campaigns with leads, applications and cost per lead, and the keys that identify them (never the secret). | `CampaignController.list` |
| POST | `/api/leasing/campaigns` | `CAMPAIGN:CREATE` | Makes a campaign. | `CampaignController.create` |
| GET | `/api/leasing/campaigns/{id}` | `CAMPAIGN:VIEW` | One campaign with its numbers and its recent activity (never the secret). | `CampaignController.get` |
| PUT | `/api/leasing/campaigns/{id}` | `CAMPAIGN:EDIT` | Changes a campaign's name, channel, spend or dates. | `CampaignController.update` |
| POST | `/api/leasing/campaigns/{id}/pause` | `CAMPAIGN:EDIT` | Pauses a campaign: its inbound address and tracking link stop bringing in new leads. | `CampaignController.pause` |
| POST | `/api/leasing/campaigns/{id}/resume` | `CAMPAIGN:EDIT` | Resumes a paused campaign. | `CampaignController.resume` |
| POST | `/api/leasing/campaigns/{id}/rotate-key` | `CAMPAIGN:EDIT` | Replaces the signing secret (the old one stops working at once). | `CampaignController.rotate` |
| POST | `/api/leasing/campaigns/{id}/test-lead` | `CAMPAIGN:EDIT` | Sends a realistic sample lead through the campaign's own signed inbound address, for demonstrations. | `CampaignController.testLead` |
| GET | `/api/leasing/campaigns/series` | `CAMPAIGN:VIEW` | Leads over time for charts (query: campaignId for one campaign, days, bucket=day or week). | `CampaignController.series` |

## deadlines

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/deadlines` | `ALERT:VIEW` | Lists upcoming lease deadlines (expiry and renewal notices). | `AlertController.deadlines` |

## leads

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/leads` | `LISTING:VIEW` | Lists enquiries from the public site. | `LeasingController.leads` |
| POST | `/api/leasing/leads/{id}/respond` | `LISTING:EDIT` | Marks a lead as answered, using the given reply or the AI-drafted one. | `LeasingController.respond` |

## leases

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/leases` | `LEASE:VIEW` | Lists leases, optionally filtered by status. | `LeasingController.leases` |
| GET | `/api/leasing/leases/{id}` | `LEASE:VIEW` | Returns one lease with its allowed actions. | `LeasingController.lease` |
| POST | `/api/leasing/leases/{id}/actions/{action}` | `LEASE:VIEW` | Moves a lease through its workflow, for example send for signature or terminate. | `LeasingController.leaseAction` |
| POST | `/api/leasing/leases/{id}/renewal` | `LEASE:EDIT` | Offers a renewal on a lease. | `LeasingController.renewal` |
| GET | `/api/leasing/leases/mine` | `LEASE:VIEW` | Returns the signed-in resident's own lease. | `LeasingController.myLease` |

## listings

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/listings` | `LISTING:VIEW` | Lists listings, optionally filtered by status. | `LeasingController.listings` |
| POST | `/api/leasing/listings` | `LISTING:CREATE` | Creates a draft listing for a vacant unit. | `LeasingController.createListing` |
| GET | `/api/leasing/listings/{id}` | `LISTING:VIEW` | Returns one listing. | `LeasingController.listing` |
| PUT | `/api/leasing/listings/{id}` | `LISTING:EDIT` | Edits a draft listing. | `LeasingController.updateListing` |
| DELETE | `/api/leasing/listings/{id}` | `LISTING:DELETE` | Creates a draft listing for a unit. | `LeasingController.deleteListing` |
| POST | `/api/leasing/listings/{id}/actions/{action}` | `LISTING:VIEW` | Moves a listing through its workflow, for example submit, approve or unpublish. | `LeasingController.listingAction` |
| POST | `/api/leasing/listings/{id}/ai-assist` | `LISTING:EDIT` | Suggests a rent from comparable units (reduced after 30 days vacant) and drafts listing copy with AI, falling back to rules when no model is available. | `LeasingController.aiAssist` |
| GET | `/api/leasing/listings/{id}/boosts` | `BOOST:VIEW` | A listing's boosts, newest first: waiting for payment, running, or over. | `BoostController.forListing` |
| POST | `/api/leasing/listings/{id}/boosts` | `BOOST:CREATE` | Buys a boost (needs BOOST:CREATE, held by company administrators by default) for a live listing. | `BoostController.boost` |
| GET | `/api/leasing/listings/{id}/ranking` | `LISTING:VIEW` | Explains where a listing ranks in public search: its score out of 100 and the points each factor adds. | `LeasingController.ranking` |
| GET | `/api/leasing/listings/ranking` | `LISTING:VIEW` | Your company's listings with their ranking score, where each live one sits on the public site and what would help most. | `LeasingController.rankingOverview` |

## owners

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/owners` | `OWNER:VIEW` | Lists owners with their properties and fee terms. | `LeasingController.owners` |
| POST | `/api/leasing/owners` | `OWNER:CREATE` | Adds a property owner. | `LeasingController.createOwner` |
| PUT | `/api/leasing/owners/{id}` | `OWNER:EDIT` | Changes an owner's details and management terms. | `LeasingController.updateOwner` |
| DELETE | `/api/leasing/owners/{id}` | `OWNER:DELETE` | Archives an owner who has no properties. | `LeasingController.archiveOwner` |
| POST | `/api/leasing/owners/{id}/restore` | `OWNER:DELETE` | Brings an archived owner back. | `LeasingController.restoreOwner` |
| GET | `/api/leasing/owners/archived` | `OWNER:DELETE` | Lists archived owners. | `LeasingController.archivedOwners` |

## platform

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/platform/amenities` | `PLATFORM:VIEW` | &nbsp; | `AmenityController.platformList` |
| POST | `/api/leasing/platform/amenities` | `PLATFORM:CONFIGURE` | &nbsp; | `AmenityController.platformAdd` |
| PUT | `/api/leasing/platform/amenities/{id}` | `PLATFORM:CONFIGURE` | &nbsp; | `AmenityController.platformChange` |
| DELETE | `/api/leasing/platform/amenities/{id}` | `PLATFORM:CONFIGURE` | &nbsp; | `AmenityController.platformRemove` |
| GET | `/api/leasing/platform/boosts` | `PLATFORM:VIEW` | Every company's boosts, newest first (paged: page, size, sort, dir, q). | `BoostAdminController.list` |
| POST | `/api/leasing/platform/boosts/{id}/cancel` | `PLATFORM:CONFIGURE` | Ends a boost now. | `BoostAdminController.cancel` |
| GET | `/api/leasing/platform/boosts/eligible` | `PLATFORM:VIEW` | Live listings that could be given a free boost, matching the search text (query: q). | `BoostAdminController.eligible` |
| POST | `/api/leasing/platform/boosts/grant` | `PLATFORM:CONFIGURE` | Gives a live listing a free boost starting now. | `BoostAdminController.grant` |
| GET | `/api/leasing/platform/boosts/overview` | `PLATFORM:VIEW` | Boosts running, waiting for payment, granted for free and ending within 3 days. | `BoostAdminController.overview` |
| GET | `/api/leasing/platform/boosts/prices` | `PLATFORM:VIEW` | Prices agreed with individual companies. | `BoostAdminController.prices` |
| PUT | `/api/leasing/platform/boosts/prices` | `PLATFORM:CONFIGURE` | Sets what one company pays for one period. | `BoostAdminController.setPrice` |
| DELETE | `/api/leasing/platform/boosts/prices` | `PLATFORM:CONFIGURE` | Takes a company back to the standard price for a period (query: orgId, days). | `BoostAdminController.removePrice` |
| GET | `/api/leasing/platform/boosts/promos` | `PLATFORM:VIEW` | Promo codes with their use counts. | `BoostAdminController.promos` |
| POST | `/api/leasing/platform/boosts/promos` | `PLATFORM:CONFIGURE` | Makes a promo code. | `BoostAdminController.createPromo` |
| PUT | `/api/leasing/platform/boosts/promos/{code}` | `PLATFORM:CONFIGURE` | Switches a promo code on or off. | `BoostAdminController.promoActive` |

## properties

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/properties` | `PROPERTY:VIEW` | Lists properties with occupancy figures. | `LeasingController.properties` |
| POST | `/api/leasing/properties` | `PROPERTY:CREATE` | Creates a property. | `LeasingController.createProperty` |
| GET | `/api/leasing/properties/{id}` | `PROPERTY:VIEW` | Returns one property with its units and owner. | `LeasingController.property` |
| PUT | `/api/leasing/properties/{id}` | `PROPERTY:EDIT` | Changes a property's details. | `LeasingController.updateProperty` |
| DELETE | `/api/leasing/properties/{id}` | `PROPERTY:DELETE` | Archives a property with no occupied units or open listings. | `LeasingController.archiveProperty` |
| POST | `/api/leasing/properties/{id}/restore` | `PROPERTY:DELETE` | Brings an archived property back, with its units. | `LeasingController.restoreProperty` |
| POST | `/api/leasing/properties/{id}/units` | `PROPERTY:CREATE` | Adds a unit to a property. | `LeasingController.createUnit` |
| GET | `/api/leasing/properties/archived` | `PROPERTY:DELETE` | Lists archived properties. | `LeasingController.archivedProperties` |

## public

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/public/facets` | open | Public: the choices worth offering in the filters (cities, types, amenities with icons, rent and size ranges). | `PublicController.facets` |
| POST | `/api/leasing/public/inbound/{campaignKey}` | open | Public, signed: a campaign's inbound address. | `PublicController.inbound` |
| GET | `/api/leasing/public/listings` | open | Public: lists live rentals one home at a time. | `PublicController.listings` |
| GET | `/api/leasing/public/listings/{id}` | open | Public: returns one live rental. | `PublicController.listing` |
| POST | `/api/leasing/public/listings/{id}/applications` | open | Public: submits a rental application. | `PublicController.apply` |
| POST | `/api/leasing/public/listings/{id}/enquiries` | open | Public: records an enquiry about a rental as a lead. | `PublicController.enquire` |
| GET | `/api/leasing/public/listings/{id}/reviews` | open | Public: the resident ratings and written comments for a rental's property and unit. | `PublicController.reviews` |
| POST | `/api/leasing/public/listings/{id}/showings` | open | Public: requests a showing of a rental. | `PublicController.showing` |
| GET | `/api/leasing/public/properties` | open | Public: lists properties that have matching homes available, each with those homes inside it. | `PublicController.properties` |

## reports

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/reports/expirations` | `REPORT:VIEW` | Leases ending in the next days days (default 120), soonest first, with whether a renewal has been offered. | `LeasingReportController.expirations` |
| GET | `/api/leasing/reports/funnel` | `REPORT:VIEW` | How enquiries turn into leases, by property, for a period (default the last 90 days): enquiries, tours, applications, approvals and signed leases. | `LeasingReportController.funnel` |
| GET | `/api/leasing/reports/occupancy` | `REPORT:VIEW` | One row per property: how full it is, what the empty units cost each month, and how long they have stood empty. | `LeasingReportController.occupancy` |
| GET | `/api/leasing/reports/ratings` | `REPORT:VIEW` | What residents say, by property and unit: average, number of ratings and the latest comment. | `LeasingReportController.ratings` |
| GET | `/api/leasing/reports/rent-roll` | `REPORT:VIEW` | One row per unit: who is in it, the lease dates, the rent against what the unit could earn, and the gap. | `LeasingReportController.rentRoll` |
| GET | `/api/leasing/reports/vacancies` | `REPORT:VIEW` | Units standing empty, longest first. | `LeasingReportController.vacancies` |

## residents

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/residents` | `USER:VIEW` | Lists residents (people with a lease) for linking a sign-in to them. | `LeasingController.residents` |

## reviews

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| PUT | `/api/leasing/reviews` | `LEASE:VIEW` | Rates the property or the unit, or changes the rating. | `ReviewController.rate` |
| PUT | `/api/leasing/reviews/{id}/moderation` | `REVIEW:APPROVE` | Staff: shows or hides one comment on the public page whatever the AI scored it, or hands the decision back to the AI. | `ReviewController.moderate` |
| PUT | `/api/leasing/reviews/{id}/reply` | `REVIEW:EDIT` | Staff: the company's public reply to a review. | `ReviewController.reply` |
| GET | `/api/leasing/reviews/{type}/{id}` | `LEASE:VIEW` | The ratings of a property or unit that the signed-in person may see. | `ReviewController.list` |
| DELETE | `/api/leasing/reviews/{type}/{id}` | `LEASE:VIEW` | Takes back the resident's own rating. | `ReviewController.withdraw` |
| GET | `/api/leasing/reviews/mine` | `LEASE:VIEW` | A resident's home as they can rate it, with the rating they already gave. | `ReviewController.mine` |

## showings

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/showings` | `SHOWING:VIEW` | Lists showings. | `LeasingController.showings` |
| POST | `/api/leasing/showings/{id}/actions/{action}` | `SHOWING:EDIT` | Moves a showing through its workflow, for example confirm or cancel. | `LeasingController.showingAction` |

## units

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/leasing/units` | `PROPERTY:VIEW` | Lists units, optionally only those with a status such as VACANT. | `LeasingController.units` |
| PUT | `/api/leasing/units/{id}` | `PROPERTY:EDIT` | Changes a unit's number, size or rent. | `LeasingController.updateUnit` |
| DELETE | `/api/leasing/units/{id}` | `PROPERTY:DELETE` | Archives a vacant unit with no open listing. | `LeasingController.archiveUnit` |
| PUT | `/api/leasing/units/{id}/market-rent` | `PROPERTY:EDIT` | Changes a unit's market rent. | `LeasingController.marketRent` |
| POST | `/api/leasing/units/{id}/restore` | `PROPERTY:DELETE` | Brings an archived unit back. | `LeasingController.restoreUnit` |
| GET | `/api/leasing/units/archived` | `PROPERTY:DELETE` | Lists archived units. | `LeasingController.archivedUnits` |
