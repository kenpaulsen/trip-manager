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
- A Submit stamp (who, when) and a Draft / Submitted / Partly paid / Paid status, the last two from payments.
- Paid status per expense (asked 2026-10-26): Not paid, Paid by organization, Already reimbursed. The last two
  still count as spent (total, budget vs actual) but are left out of what is due.
- Payments (asked 2026-10-26): a finance admin records each payment against the expenses it settles, with the
  amount, how, the date and a note. The amount is free and signed (the advance can make it less, zero or
  negative). They REPLACE the report's old "Paid on" / "How it was paid" fields. Only site admins and
  `tripFinAdmin` holders record them: a trip manager is usually the one being paid.
- Receipts belong to an expense or to the report itself (a cash-advance slip, a statement).
- Receipts are stored in the media bucket but served ONLY through the app, behind the report's view gate.
- A read-only print view now; a PDF later.

## The model

| Type | What it is |
|------|------------|
| `model/TripExpenseReport` | ONE row per trip: `submittedBy` (free text, pre-filled with whoever started it), `submittedById` + `submittedOn` (the submit stamp), `cashAdvanceCents`, `cashAdvanceDetails`, `paymentInstructions`, `currencies`, `lines`, `payments`, `receiptIds` (report level), `orgId`, `createdBy`, `created`, `version`. `getStatus()` is derived, never stored |
| `model/CurrencyRate` | `code` (three letters, upper-cased), `perUsdMicros` (foreign units per ONE dollar, in millionths: 0.83 EUR is 830,000), `note`. USD never has a row |
| `model/ExpenseLine` | `id`, `date` (null reads "Various"), `category` (`ExpenseCategory`), `description`, `currency` (USD by default), `amountMinor` (hundredths of the line's own currency), `budgetLineId` (a `BudgetLine` or `BudgetLodgingLine` id, or null), `note`, `receiptIds`, `paidStatus` (`ExpensePaidStatus`, NOT_PAID by default) |
| `model/ExpensePayment` | `id`, `date`, `amountCents` (SIGNED: positive paid to the submitter, negative paid back to the organization), `method`, `note`, `lineIds` (the expenses it settles; may be empty, a pure settlement of the balance), `recordedById`, `recordedBy`, `recordedOn` |
| `ExpensePaidStatus` | NOT_PAID, ORG ("Paid by organization", chip "Org paid"), REIMBURSED ("Already reimbursed", chip "Reimbursed") |
| `ExpenseCategory` | The menu above; `ofBudget(BudgetCategory)` maps by name |
| `ExpenseStatus` | DRAFT, SUBMITTED, PARTLY_PAID, PAID: with no payment, Submitted once stamped else Draft; with any, Paid when every Not-paid expense is in a payment, else Partly paid |

The rate runs the way the sheet wrote it ("USD / EUR 0.83"), with the inverse ("1 EUR = $1.2048") shown
beside it. Every dollar figure is DERIVED on read and never stored, so fine-tuning a rate later re-prices every
expense in that currency. Money is long hundredths in the model and `Double` in forms; the constructors
normalize like the budget's.

`trip_expense_reports` (PK `tripId`, no index) is `dynamo/ExpenseReportDAO`, the `BudgetDAO` shape exactly: a
`PointCache` (`CacheKeys.EXPENSE_PREFIX`, `DAO.CacheScope.EXPENSE`), a versioned conditional put (version 0 may
only create), "Saved by someone else: reload the page and try again." on a lost race, a 350 KB guard. Caps: 300
expenses, 20 currencies, 40 receipts per expense, 60 at report level, 100 payments.

## Formulas (`pay/ExpenseMath`, HALF_UP to the cent)

| Quantity | Formula | Sheet oracle |
|---|---|---|
| usd(line) | USD: the amount; else amount × 1,000,000 / perUsdMicros; a currency with no rate counts $0 and the page warns | 23 EUR → 27.71; 220 EUR → 265.06; 800 KM → 481.93; 200 KM → 120.48 |
| total | Σ usd | 55,560.95 |
| settled / out of pocket | Σ usd of the ORG (or REIMBURSED) expenses / Σ usd of the NOT_PAID ones | with 220 EUR org-paid and 200 KM reimbursed: 265.06, 120.48, 55,175.41 |
| due | cash advance − out of pocket; positive is "Due to {organization}", negative "Due to {submitter}", shown unsigned | −9,795.95 on 45,765.00 (nothing settled); −9,410.41 with the two above |
| payments / still due | Σ signed payment amounts / due + payments; 0 reads "Settled" | −9,410.41 + 9,000.00 → "Still due to Ken Paulsen" 410.41 |
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
- **Rotated** (2026-10-30) from the receipt's own menu, images only (a PDF has no menu): `rotateReceipt(tripId,
  mediaId, "left"|"right")` → `ExpenseReceipts.rotate`, behind the same edit gate as delete. The object is
  overwritten IN PLACE under its key (the servlet answers `private, no-store` and the CDN never serves receipts,
  so there is no shared cache to invalidate); the small rendition is re-derived from the turned image (minted
  as `{id}-small.{ext}` if the receipt had none, dropped when the turn no longer needs one), and the row's
  `rev` moves on. `ReceiptRow.url`/`thumbUrl` append `MediaItem.getCacheBuster()` (`?r=N`), which is what makes
  the page's own image cache let go of the old turn. A second rotate of the same receipt while one runs is
  refused (`RotationGuard`).
- **Deleted** from the dialog by id (`f:param`), or with their expense.
- **Kept** by a trip delete, with the report: they are the record of money that was spent
  ([trip-delete.md](trip-delete.md), "Financial history is never destroyed").

## PDF and email

- **PDF** (`action/ExpenseReportPdf`, drawn with the shared `action/PdfSheet`): the page's own `ExpensePage`,
  section for section (details, currencies, budget, expenses with a Paid column (the row's chip) and a Receipts
  column naming R1, R2..., the payments, totals with the settled split and what is still due, a signature line). "Download PDF" is the report alone; "PDF with receipts" appends them: an index (number, what
  it is for, file, "image" / "PDF, n pages" / "missing"), then each image on a page of its own under its number
  and each receipt PDF's own pages with its number stamped across the top of the first. An unreadable or
  missing receipt is listed, never fatal; a character the font lacks prints as "?".
- **Download:** `web/TripMoneyPdfServlet`, `GET /reports/expense-report?trip=[&receipts=true]` (and
  `/reports/budget?trip=` for the budget), the report's view gate; not signed in goes to login, anyone else is
  404. Never a `.pdf` URL (`PdfRedirectFilter`). REST: `GET /api/expenses/trips/{id}/pdf[?receipts=true]`.
- **The treasurer** is the org-overridable setting `finance.treasurer.email` ("Treasurer email", section
  Finance; validated as one address by the new `SettingDef.withEmail()`): an organization sets its own on its
  Configuration page, and the site's value is the fallback. Unset, the page says so and the Submit dialog
  explains where to set it.
- **Emailing** (owner decisions 2026-10-26): the Submit dialog has a box, checked when a treasurer is set,
  "Email the report and its receipts to the treasurer"; an "Email to treasurer" button resends later. The mail
  (`ExpenseCommands.emailTreasurer`) carries ONE PDF, the report with its receipts; when that is over
  `MailCommands.MAX_ATTACHMENT_BYTES` (7 MB, under SES's 10 MB raw limit after base64) it carries the report
  alone and says the receipts are online. From is the site address, Reply-To the submitter, and the report
  records who it went to and when ("Emailed to ... on ..."). A refused email never undoes the submit. The body
  repeats the totals, with the settled split and the payments and what is still due once there are any.
  `MailCommands.sendWithAttachment` is the first attachment-capable send: raw MIME (`action/RawMime`) over SES
  `SendRawEmail`, with `send`'s local-mode guard, recipient filtering and `EMAIL` audit.

## Privileges

No new privilege, and no second spelling of one: `action/TripFinanceGate` is THE rule for the trip's money
pages, used by both `BudgetCommands` and `ExpenseCommands`. Edit: site admin, `tripMgr@trip`,
`tripFinAdmin@trip`. View: an editor, `tripFinView@trip`, or the global `viewFinances`. Settle (record, edit or
delete a payment; `canSettle`, `ExpenseCommands.canRecordPayments`): site admin or `tripFinAdmin@trip` ONLY, so a
trip manager, usually the one being reimbursed, cannot mark their own report paid. The Reports dashboard's
**Expense Report** card shows for the same people as View (`seeExpenses`).

## The page (`admin/reports/expenses.xhtml`, private repo)

Reached from the dashboard card (`toExpenses`, its number `expenseHeadline`: "$55,560.95 spent" or "No expenses
yet") and from the Budget page (`toExpenses`); back with `reportsDone`, across with `toBudget`, and a print view
(`?print=true`) that edits nothing and ends with the sheet's signature line. Sections: warnings; Report (status
chip, trip dates, the submit line, then the fields for an editor or their values for anyone else; Save details,
Submit / Submit again, Withdraw submission); Currencies; Budget (budget vs actual, with Not in budget and
totals); Expenses (Edit / Up / Down / Delete in `WEB-INF/expenseLineActions.xhtml`, and a Receipts button per
expense, and a Paid column with the row's chip: "Org paid", "Reimbursed" or "Paid Oct 20"); Payments (the
table, Record payment / Edit / Delete for a finance admin); Report receipts; the totals card (total spent; paid by
organization, already reimbursed and out of pocket when any expense was settled outside; cash advance; the due
line; payments recorded and the Still due / Settled line once there are payments; the payment instructions).

The payment dialog (`paymentDlg`) lists every expense still to be paid as a checklist (plus the payment's own
when editing); ticking one refills the amount with the ticked expenses' dollars (`prefillAmount`), which stays
editable and may be negative. A ticked expense must be Not paid and in no other payment. An expense in a payment
cannot be deleted or switched to a settled status until the payment goes (its text and amount stay editable).

The expense dialog asks for the description first (a date picker first would pop its calendar over the menus
when the dialog focuses it), then the date, the budget line (choosing one sets the category to match, still
editable), the category, the currency (choosing "Add a currency..." asks for its code and rate and adds it with
the expense), the amount, the paid status and a note. Refusals show inside the dialog, which stays open (`keepOpen`).

State follows `docs/page-state-and-identity.md`: `requestScope.ep = expenses.reportPage(tripId)` every request
and after every write (`SessionScopePolicyIT` lists it as domain-valued); only the scalar forms (`headerForm`,
`expenseForm`, `currencyForm`, `paymentForm`) and the receipt dialog's `receiptLineId` live in viewScope.

## REST (`api/ExpenseReportResource`, `application/vnd.trip.expense-report.v1+json`)

| Method | Path | Body / answer |
|---|---|---|
| GET | `expenses/trips/{tripId}` | `{header, currencies, lines, payments, receipts, summary}`; `receipts` carry their `lineId` (null = the report) and absolute app URLs |
| PUT | `expenses/trips/{tripId}/header` | `{submittedBy, cashAdvance, cashAdvanceDetails, paymentInstructions}` |
| POST | `expenses/trips/{tripId}/submit` · `.../unsubmit` | stamps / withdraws; submit is 400 while there are no expenses |
| POST | `expenses/trips/{tripId}/email` | emails the treasurer the PDF with receipts; 400 when none is set or the send fails |
| GET | `expenses/trips/{tripId}/pdf[?receipts=true]` | `application/pdf` |
| POST / PUT / DELETE | `expenses/trips/{tripId}/currencies[/{code}]` | `{code, rate, note}`; a currency in use cannot be deleted |
| POST / PUT / DELETE | `expenses/trips/{tripId}/lines[/{lineId}]` | `{date, category, description, currency, amount, budgetLineId, note, paidStatus}`; a new code with `rate` adds the currency in the same save; `paidStatus` NOT_PAID (default), ORG or REIMBURSED |
| POST / PUT / DELETE | `expenses/trips/{tripId}/payments[/{paymentId}]` | `{date, amount, method, note, lineIds}` → `{id}`; the settle gate (403 "Finance admin required." for a trip manager) |
| POST | `expenses/trips/{tripId}/receipts?lineId=&name=` | a RAW body (image or PDF), 413 over 16 MB → `{id, url, thumbUrl}` |
| DELETE | `expenses/trips/{tripId}/receipts/{receiptId}` | |
| POST | `expenses/trips/{tripId}/receipts/{receiptId}/rotate` | `{direction: "left"\|"right"}` → `{id, title, pdf:false, url, thumbUrl, rev}`, URLs ending in `?r={rev}`; 404 not on this report, 409 already rotating, 422 a PDF |

Writes need the CSRF header and the edit gate (403); a refusal is 400 `VALIDATION_FAILED` with the bean's
reason; an unknown trip is 404.

## Tests

Unit (`trip/`): `TripExpenseReportTest`, `ExpenseLineTest`, `CurrencyRateTest`, `ExpenseMathTest` (the oracle in
`pay/ExpenseOracle`), `ExpenseReportDAOTest`, `ExpenseCommandsTest` (gates, the oracle on the page rows, every
refusal, a currency from the dialog, budget vs actual for all three kinds of budget line, submit, paid statuses,
payments either way and with no expense, the settle gate),
`ExpenseReceiptsTest` (both renditions, PDFs, refusals, rollbacks, the S3 branch), `ExpenseReceiptServletTest`,
`ExpenseReportResourceTest`, `MoneyFormsTest`, `ExpensePaymentTest`, `ExpenseReportPdfTest`, plus cases in `AuditCommandsTest`, `MediaTenancyTest`,
`MediaCommandsPresignTest`, `MediaResourceTest`, `ModelSerializationTest`, `SerializationCompatibilityTest`,
`ApiSurfaceTest`.

Browser (`medjugorje/webtest`): `ExpenseReportPwIT` (the oracle and the print view, a currency from the dialog
then fine-tuned, a matched expense moving its budget line, row commands by identity, receipts uploaded on an
expense and on the report and served only to the report's readers, submitting, an org-paid expense leaving the
due, payments recorded against ticked expenses then edited and deleted by identity, the gate matrix including a
trip manager who may not record a payment), with the card
and page added to `ReportsDashboardPwIT`, `AdminReportsPwIT` and `AuthGatePwIT`.

## Out of scope (for now)

Several reports per trip, a reimbursement split across people beyond the payment instructions text.
