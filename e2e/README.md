# End-to-end tests (Playwright)

These tests drive the real app in a browser: sign-in, every screen in the menu, the sales and purchase
flows from quotation or PO to payment, stock, production, quality, reports, settings, English/Hindi,
the product admin area and each role's access.

They never use your own data. Each run starts its own copy of the app:

| Part | Where | Data |
| --- | --- | --- |
| API | http://localhost:18080 | a new demo database each run, in your temp folder |
| Web app | http://localhost:15173 | the code in `frontend/` |

So you can leave `run-backend.cmd` and `run-frontend.cmd` running while the tests run. The API is
built into a separate folder in your temp folder, so `backend/target` and `backend/data` are not touched.

## First time

Needs Java 21+ and Node 20+ (the same as the app), and the web app's packages (`npm install` in `frontend`).

```
cd e2e
npm install
npx playwright install chromium
```

## Run

```
cd e2e
npm test                 # all tests, about 10 minutes (the first run also builds the API)
npm test -- tests/03-sales-flow.spec.ts    # one file
npm run test:headed      # watch the browser while it runs
npm run report           # open the last report: every test with its screenshots
```

Screenshots of each step are in the report (`playwright-report/`) and in `test-results/`. A failed
test also keeps a trace you can step through with `npx playwright show-trace <path to trace.zip>`.

## Files

| File | What it checks |
| --- | --- |
| `01-public` | landing page, sign-in checks, wrong password, Hindi on the sign-in page |
| `02-every-screen` | every menu screen opens with no server or browser errors; records open as pages; tabs, search, "From ..." pickers and "New" buttons |
| `03-sales-flow` | quotation, sales order, invoice, receipt and PDF; editor checks; cancelling |
| `04-purchase-flow` | purchase order, goods receipt, vendor invoice and payment |
| `05-masters` | add, find, edit and delete an item; customer GSTIN/PAN/PIN checks |
| `06-inventory-production-quality` | stock adjustment and ledger, transfer checks, work order with job cards and output, BOM copy, material plan, incoming inspection |
| `07-reports` | every report opens and exports to CSV |
| `08-settings` | labels, dark mode, company, print templates, adding a user, role access, audit trail |
| `09-language` | Hindi across the menu and screens, and English text left untranslated |
| `10-product-admin` | product admin screens, new client with the Trading preset, switching a module off, support session, adding a client user, editing a translation |
| `11-roles` | each seeded role sees only its own screens |
| `12-new-industries` | Agriculture and Chemicals on the landing page; new clients on those templates get their words and lists, in English and Hindi |
| `13-signup` | self sign-up: off by default, plans and demo payment, approval by the product admin, automatic approval, free membership, coupons, Hindi |
| `14-table-filters` | the Filters button on every list; several filters together, chips, clearing, remembered after reload; masters, stock, ledger, reports, product admin; Hindi |
| `15-bom-print` | a bill of material prints and downloads as PDF |

## Options

| Setting | Default | Use |
| --- | --- | --- |
| `E2E_API_PORT`, `E2E_WEB_PORT` | 18080, 15173 | if those ports are taken |
| `E2E_REUSE=1` | off | reuse a test API and web app you already started on those ports (faster while writing tests) |
| `E2E_WORK_DIR` | temp folder `endustry-e2e` | where the test API is built and its database kept |
| `ERP_SEED_PASSWORD` | `Endustry@2026` | if you changed the seed password |
