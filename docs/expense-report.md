# Trip expense report

What a trip actually spent, against the cash advanced for it: one shared report per trip, edited by the same
people who edit the trip's [budget](budget.md). Web page `admin/reports/expenses.xhtml` (private repo), bean
`#{expenses}`, REST `api/ExpenseReportResource`; built the way every app-reachable feature is, so the apps can
use it later without a second implementation.

## Why it exists

Trip leaders settled their spending in the "Expense Report" tab of the `2026-09-HolyAngels` Google Sheet: a
header (name, event, dates, the cash advance, a rate per foreign currency), one row per expense (date,
category, description, an amount in EUR, BAM or USD, the dollar actual, a budget column, a receipt column,
notes), a total, and "DUE TO CFPW (to leader if negative)". That tab is the oracle: `ExpenseMathTest`,
`ExpenseCommandsTest` and `ExpenseReportPwIT` reproduce it to the cent. What the sheet could not do is see the
budget it was spending against, or keep the receipts.

## Owner decisions (2026-10-26)

- One report per trip, shared; editing stays open in every state.
- One currency per expense: a payment made in two currencies is two expenses. (The sheet's one mixed row, bus
  driver tips of 40 EUR + 200 KM + $20, is three lines in the oracle.)
- The expense categories are their own list: Transportation, Guide, Supplies, Tips, Scholarships, Leader
  expenses, Lodging, Postage, Meals, Other.
- The budget table lists EVERY budget line (shared, per person, lodging) and an expense may match any of them.
- A Submit stamp (who, when) and a Draft / Submitted / Paid status; Paid once a paid-on date is set.
- Receipts belong to an expense or to the report itself (a cash-advance slip, a statement).
- Receipts are stored in the media bucket but served ONLY through the app, behind the report's view gate.
- A read-only print view now; a PDF later.

## The model

| Type | What it is |
|------|------------|
| `model/TripExpenseReport` | ONE row per trip: `submittedBy` (free text, pre-filled with whoever started it), `submittedById` + `submittedOn` (the submit stamp), `cashAdvanceCents`, `cashAdvanceDetails`, `paymentInstructions`, `paidOn`, `paidHow`, `currencies`, `lines`, `receiptIds` (report level), `orgId`, `createdBy`, `created`, `version`. `getStatus()` is derived, never stored |
| `model/CurrencyRate` | `code` (three letters, upper-cased), `perUsdMicros` (foreign units per ONE dollar, in millionths: 0.83 EUR is 830,000), `note`. USD never has a row |
| `model/ExpenseLine` | `id`, `date` (null reads "Various"), `category` (`ExpenseCategory`), `description`, `currency` (USD by default), `amountMinor` (hundredths of the line's own currency), `budgetLineId` (a `BudgetLine` or `BudgetLodgingLine` id, or null), `note`, `receiptIds` |
| `ExpenseCategory` | The menu above; `ofBudget(BudgetCategory)` maps by name |
| `ExpenseStatus` | DRAFT, SUBMITTED, PAID |

The rate runs the way the sheet wrote it ("USD / EUR 0.83"), with the inverse ("1 EUR = $1.2048") shown
beside it. Every dollar figure is DERIVED on read and never stored, so fine-tuning a rate later re-prices every
expense in that currency. Money is long hundredths in the model and `Double` in forms; the constructors
normalize like the budget's.

`trip_expense_reports` (PK `tripId`, no index) is `dynamo/ExpenseReportDAO`, the `BudgetDAO` shape exactly: a
`PointCache` (`CacheKeys.EXPENSE_PREFIX`, `DAO.CacheScope.EXPENSE`), a versioned conditional put (version 0 may
only create), "Saved by someone else: reload the page and try again." on a lost race, a 350 KB guard. Caps: 300
expenses, 20 currencies, 40 receipts per expense, 60 at report level.

## Formulas (`pay/ExpenseMath`, HALF_UP to the cent)

| Quantity | Formula | Sheet oracle |
|---|---|---|
| usd(line) | USD: the amount; else amount × 1,000,000 / perUsdMicros; a currency with no rate counts $0 and the page warns | 23 EUR → 27.71; 220 EUR → 265.06; 800 KM → 481.93; 200 KM → 120.48 |
| total | Σ usd | 55,560.95 |
| due | cash advance − total; positive is "Due to {organization}", negative "Due to {submitter}", shown unsigned | −9,795.95 on 45,765.00 |
| budgeted(line) | a typed line: `BudgetMath.tripCents` (a per-person line × people); a lodging line: its hotel COST | |
| spent(line) / remaining | Σ usd of the expenses matched to it / budgeted − spent (red when negative) | |
| Not in budget | Σ usd of unmatched expenses AND of expenses whose budget line was deleted, so the Spent column always adds up to the total | |
| per currency | lines, foreign total, USD total (line by line) | 283.00 EUR = $340.96 |

## Receipts

`action/ExpenseReceipts` (`#{expenseReceipts}`) stores, `ExpenseCommands` links.

- **Accepted:** what the bytes ARE, never the declared type: a JPEG, PNG, GIF or HEIC image, or a PDF
  (`%PDF-`, stored as is). At most 16 MB uploaded. Anything else: "use a JPG, PNG, GIF, HEIC or PDF."
- **Images are capped at 1280 px on the longest side** (`ExpenseReceipts.MAX_IMAGE_SIDE`, owner request
  2026-10-26) by `PhotoProcessor.processWithin`: the aspect ratio is kept, nothing is cropped or enlarged, and
  the EXIF orientation is applied first, so the cap measures the picture as seen. An image already within the
  cap is stored as uploaded (HEIC still becomes JPEG); a larger one is re-encoded, JPEG for JPEG and HEIC, PNG
  for PNG and GIF. An image over 800 px wide also gets a small rendition for the page's thumbnail.
- **Stored:** `receipts/{tripId}/{uuid}.{ext}` (and `-small.{ext}`) in the media bucket, or the static local
  store in local mode; one `MediaItem` row in slot `receipts-{tripId}`, `hidden`, stamped with the trip's org.
  The id is then linked with the report's own versioned put; a refused link deletes the files and the row.
- **Served:** ONLY by `web/ExpenseReceiptServlet`, `GET /expense-receipts/{id}[/thumb]`: not signed in →
  login and back; not a receipt row → 404; may not read the report → 403; else the bytes with `inline`,
  `private, no-store` and `nosniff`. The path never ends in `.pdf` (`PdfRedirectFilter` 301s those to the CDN
  in production only). Every URL in a row, a DTO or the page comes from `ExpenseReceipts.path`, never
  `MediaCommands.publicUrl`.
- **Fenced off from media:** `MediaCommands.getAll` (so every library and picker) skips receipt slots,
  `mayManage` is false for them, `isReservedKey` includes `receipts/`, and `MediaResource` answers 404 for a
  receipt slot or row, even to an admin. And the CDN agrees: `MediaStack`'s bucket policy DENIES CloudFront
  `s3:GetObject` under `receipts/*`, so a receipt's key typed into the media host is 403 (the task role's
  reads are untouched).
- **Uploaded** through the receipt dialog (`WEB-INF/receiptUploadDialog.xhtml`), a `p:fileUpload` in its own
  small multipart form mounted from the template's `uploadDialogs` insert, like the photo dialog; one file at
  a time (`sequential`), because each is a versioned save of the report. Not a `PhotoUploadBean`: that base
  crops, and rejects PDFs. An upload's response re-renders the dialog's list, then `refreshExpenseBody()`
  re-renders the page: the page's figures are computed before the upload's listener runs.
- **Deleted** from the dialog by id (`f:param`), or with their expense.
- **Kept** by a trip delete, with the report: they are the record of money that was spent
  ([trip-delete.md](trip-delete.md), "Financial history is never destroyed").

## Privileges

No new privilege, and no second spelling of one: `action/TripFinanceGate` is THE rule for the trip's money
pages, used by both `BudgetCommands` and `ExpenseCommands`. Edit: site admin, `tripMgr@trip`,
`tripFinAdmin@trip`. View: an editor, `tripFinView@trip`, or the global `viewFinances`. The Reports dashboard's
**Expense Report** card shows for the same people (`seeExpenses`).

## The page (`admin/reports/expenses.xhtml`, private repo)

Reached from the dashboard card (`toExpenses`, its number `expenseHeadline`: "$55,560.95 spent" or "No expenses
yet") and from the Budget page (`toExpenses`); back with `reportsDone`, across with `toBudget`, and a print view
(`?print=true`) that edits nothing and ends with the sheet's signature line. Sections: warnings; Report (status
chip, trip dates, the submit line, then the fields for an editor or their values for anyone else; Save details,
Submit / Submit again, Withdraw submission); Currencies; Budget (budget vs actual, with Not in budget and
totals); Expenses (Edit / Up / Down / Delete in `WEB-INF/expenseLineActions.xhtml`, and a Receipts button per
expense); Report receipts; the totals card (total spent, cash advance, the due line, the payment instructions).

The expense dialog asks for the description first (a date picker first would pop its calendar over the menus
when the dialog focuses it), then the date, the budget line (choosing one sets the category to match, still
editable), the category, the currency (choosing "Add a currency..." asks for its code and rate and adds it with
the expense), the amount and a note. Refusals show inside the dialog, which stays open (`keepOpen`).

State follows `docs/page-state-and-identity.md`: `requestScope.ep = expenses.reportPage(tripId)` every request
and after every write (`SessionScopePolicyIT` lists it as domain-valued); only the scalar forms (`headerForm`,
`expenseForm`, `currencyForm`) and the receipt dialog's `receiptLineId` live in viewScope.

## REST (`api/ExpenseReportResource`, `application/vnd.trip.expense-report.v1+json`)

| Method | Path | Body / answer |
|---|---|---|
| GET | `expenses/trips/{tripId}` | `{header, currencies, lines, receipts, summary}`; `receipts` carry their `lineId` (null = the report) and absolute app URLs |
| PUT | `expenses/trips/{tripId}/header` | `{submittedBy, cashAdvance, cashAdvanceDetails, paymentInstructions, paidOn, paidHow}` (`paidOn` an ISO date) |
| POST | `expenses/trips/{tripId}/submit` · `.../unsubmit` | stamps / withdraws; submit is 400 while there are no expenses |
| POST / PUT / DELETE | `expenses/trips/{tripId}/currencies[/{code}]` | `{code, rate, note}`; a currency in use cannot be deleted |
| POST / PUT / DELETE | `expenses/trips/{tripId}/lines[/{lineId}]` | `{date, category, description, currency, amount, budgetLineId, note}`; a new code with `rate` adds the currency in the same save |
| POST | `expenses/trips/{tripId}/receipts?lineId=&name=` | a RAW body (image or PDF), 413 over 16 MB → `{id, url, thumbUrl}` |
| DELETE | `expenses/trips/{tripId}/receipts/{receiptId}` | |

Writes need the CSRF header and the edit gate (403); a refusal is 400 `VALIDATION_FAILED` with the bean's
reason; an unknown trip is 404.

## Tests

Unit (`trip/`): `TripExpenseReportTest`, `ExpenseLineTest`, `CurrencyRateTest`, `ExpenseMathTest` (the oracle in
`pay/ExpenseOracle`), `ExpenseReportDAOTest`, `ExpenseCommandsTest` (gates, the oracle on the page rows, every
refusal, a currency from the dialog, budget vs actual for all three kinds of budget line, submit),
`ExpenseReceiptsTest` (both renditions, PDFs, refusals, rollbacks, the S3 branch), `ExpenseReceiptServletTest`,
`ExpenseReportResourceTest`, `MoneyFormsTest`, plus cases in `AuditCommandsTest`, `MediaTenancyTest`,
`MediaCommandsPresignTest`, `MediaResourceTest`, `ModelSerializationTest`, `SerializationCompatibilityTest`,
`ApiSurfaceTest`.

Browser (`medjugorje/webtest`): `ExpenseReportPwIT` (the oracle and the print view, a currency from the dialog
then fine-tuned, a matched expense moving its budget line, row commands by identity, receipts uploaded on an
expense and on the report and served only to the report's readers, submitting, the gate matrix), with the card
and page added to `ReportsDashboardPwIT`, `AdminReportsPwIT` and `AuthGatePwIT`.

## Out of scope (for now)

A PDF download (the Room Invoice pattern), emailing the report, several reports per trip, a reimbursement split
across people beyond the payment instructions text.
