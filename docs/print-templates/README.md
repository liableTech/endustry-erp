# Print templates: placeholder guide

Every saved document in Endustry ERP has **Print** and **Download PDF** buttons at the bottom of its page:
quotations, sales orders, invoices, purchase orders, goods receipts, vendor invoices and work orders (with their job cards).

They print with the **standard layout** unless your company uploads its own. To use your own layout:

1. Go to **Settings > Print templates**.
2. Download the **sample template** (a classic bordered GST invoice), or the **standard layout** of the document you want to change.
3. Edit the HTML file in any text editor. Keep the `{{placeholders}}` you need.
4. Click **Upload template**, choose the file, pick the document it is for, and press **Preview** to see it filled with your latest real document.
5. Tick **Use this layout whenever this document is printed**, or leave it unticked to pick it from the layout list beside the Print button.

You can go back to the standard layout at any time from the **Prints with** column.

## How placeholders work

Templates use [Mustache](https://mustache.github.io/mustache.5.html) placeholders.

| You write | You get |
| --- | --- |
| `{{document.number}}` | The value, e.g. `INV/26-27/0042`. Text is made safe for HTML automatically. |
| `{{#lines}} ... {{/lines}}` | The part in between, repeated for each line item. Inside it, use the line's fields directly: `{{itemName}}`, `{{qty}}`. |
| `{{#party.gstin}} ... {{/party.gstin}}` | The part in between only when the field has a value (or is `true`). |
| `{{^party.gstin}}Unregistered{{/party.gstin}}` | The part in between only when the field is empty (or `false`). |

* Amounts arrive already formatted with Indian grouping and two decimals (`12,34,567.00`), without the rupee sign. Add `&#8377;` where you want ₹.
* Dates arrive as `DD-MM-YYYY`. An empty date is an empty string.
* Page size and margins: `@page { size: A4; margin: 12mm; }`. Landscape: `size: A4 landscape;`.
  Page numbers: `@page { @bottom-right { content: "Page " counter(page) " of " counter(pages); } }`.
* Font: **DejaVu Sans** is built in and prints the ₹ sign. Common names (Arial, Helvetica, sans-serif) are mapped to it.
* Layout: use tables for columns. The PDF engine supports CSS 2.1 plus some CSS 3 (borders, backgrounds, `@page`); it does not support flexbox or grid.
* Logo: embed it as a data URI, `<img src="data:image/png;base64,...">`. Links to images on the internet are not loaded.
* Scripts do not run.
* Maximum file size is 3 MB including embedded images.

A template that has a placeholder mistake (for example a `{{#lines}}` with no `{{/lines}}`) is refused when you save it, with the reason.

## Data for quotations, orders, goods receipts and invoices

Document types: `QUOTATION`, `SALES_ORDER`, `SALES_INVOICE`, `PURCHASE_ORDER`, `GRN`, `PURCHASE_INVOICE`.
A full example is in `sample-document-data.json` (also **Sample data** in the app, which uses your latest real document).

| Field | Example | Notes |
| --- | --- | --- |
| `documentType` | `SALES_INVOICE` | |
| `documentTitle` | `Tax Invoice` | Quotation, Sales Order, Tax Invoice, Purchase Order, Goods Receipt Note, Purchase Invoice |
| `printedAt` | `10-10-2026 11:30` | |
| **company** | | Your company, from Settings > Company |
| `company.legalName`, `tradeName`, `displayName` | `Shree Precision` | `displayName` is the trade name, or the legal name when there is none |
| `company.gstin`, `pan`, `udyamNumber` | | |
| `company.addressLine` | `Plot 14, MIDC Bhosari, Pune - 411026` | Address, city and PIN joined, leaving out blanks |
| `company.address`, `city`, `pincode`, `stateCode`, `phone`, `email` | | |
| **document** | | |
| `document.number` | `INV/26-27/0042` | |
| `document.date` | `08-10-2026` | |
| `document.dueDate`, `document.dueLabel` | `07-11-2026`, `Due date` | Label differs per type: Valid until, Delivery date, Expected by, Pay by |
| `document.referenceNo`, `document.referenceLabel` | `PO-7781`, `Customer PO no` | |
| `document.status`, `statusLabel` | `POSTED`, `Posted` | |
| `document.isDraft`, `isCancelled` | `true` / `false` | |
| `document.placeOfSupply` | `27` | GST state code |
| `document.sourceNumber` | `SO/26-27/0031` | The document this one was raised from |
| `document.warehouse`, `notes` | | |
| `document.ewayBillRequired` | `true` / `false` | |
| **party** | | Customer or vendor |
| `party.label`, `billToLabel` | `Customer`, `Bill to` | `Vendor`, `Vendor` on purchase documents |
| `party.code`, `name`, `gstin`, `pan` | | |
| `party.addressLine` | `Udyog Bhavan, Hadapsar, Pune - 411013` | As for the company |
| `party.address`, `city`, `pincode`, `stateCode`, `contactPerson`, `phone`, `email` | | |
| **lines** (list) | | One entry per line item |
| `lineNo` | `1` | |
| `itemCode`, `itemName`, `description`, `hsnCode`, `batchNo` | | |
| `qty`, `uom` | `10`, `NOS` | |
| `rate`, `discountPct`, `hasDiscount`, `taxRatePct` | `1,000.00`, `10`, `true`, `18` | |
| `taxableAmount` | `9,000.00` | After discount, before tax |
| `cgstAmount`, `sgstAmount`, `igstAmount`, `taxAmount` | | |
| `lineTotal` | `10,620.00` | Including tax |
| **taxSummary** (list) | | One entry per HSN code and GST rate |
| `hsnCode`, `taxRatePct`, `taxableAmount`, `cgstAmount`, `sgstAmount`, `igstAmount`, `taxAmount` | | |
| **totals** | | |
| `totals.subTotal`, `discountTotal`, `hasDiscount`, `taxableTotal` | | |
| `totals.interState` | `false` | `true` when IGST applies instead of CGST + SGST |
| `totals.cgst`, `sgst`, `igst`, `taxTotal`, `grandTotal` | | |
| `totals.paidAmount`, `balance`, `hasPayments` | | Payments recorded against an invoice |
| `totals.amountInWords` | `Rupees Seventeen Thousand Seven Hundred Only` | |

## Data for work orders and job cards

Document type `WORK_ORDER`. A full example is in `sample-work-order-data.json`.

| Field | Notes |
| --- | --- |
| `documentType`, `documentTitle`, `printedAt`, `company.*` | As above |
| `document.number`, `status`, `statusLabel`, `isDraft`, `isCancelled` | `isDraft` is true while the order is only planned |
| `document.startDate`, `dueDate`, `completedDate`, `salesOrderNo`, `warehouse`, `bomVersion`, `notes` | |
| `item.code`, `item.name`, `item.uom` | The product being made |
| `quantities.planned`, `produced`, `rejected`, `remaining` | |
| `costs.standardUnitCost`, `materialCost`, `operationCost`, `actualUnitCost` | |
| **materials** (list): `itemCode`, `itemName`, `uom`, `required`, `available`, `shortage`, `isShort` | Material still needed for the remaining quantity |
| **jobCards** (list): `seqNo`, `operationName`, `workCentre`, `operator`, `status`, `statusLabel`, `minutesWorked`, `goodQty`, `rejectedQty`, `pauseReason` | One per routing step |

## Data for bills of material

Document type `BOM`. A full example is in `sample-bom-data.json`.

| Field | Notes |
| --- | --- |
| `documentType`, `documentTitle`, `printedAt`, `company.*` | As above |
| `document.number`, `version`, `status`, `statusLabel`, `isDraft`, `outputQty`, `notes` | The number is the product code and version, e.g. `FG-SHAFT-20-v2` |
| `item.code`, `item.name`, `item.uom` | The product the BOM makes |
| `costs.materialCost`, `operationCost`, `totalCost`, `unitCost` | Standard cost of one run and per unit |
| **components** (list): `lineNo`, `itemCode`, `itemName`, `uom`, `qty`, `scrapPct`, `hasScrap` | What goes into one run |
| **operations** (list): `seqNo`, `name`, `workCentre`, `setupMinutes`, `runMinutesPerUnit` | The routing, in order |

## For developers

* Print: `GET /api/{module}/docs/{kind}/{id}/print` (PDF; `?download=true` for an attachment; `?templateId=` to pick a template, `0` for the standard layout).
  Work orders: `GET /api/production/work-orders/{id}/print`. Bills of material: `GET /api/production/boms/{id}/print`.
* The JSON a template receives: the same paths ending in `/print-data`.
* Templates: `/api/settings/print-templates` (list, create, update, delete), `/types`, `/default/{docType}`,
  `/built-in/{docType}`, `/sample-data/{docType}`, `POST /preview`.
* Code: `backend/src/main/java/com/endustry/erp/printing`. Standard layouts: `backend/src/main/resources/print-templates`.
