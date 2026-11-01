# Journeys and workflows the end-to-end suite walks through

This is the map of what the Playwright suite proves, drawn as diagrams. Each diagram names the people ("personas") involved and the test id that covers it, so you can run it with `npx playwright test -g "JRNY-08"`. The diagrams are [Mermaid](https://mermaid.js.org); GitHub, VS Code (Markdown Preview Mermaid Support) and most documentation tools draw them.

How to read the suite:

| Layer | Folder | What it proves | Speed |
| --- | --- | --- | --- |
| API | `tests/api` | Each endpoint's rules, permissions, validation and error messages | seconds |
| Screens | `tests/ui` | What each person sees on each page, tables, filters, tours, phone layout | seconds |
| Journeys | `tests/journeys` | A whole story across several people and screens | minutes |

The journeys are deliberately few and long. The detail lives in the API and screen tests.

## The people

```mermaid
flowchart LR
    P[Platform administrator<br/>runs PropertyOS itself]
    A[Company administrator<br/>sets the company up]
    M[Property manager<br/>day-to-day operations]
    G[Leasing agent / broker<br/>enquiries, tours, applications]
    C[Accountant<br/>invoices, collections, statements]
    O[Owner<br/>owns the properties]
    R[Resident<br/>lives in a home]
    V[Vendor<br/>does repairs]
    X[Visitor / applicant<br/>no account]
    P -->|onboards, suspends, bills| A
    A -->|creates accounts| M & G & C & O & R & V
    X -->|enquires, applies| G
```

A platform administrator never opens a company's leases, payments, files or work orders. The operator pages show only company-level facts (see "The platform operator" below).

---

## 1. A company's whole life (JRNY-08, `lifecycle.spec.ts`)

The longest test. A brand-new company goes from sign-up to a resident moving out, with every person signing in with their own account.

```mermaid
sequenceDiagram
    autonumber
    actor P as Platform admin
    actor A as Company admin
    actor M as Manager
    actor T as Agent / Accountant
    actor O as Owner
    actor X as Visitor
    actor R as Resident
    actor V as Vendor

    P->>P: Sign in, onboard company, choose plan, set features
    P-->>A: Temporary password
    A->>A: First sign-in, choose a new password
    A->>A: Change a company setting (history records it)
    A->>O: Add owner account (with sign-in)
    A->>A: Add property, two units, a vendor, publish a listing
    A->>M: Add manager (property picker)
    A->>T: Add broker, agent, accountant (limited to the property)
    X->>T: Enquire, request a tour
    T->>T: Confirm tour, mark attended
    X->>A: Rae applies on screen, Dan applies by form
    M->>M: Decline Dan (reason), send Rae to owner
    O->>O: Approve application
    M->>M: Create lease (start date, term, rent, deposit), send
    A->>R: Create resident account
    R->>R: First sign-in, sign the lease
    M->>M: Countersign and activate
    R->>R: Add bank account, pay move-in charges
    loop Months 1 to 3
        Note over R,T: Billing cycle runs for that month
        R->>R: Month 1 pays, month 2 by autopay
        T->>R: Month 3 missed: late fee, reminder from Collections
    end
    R->>M: Report a leak
    V->>V: Schedule, start, complete with cost
    M->>M: Confirm fixed, close, rate the vendor
    R->>M: Messages, resident signs an e-signature request
    T->>O: W-9 recorded, owner statement published, owner reads it
    M->>R: Renewal offer, resident accepts
    R->>M: Gives notice, moves out
    M->>M: Deposit settled with a deduction
    A->>P: Audit log names who did what, platform sees company usage
```

## 2. When things go wrong (JRNY-09 to JRNY-15, `hardship.spec.ts`)

Each starts from a home somebody already lives in.

```mermaid
flowchart TD
    S([Occupied home: resident has an account and open move-in charges])
    S --> J9[JRNY-09 Declined card]
    S --> J10[JRNY-10 Part payment]
    S --> J11[JRNY-11 Bounced autopay]
    S --> J12[JRNY-12 Repair refused / reopened]
    S --> J13[JRNY-13 Declined signature]
    S --> J14[JRNY-14 Move-out deposit]
    S --> J15[JRNY-15 Bad upload link]

    J9 --> a1[Add card ending 0002, press Pay]
    a1 --> a2[Plain message: payment was declined]
    a2 --> a3[Nothing charged, attempt kept in history as Failed]
    a3 --> a4[Add a good card, pay, balance is $0]

    J10 --> b1[Try to pay more than owed]
    b1 --> b2[Refused: 'more than the $ due']
    b2 --> b3[Pay half, one charge becomes Part paid]
    b3 --> b4[Pay the rest, all Paid]

    J11 --> c1[Pay move-in charges, add a past-due charge]
    c1 --> c2[Switch on autopay with a declining account]
    c2 --> c3[Billing cycle: autopay collects nothing]
    c3 --> c4[Resident sees Failed; accountant finds them in Collections]

    J12 --> d1[Owner limit set low, costly repair needs approval]
    d1 --> d2[Owner declines with a reason on screen]
    d2 --> d3[Resident sees Declined and why; job cannot start]
    d3 --> d4[Second job: vendor completes, manager reopens with a reason]

    J13 --> e1[Resident declines to sign, reason needed]
    e1 --> e2[Request closed, cannot be signed afterwards]
    e2 --> e3[New request sent and signed]

    J14 --> f1[Settle before move-out: refused]
    f1 --> f2[Notice, end lease]
    f2 --> f3[Deduct more than held, blank reason, zero: all refused]
    f3 --> f4[Settle with a deduction: refund is what is left; twice is refused]

    J15 --> g1[Wrong, edited or expired token]
    g1 --> g2[Clear message, never a blank page]
```

## 3. The smaller journeys (`journeys.spec.ts`)

```mermaid
flowchart LR
    subgraph J1[JRNY-01 New company]
      p1[Platform onboards] --> p2[Admin first sign-in] --> p3[Owner, property, unit, listing] --> p4[Platform suspends] --> p5[Admin is signed out and refused]
    end
    subgraph J2[JRNY-02 Leasing]
      l1[Visitor finds a home] --> l2[Asks a question] --> l3[Applies] --> l4[Staff approve, owner approves] --> l5[Resident signs the lease]
    end
    subgraph J3[JRNY-03 Rent]
      r1[Resident adds a bank account] --> r2[Pays] --> r3[Books update] --> r4[Owner statement follows]
    end
    subgraph J4[JRNY-04 Repair]
      m1[Resident asks] --> m2[Vendor schedules, works, completes] --> m3[Manager confirms and closes]
    end
```

JRNY-05 (owner sees only their own portfolio and statements), JRNY-06 (resident: lease, balance, a request and a message) and JRNY-07 (vendor sees only their own jobs) check that each person sees exactly their own slice.

## 4. Rent, late fees and the billing cycle

```mermaid
stateDiagram-v2
    [*] --> Open: Rent invoice created a few days before it is due
    Open --> PartPaid: Part payment
    PartPaid --> Paid: Rest paid
    Open --> Paid: Paid in full or autopay on due date
    Open --> Late: Past due + grace days
    PartPaid --> Late
    Late --> Paid: Resident settles up
    Late --> LateFee: Cycle adds one late fee (state caps apply)
    Open --> Void: Entered in error (reason needed, only if nothing paid)
    Paid --> [*]
    Void --> [*]
```

The cycle is a daily timer (2 a.m. Central). Tests move the clock with `POST /api/billing/run-cycle {"asOf": "YYYY-MM-DD"}` (demo environments only), and the integration test `theScheduleBillsRentWithoutAnyoneAskingTo` proves the timer itself works.

## 5. Application to lease

```mermaid
stateDiagram-v2
    [*] --> Submitted: Applicant applies
    Submitted --> Screened: Automatic screening
    Submitted --> Rejected: Reject (reason)
    Screened --> OwnerReview: Send to owner
    Screened --> Approved: Approve
    OwnerReview --> Approved: Owner approves
    OwnerReview --> Rejected
    Approved --> LeaseDrafted: Create lease
    LeaseDrafted --> Sent: Send
    Sent --> TenantSigned: Resident signs
    TenantSigned --> Active: Countersign
    Active --> NoticeGiven: Resident gives notice
    NoticeGiven --> Ended: End
    Ended --> DepositSettled: Deposit settled
```

## 6. A repair

```mermaid
stateDiagram-v2
    [*] --> Submitted
    Submitted --> Triaged: Automatic triage (AI or rules)
    Triaged --> AwaitingApproval: Cost above the owner's limit
    AwaitingApproval --> Triaged: Owner approves
    AwaitingApproval --> Declined: Owner declines (reason, resident can read it)
    Triaged --> Assigned: Vendor chosen
    Assigned --> Scheduled: Visit booked
    Scheduled --> InProgress
    Assigned --> InProgress
    InProgress --> Completed: Vendor enters cost and notes
    Completed --> Closed: Manager confirms and rates
    Completed --> Assigned: Reopen (reason)
```

## 7. Website forms and who is told (NOTIFY-01 to 03, `notifications.api.spec.ts`)

```mermaid
flowchart LR
    V([Visitor on the public site])
    V -->|Contact / demo request| C[(Enquiry inbox)]
    C --> N1[/Notification to every platform admin, high priority/]
    V -->|Enquiry about a home| L[(Lead)]
    L --> N2[/Notification to leasing agents/]
    V -->|Rental application| A[(Application)]
    A --> N3[/Notification to property managers/]
    V -->|Tour request| S[(Showing)]
    S --> N4[/Notification to property managers/]
    V -->|Applicant uploads a document| D[(Document)]
    D --> N5[/Notification to the property's staff/]
    B([A bot fills the hidden field]) -.->|accepted, nothing stored, nobody told| X[ ]
```

## 8. Two companies side by side (ISO-UI-01 to 03, `isolation.ui.spec.ts`)

```mermaid
flowchart TB
    subgraph A[Company A]
      aa[Admin A] --- ra[Resident A]
    end
    subgraph B[Company B]
      ab[Admin B] --- rb[Resident B]
    end
    aa -. lists, search, pickers .-x B
    aa -. pasted links to B's records .-x B
    ra -. B's invoice or lease by link .-x B
    note1[The browser is never even sent B's data] --- aa
    sus[Platform suspends A] --> lock[A is signed out; B carries on]
```

## 9. A resident on a phone (MOB-01 to 03, `resident-phone.spec.ts`)

```mermaid
flowchart LR
    s1[Sign in on a 390px screen] --> s2[Open the menu behind the button] --> s3[Payments: add card, pay]
    s3 --> s4[Maintenance: new request] --> s5[Attach a photo] --> s6[My lease] --> s7[Messages]
    s7 --> chk{{Every page: no sideways scroll, nothing cut off}}
```

MOB-02 sweeps every resident page, MOB-03 sweeps every main staff page.

## 10. The AI features (AIM-01 to 08)

Run against a stand-in model (`E2E_FAKE_AI=1`) so the successful paths can be tested without a key.

```mermaid
sequenceDiagram
    actor U as Manager
    participant AI as AI service
    participant M as Model (stand-in)
    participant T as Tools (run as the caller)
    U->>AI: "Which units are vacant?"
    AI->>M: question + tool list
    M-->>AI: call list_vacant_units
    AI->>T: run with the caller's permissions
    T-->>AI: rows the caller may see
    AI->>M: tool result
    M-->>AI: written answer
    AI-->>U: answer + "Looked up: list_vacant_units"
    Note over AI: A model error still gives an answer from the person's own data.<br/>Prompt-injection attempts are blocked before the model is called.
```

## 11. The platform operator (CONSOLE-01 to 08, PLAT-*)

```mermaid
flowchart LR
    P([Platform admin]) --> H[Platform home<br/>enquiries, new companies, suspended, services up]
    P --> U[Company usage<br/>active / slowing / quiet / not started]
    P --> S[Service health<br/>7 services + database, response times]
    P --> D[Delivery log<br/>who was messaged and the outcome, recipients masked, text never shown]
    D -->|Details, with a reason| R[Whole recipient and subject, recorded in the company's audit log]
    P --> FP[Subscription payments<br/>billed, collected, overdue, record payments]
    P --> E[Enquiries<br/>demo requests inbox]
    P --> F[Features / Plans / Product defaults]
    P --> C[Companies<br/>onboard, edit, suspend, delete]
    ad([Company admin]) -.->|none of these open| H
```

## 12. Guidance and everyday behaviour (GUIDE-*, PAGE-*, TOAST-01, ROW-01)

| Id | Story |
| --- | --- |
| GUIDE-01, 02 | First sign-in offers a welcome tour; finishing or declining is remembered; Esc leaves a tour |
| GUIDE-03, 04 | First visit to a page offers a short tour; the "?" opens help for that page |
| GUIDE-05 | Ctrl+K searches pages and records from the server |
| GUIDE-06, 07 | Getting-started list ticks itself off; a resident is shown where to start |
| PAGE-01 to 03 | 20 per page with total and size choice; pickers are searches; messages find people by typing |
| TOAST-01 | Messages appear at the top centre: green worked, yellow needs attention, red broke |
| ROW-01 | Row buttons say what they do (View, Edit, Delete) |

## 13. A company pays PropertyOS (FEES-01 to 04, FEES-UI-01)

```mermaid
sequenceDiagram
    autonumber
    actor P as Platform admin
    participant B as Billing run (nightly, or "Run billing now")
    actor A as Company admin
    P->>B: Run billing (or the timer does at 2:30 a.m.)
    B->>B: For each active company: units managed x plan price, never under the plan minimum
    B-->>A: Notification: "Your PropertyOS invoice is ready"
    A->>A: Subscription page: plan, units, balance, invoices
    A->>A: Pay by card or bank
    alt card ends 0002 (declined)
        A-->>P: Failed payment recorded, operator notified
    else paid
        A-->>P: Invoice Paid, operator's collected figure goes up
    end
    P->>P: Records a wire, cheque or credit, raises a one-off charge, voids a mistake (reason needed)
    Note over P: Overdue invoices raise a high-priority notification for the operator
```

```mermaid
stateDiagram-v2
    [*] --> Open: Invoice made, due in 14 days
    Open --> PartPaid: Part payment
    PartPaid --> Paid: Rest paid
    Open --> Paid
    Open --> Void: Raised in error (no payments yet)
    Open --> Overdue: Past due date
    Overdue --> Paid
```

A company sees only its own invoices; another company's invoice is "not found". A platform administrator sees amounts, dates and statuses, never a company's business data.

## 14. Ratings (RATE-01 to 04, RATE-UI-01, 02)

```mermaid
flowchart LR
    R([Resident]) -->|rates| PU[Their property and their unit]
    R -->|rates once the work is finished| V
    O([Owner]) -->|rates a vendor who worked on their property| V[(Vendor)]
    S([Company staff]) -->|rates any vendor; closing a repair files a rating too| V
    PU --> M[Manager and owner read the stars and the words on the property and unit lists]
    V --> VL[Vendor list, scorecard and ranking use the combined rating]
    R -. reads only their own ratings .-> PU
```

Staff do not rate the property they run, vendors do not rate themselves, and what staff wrote about a job is not shown to the resident whose job it was.

## 15. Reports (REP-01 to 06, REP-UI-01 to 03)

```mermaid
flowchart TB
    H[Report centre: hub by role] --> P[Portfolio]
    H --> L[Leasing]
    H --> M[Money]
    H --> R[Repairs]
    P --> RR[Rent roll] & OC[Occupancy and vacancy]
    OC -->|Every unit| RR
    OC -->|Empty units| VA[Vacant units]
    L --> EX[Lease expirations] & FU[Leasing funnel] & RT[Resident ratings]
    M --> RE[Who owes what] --> TX[Transactions: one resident's account]
    M --> IN[Income and expenses] -->|a month| TX2[Transactions: that property and month]
    M --> PA[Payments] & FE[Fees and charges]
    R --> CO[Repair costs by property, category, vendor or priority] -->|a line| JO[Repair jobs]
    R --> VS[Vendor scorecard] -->|Jobs| JO
```

Every report has the same shape (headline figures, then a table that can be sorted, searched, filtered by column and downloaded), and what each person gets follows their role: an owner sees only their own properties, a resident and a vendor have no reports.

## 16. Company colours, logo and invoices (BRAND-01, INV-01, INV-02, LIST-01, LIST-02)

```mermaid
flowchart LR
    A([Company admin]) -->|Configuration > Branding: primary colour, accent colour, logo| S[(Settings)]
    S -->|announced to every service within seconds| UI[Menu, buttons, links take the company's colour; logo in the menu]
    S --> PDF[Invoice PDF: colour band, logo, status stamp, amount due, itemised table, tax line, payments, how to pay]
    T[Tax settings per charge type and state] --> PDF
    PR[Property form: amenities, one per line] --> LP[Public listing page: facts with icons, amenities with icons, costs, resident rating]
```

## 17. Amenities and the public homes search (AMEN-01 to AMEN-04)

A company adds an amenity with an icon, picks it on a property, and a visitor finds the home by it. The platform administrator keeps the shared list. Visitors browse by property or by home, filter, sort and page, and see who is renting the home out.

```mermaid
sequenceDiagram
    participant A as Company admin
    participant P as Platform administrator
    participant V as Visitor
    participant S as PropertyOS
    P->>S: Amenities page: add a shared amenity with an icon, switch it off, remove it (AMEN-02)
    A->>S: Leasing, Amenities: add Dog wash with a paw icon (AMEN-01)
    S-->>A: a repeated name is refused with a reason
    A->>S: Property form: search the list, pick Dog wash and Covered parking
    A->>S: Publish a listing
    V->>S: Homes page: filter by that amenity (AMEN-01)
    S-->>V: one property card with its homes, a photo carousel and Listed by the company
    V->>S: Switch to All homes, sort by rent, add a size filter, remove it, reload (AMEN-03)
    S-->>V: the address keeps every choice, the count and the cards follow
    V->>S: Open a home (AMEN-03)
    S-->>V: company logo, phone, email and website, the other home in the building, amenities with icons
    V->>S: Long result list, Next and Previous, a page past the end (AMEN-04)
    S-->>V: 12 per page, the last page when the number is too high
```

Spec: `tests/ui/amenities.ui.spec.ts`. The API rules behind it (names, icons, permissions, filters, paging, no internal ids) are in `PublicCatalogIT`.

## Where each test id lives

| Prefix | File |
| --- | --- |
| JRNY-01 to 07 | `tests/journeys/journeys.spec.ts` |
| JRNY-08 | `tests/journeys/lifecycle.spec.ts` |
| JRNY-09 to 15 | `tests/journeys/hardship.spec.ts` |
| MOB-01 to 03 | `tests/journeys/resident-phone.spec.ts` |
| ISO-UI-01 to 03 | `tests/ui/isolation.ui.spec.ts` |
| NOTIFY-01 to 03 | `tests/api/notifications.api.spec.ts` |
| AIM-01 to 06 / 07, 08 | `tests/api/ai.model.api.spec.ts` / `tests/ui/ai.model.ui.spec.ts` |
| CONSOLE-01 to 03, 09 / 04 to 08, 10 | `tests/api/platform-console.api.spec.ts` / `tests/ui/platform-console.ui.spec.ts` |
| FEES-01 to 04 | `tests/api/platform-billing.api.spec.ts` |
| RATE-01 to 04 | `tests/api/ratings.api.spec.ts` |
| REP-01 to 06 | `tests/api/reports-center.api.spec.ts` |
| BRAND, INV, RATE-UI, LIST, REP-UI, FEES-UI | `tests/ui/features.ui.spec.ts` |
| GUIDE, PAGE, TOAST, ROW | `tests/ui/guide.ui.spec.ts` |
| RBAC-08 | `tests/ui/navigation.ui.spec.ts` |
| AMEN-01 to 04 | `tests/ui/amenities.ui.spec.ts` |
