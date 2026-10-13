# Endustry ERP

A working first cut of the ERP described in `Endustry_ERP_BRD.docx`: Spring Boot API, React web app,
and a sample engineering factory with six months of transactions so every screen has real data.

## Run it

Needs Java 21+ and Node 20+. Nothing else to install; Maven is fetched by the wrapper on first run.

| Step | Command | Opens at |
| --- | --- | --- |
| 1. API | `run-backend.cmd` | http://localhost:8080 |
| 2. Web app | `run-frontend.cmd` | http://localhost:5173 |

Sign in with `owner@endustry.local`. There is one user per role (`accounts@`, `sales@`, `purchase@`,
`store@`, `production@`, `supervisor@`, `quality@`, `ca@`, all `@endustry.local`). They share the
password set by `erp.seed.password` in `backend/src/main/resources/application.yml`.

The sample data is created once, in `backend/data/`. That folder is the database: everything anyone
enters lives there, so back it up before deleting it. Start with `ERP_DEMO_DATA=false` for a demo
client with no transactions.

Tests: `mvnw.cmd test` in `backend/` (112 tests) and `npm test` in `frontend/` (19 tests).

The public landing page is at http://localhost:5173/; sign-in is at `/login`. Signed-in users go straight to their dashboard. The demo and contact buttons email `CONTACT_EMAIL` in `frontend/src/pages/Landing.tsx`.

## Database changes (Flyway)

The schema is owned by Flyway migrations in `backend/src/main/resources/db/migration/h2/` and `.../postgresql/`
(plus Java ones in `com.endustry.erp.platform.migration`). Hibernate only validates the tables against the code.
To change the schema, add the next file, e.g. `V5__add_item_brand.sql`, in both folders; never edit an applied one.
Before applying anything to a database that holds data, the server copies it to `backend/data/backup-before-migration-*.zip`.

## Labels

Each client can rename business terms (Customers to Clients, Items to Products, Machines to Equipment...) under
Settings > Labels. The starting words come from the client's industry template (`platform/labels/Terms.java`).

## Languages (English / Hindi)

Every screen text is written in English in the code and shown through `t('...')`; the English wording is the key.
Translations live in the database (`ui_language`, `ui_text`) and the app loads them as one JSON object per language
from `GET /api/public/i18n/hi`, with a version number. The browser keeps its copy until the version changes.
The language picker is in the top bar, on the sign-in page and on the landing page.

- Edit a translation: sign in as product admin, Translations. Saving raises the version, so open browsers pick it up.
- New screen text: wrap it in `t('...')`; add its Hindi to `backend/src/main/resources/i18n/hi.json`. On the next
  start, the seed migration adds texts the database does not have yet and never overwrites edits made on screen.
- Another language: add a row to `ui_language` in a new `V` migration and a `<code>.json` next to `hi.json`
  (and its code to `R__Seed_translations.LANGUAGES`).
- Client term renames (Labels) still apply on top. Names, numbers and data people enter are not translated.

## What is built

| Area | What works | BRD |
| --- | --- | --- |
| Masters | Items, customers, vendors, warehouses, machines, employees, dated GST rates, user-defined lists; GSTIN check-digit, state and PAN checks; duplicate GSTIN detection | ORG-02..07, 10 |
| Sales | Quotation, sales order, tax invoice, receipts; CGST/SGST/IGST by place of supply; credit-limit block with Owner override; e-way bill flag | SAL-02, 04, 07, 10, 14 |
| Purchase | Purchase order with approval limit, vendor invoice with three-way quantity match and rate-variance flag, payments, MSME due dates (15 / 45 days) | PUR-03, 05..07, 11 |
| Inventory | Stock by item, warehouse, batch and status; goods receipt with tolerance; FIFO issue; weighted average cost; adjust and transfer; full ledger | INV-01, 02, 04, 07, 08 |
| Production | Versioned BOM with routing and cost roll-up, circular-BOM guard, work orders, material check, job cards (start, pause, complete), output with backflush, standard vs actual cost, material plan | BOM-01..03, 07, 08, 10; PPC-01, 04, 06; SHP-01..07; CST-01..03 |
| Quality | Incoming inspection holding stock in QC, accept / reject with reason | QLT-02, 04, 08 |
| Reports | Owner dashboard and 9 reports (registers, GST summary, receivables ageing, MSME payables, stock, cost variance, rejections), all exportable | RPT-01, 03, 07 |
| Platform | Role x module access matrix editable at runtime, append-only audit trail, configurable numbering, optimistic locking | ADM-01, 03; ORG-09; WFL-05 (numbering) |

## Product admin and clients

One deployment serves many clients. Each client's data is kept apart by a client column on every
table, which Hibernate fills in and filters on automatically, so no query can forget it.

Sign in as `admin@endustry.local` (same seed password) to reach the product admin area:

- **Clients**: create a client by choosing its name, industry template, modules, user limit, hosting
  and whether it gets white-label branding. Its workspace (company, numbering, lists for that
  industry, role matrix, first warehouse, Owner user) is ready at once and the Owner's sign-in
  details are shown to you once.
- **Per-client switches**: modules on or off, white-label on or off, user limit, suspend and
  reactivate. All take effect immediately, without the client signing in again.
- **Support session**: "Sign in as" any active client user for 30 minutes. Every change is recorded
  as `user (support: admin)`. A support session cannot change users or access rights.
- **Add user**: create a sign-in for anyone at a client, in any client role. The client's Owner can
  also add their own users under Settings > Users.
- **Reset password** for any client user; **Product theme** for the default look; **Admin activity**
  for a log of everything product admins did.

A white-label client signs in at `/?client=<short-name>` to see its own branding on the sign-in page.
"Hosting" on a client is a record for your team; setting up a dedicated server is done outside the app.

The sample company is the client `demo`. `backend/data` holds real entries once people start using
the app: stop the server and copy that folder before any upgrade.

## What is configurable without code

- **Theme**: colours, dark mode, sidebar style, font, density, corner radius, application name
  (Settings > Theme, with live preview).
- **Company**: legal and tax identity, financial year start.
- **Rules and limits**: credit-check mode, PO approval limit, receipt tolerance, rate-variance
  tolerance, negative stock, e-way bill threshold, MSME payment days.
- **Numbering**: prefix, digits, next number per document, checked against GST's 16-character limit.
- **Roles and access**: View / Full per role and module.
- **Lists**: units, item categories, rejection, downtime and adjustment reasons, states.

## Not built yet

These are in the BRD's Phase 1 but not in this cut:

- Double-entry accounting, ledgers, bank reconciliation and financial statements (FIN-*). Receivables
  and payables are tracked on invoices only.
- E-invoice (IRN), e-way bill generation, GSTR-1 / 3B / 2B, IMS, TDS (GST-02..10). Needs a GSP contract.
- Job work outward and inward, ITC-04 (JWO-*, JWI-*).
- Credit notes, debit notes, returns, delivery challans, export invoices.
- Mobile app, offline mode, regional languages, WhatsApp, barcode scanning.
- Tally and Excel import, multi-company, multi-branch.
- Item variants, serial numbers, stock counts, price lists.

Also note: the local database is file-based H2 (a PostgreSQL profile is included but untested), the
JWT secret in `application.yml` is a development default, and the GST rates and statutory values in
the sample data are illustrative and must be checked against current notifications.

## How it is put together

```
backend/src/main/java/com/endustry/erp
  platform/   common (CrudService, CrudController, errors), security, settings, audit, validation
  masters/    entities + a few lines each on top of CrudService
  documents/  one engine for quotation, order, receipt and invoice (TradeDocument, DocumentPolicy)
  sales/ purchase/ inventory/ quality/ production/ reporting/   the rules of each module
  seed/       reference data and the sample factory

frontend/src
  core/        api, auth, theme, formats, validation
  components/  ui kit, FormFields, ResourcePage (any master), DocumentPage (any document)
  config/      resources.ts, documents.ts, navigation.ts  <- screens are declared here
  pages/       dashboard, stock, production, quality, reports, settings
```

Three ideas carry most of the reuse:

1. **A master is a config.** `CrudService` + `CrudController` on the server and `ResourcePage` on the
   client give list, search, filter, paging, validation, optimistic locking and audit. Adding a master
   is an entity, a three-line service and controller, and an entry in `config/resources.ts`.
2. **Every commercial document is one shape.** Quotation, sales order, invoice, purchase order, goods
   receipt and vendor invoice share one table, one service, one controller and one screen. Each kind
   contributes only a `DocumentPolicy` (its rules) and an entry in `config/documents.ts`.
3. **Access is data.** Every endpoint lives under `/api/{module}/...`; one interceptor checks the
   role x module matrix, so a new endpoint is protected by where it sits.

Every error has the same shape (`status`, `code`, `message`, `fieldErrors`, `details`), and the client
shows field errors next to the field and business-rule errors as a message.
