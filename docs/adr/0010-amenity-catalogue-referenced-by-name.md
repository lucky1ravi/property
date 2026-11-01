# ADR 10: Amenities are a catalogue that properties reference by name

Status: accepted

## Context

Amenities were free text, one per line. That produced "Pool", "pool", "Swimming Pool" and typos, gave no icons, and made a useful search filter impossible. The product needs a pick-list with icons that companies can extend, and a shared base list the platform maintains.

## Decision

- A table `leasing.amenity` (name, icon, active) holds **platform-wide** entries (`org_id` null, 26 seeded, managed by the platform administrator) and **company** entries. There is one name per company and one platform-wide, ignoring case, and a company cannot repeat a platform name.
- A property keeps a list of amenity **names** (`property.amenities`), with no foreign key.
- Icons are chosen from a fixed set the front end draws, so a stored icon always renders. A name with no catalogue entry (typed earlier, or later removed) gets an icon guessed from its words.
- The search filters by name, and a facet endpoint returns each amenity with its icon and how many available homes have it.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Link properties to amenity ids | Renaming or deleting an entry would change or break existing properties, and it needs a backfill from free text |
| Keep free text, add icons by keyword only | No consistent search and no company-managed list |
| Any icon from the whole library | Ships hundreds of icons the product never uses, and an unknown name would render nothing |

## Consequences

- Existing properties keep working unchanged; the migration adds a table and seeds rows, nothing else.
- Removing a catalogue entry never alters a property; a renamed concept needs the property edited.
- The shared list is a product asset the operator curates; companies add their own without a request to support.
