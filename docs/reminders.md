# Trip reminders and the Communications page

A trip's **Communications** page (`admin/reports/communications.jsf?trip=`, a card on the trip's Reports
dashboard) shows a facilitator everything the trip says to its travelers and when:

1. **Scheduled messages**: reminders the facilitator words, previews, tests and approves.
2. **Sender**: the From and Reply-To those messages carry.
3. **Sent automatically**: the transactional email the trip already sends (read-only).
4. **Sent history**: what actually went out, to whom, with the result.

Issue: medjugorje#44. The org-level "created an account, never registered" outreach is a separate issue
(medjugorje#125) and is not built.

## The rule: nothing unapproved is ever sent

This is the reason the feature has the shape it has. A message a facilitator has not seen must never reach a
traveler, whether because it is wrong or simply because the facilitator did not know it was going out.

- A new trip has **no** scheduled messages. "Add suggested messages" adds them as **drafts**. A draft is never
  sent.
- Approving a message records `approvedBy`, `approvedAt` and an **approval hash** over the subject, body, push
  text, audience, schedule AND the date that schedule resolves to on the trip
  (`TripReminder.approvalHash`).
- The send gate is `TripReminder.isApprovedFor(trip)`: status `APPROVED` **and** the hash recomputed now equals
  the stored one. `ReminderSender.sendAll` checks it itself, so no caller can forget to.
- So an edit through any path stops the send without anyone resetting a status, and so does **moving the
  trip's start date or balance due date**: the message would go out on a day nobody approved. The page shows
  such a row as "Changed, approve again".
- Saving a message in the dialog always stores a draft (`ReminderCommands.save`). The name is the one field
  outside the hash: it is the facilitator's label and travelers never see it.
- The wording is a **snapshot on the trip**. Suggested messages COPY the organization's starter template; a
  later edit of that template never changes a trip's message.
- An approved message whose date passed unsent (beyond the one-day catch-up) is **Missed**. It is not sent
  late by itself; the facilitator may use Send now.
- On top of all that there is a site switch, `reminders.enabled`, which ships **off**
  (`KnownSettings.REMINDERS_ENABLED`). With it off nothing is sent on a schedule; Send now still works.

## Model and storage

`trip_reminders` (PK `tripId`, SK `sk`), one partition per trip, **uncached** (`ReminderDAO`: the plan decides
whether mail may go out, so a stale approval must never be read):

| Item | `sk` | What |
|------|------|------|
| `TripReminderPlan` | `PLAN` | The trip's `TripReminder`s inline, plus `mailFrom` and `replyTo`. One versioned conditional put per save (the `BudgetDAO` shape): a lost race is refused as "saved by someone else". |
| `ReminderSend` | `SEND#<reminderId>#<sendDate>#<personId>` | One row per traveler, reminder and send date. Written with `attribute_not_exists` BEFORE the mail goes out. |

The `ReminderSend` row does two jobs. It is the **claim** that makes a send happen at most once across servers,
restarts, and a Send now racing the scheduler; and, read back, it is the **Sent history**. It captures the
reminder's name, the subject and the address, so the history reads correctly after an edit or a delete. A run
that matched nobody leaves one row with no traveler (`Outcome.NOBODY`), so an on-time run with an empty audience
is "Sent to 0 people", not "Missed".

A trip delete removes the whole partition (`TripDeleteCommands`).

`TripReminder`: `kind` (`BALANCE_DUE`, `WAIVER_UNSIGNED`, `PASSPORT`, `COUNTDOWN`, `CUSTOM`), `anchor`
(`BALANCE_DUE_DATE`, `TRIP_START`, `TRIP_END`, `FIXED_DATE`) + `offsetDays` (negative = before) or `fixedDate`,
`audience`, `subject`, `body` (cleaned HTML), `pushText` (blank = email only), and the approval fields. A
built-in kind FIXES its audience (the constructor enforces it); only `CUSTOM` chooses one. A reminder whose
anchor date is missing on the trip (no balance due date, no start date) has no send date and can be neither
approved nor sent.

## Who receives a message

`ReminderRecipients.targets(trip, reminder, today)` is the ONE implementation, asked by the page's review
dialog, by the preview, and by the send, so the list a facilitator looked at is produced by the code that
mails it. The audience is evaluated **on the day it is asked**, never frozen at approval: someone who paid
yesterday is not reminded today. Every read is fresh (`Cached.NO`).

| Audience | Who |
|----------|-----|
| `ALL_CONFIRMED` | every CONFIRMED registration |
| `WITH_BALANCE` | CONFIRMED and `CheckinCommands.balanceOf(trip, person).dueCents() > 0` (the trip ledger, never the person's whole ledger) |
| `UNSIGNED_DOCUMENTS` | CONFIRMED and `WaiverCommands.pendingFor` is not empty |
| `PASSPORT_ISSUE` | CONFIRMED and no passport on file, no expiration date, or one expiring less than 6 months after the trip ends |
| `PENDING` | registrations awaiting approval |

A traveler with no usable address is reached through `PersonCommands.mailableManagers` (their family
managers): the message greets the manager (`{{firstName}}`) and names the traveler (`{{travelerName}}`). With
no manager either, the send is recorded as "No email address".

There is no email opt-out on `Person` today; these are operational messages about a trip the person is on.
Push honors the person's own push settings.

## Wording

`ReminderText` holds the `{{token}}` vocabulary, the unknown-token check and the substitution. It is Java-only
substitution with no EL, like the managed MAIL templates, so a facilitator's text can never evaluate anything
and it renders on the scheduler's thread. A message with an unknown token is refused at save. The body is
HTML-escaped per value (`render`); the subject and the push text are plain text (`renderPlain`).

The body is cleaned by `ReminderHtml.clean`, an ALLOWLIST: a facilitator (who may hold no privilege) writes it,
and it is rendered unescaped to every reviewer, admins included, and mailed to travelers. Only the tags the
editor writes are kept, each rebuilt with no attributes except a link's checked address (`http://`, `https://`,
`mailto:` or exactly one `{{...Link}}` placeholder) and Quill's own `ql-` classes; inline styles, images and
every unknown tag are dropped. It runs when a message is saved and again where the body is written out (preview,
test send, send). Never clean this body with `WaiverHtml.sanitize`: that is a blocklist for text only an org
admin writes, and the first review found three bodies it passes unchanged (`ReminderHtmlTest` pins them).

Tokens: `firstName`, `travelerName`, `tripTitle`, `tripDates`, `daysUntilDeparture`, `balanceDue`,
`balanceDueOn`, `documentList`, `passportIssue`, `orgName`, `tripLink`, `paymentLink`, `documentsLink`,
`itineraryLink`, `profileLink`. Links are built on the trip's owning organization's site (`SiteUrls`).

The four starter texts (`content/ReminderDefaults`) are also MAIL starter templates (`reminder-balance-due`,
`reminder-waiver`, `reminder-passport`, `reminder-countdown`), so an organization can reword its own starting
text on the Templates page. A trip copies the org's copy when it is installed and uses only known tokens, else
the built-in text, so "Add suggested messages" works before `install-starter-templates.sh` has been rerun.

## Sending

`ReminderSender.sendAll(trip, plan, reminder, sendDate, today, manual, who)` is the single path to a mailbox:

1. refuse unless `isApprovedFor(trip)` and `sendDate` is the reminder's own send date;
2. refuse without a From (`effectiveFrom`: the plan's own while the trip's organization may still send from
   it, else what the trip's payment mail inherits);
3. per traveler, under a semaphore of 8 inside a `StructuredTaskScope` (the mail merge's shape): **claim**,
   then mail each addressee, then push when the reminder has push text, then record the outcome.

A failed send stays recorded as failed. It is never retried silently: missing a reminder beats sending it
twice. The facilitator sees it in the history and may "Allow resend", which clears that one row so Send now
includes the traveler again. A sent row can never be cleared.

`reminder/ReminderScheduler` is `ChatDigestScheduler`'s shape: one virtual timer thread, a run at
`reminders.hour` in `reminders.zone` (which also decides what day it is), a 5-minute retry after a failure for
up to 2 hours, and the system identity bound for the attempt. It is started from `ChatLifecycleListener` (the
listener order lives in the deployed `web.xml`). What differs from the digest is where "once" comes from: the
digest's guarantee is a cache lock; here it is the DynamoDB ledger, so the cache lock only saves a second
instance the same reads, and a cache that grants every lock (`NoopCacheClient`) still sends once.

Each run sends what is due on its day and **catches up one earlier day**, but only when that day's run never
happened (no ledger row for it). Had it run, sending again would mail people who joined the audience since,
a day late and unannounced.

Push: kind `trip.reminder` (`PushPayload.KIND_TRIP_REMINDER`), through `PushNotifications.tripReminder` and
`PushSender.sendAlert` (so `push.enabled`, the person's switch and quiet hours all apply). A balance reminder
opens the trip's payments (`unitetrip://trip/<id>/payments`), every other kind the trip
(`unitetrip://trip/<id>`).

## Access

`ReminderGate.canManage`: a site admin, an admin of the trip's organization (`OrgCommands.canManageOrg`),
`tripMgr@trip`, or a person in `Trip.facilitatorIds`. One rule for seeing, writing, approving and sending:
whoever can see what goes out is whoever answers for it, so there is no read-only tier. Finance roles do not
get in. The Reports tab and the dashboard's admission union both include it, so a facilitator who holds no
privilege still reaches the card. Every change and every send is audited (`AuditAction.REMINDER`).

## The page

`#{reminder}` is `ReminderCommands`. The page follows `budget.xhtml`: the trip id pinned in the view, rows in
`requestScope` rebuilt every request and after every write, row commands carrying their id in an `f:param`, and
the message dialog's scalar form (`ReminderViews.Form`) in the view with refusals shown inside the dialog.

The **review dialog** is where approval happens: it shows the message as one real recipient would read it
(the first person it would go to today; sample values when nobody would), the app notification text, and the
names it would go to today with the reason each is in the audience. "Send test email" mails that rendering to
an address the facilitator types (prefilled with their own, editable); a test writes nothing to the history
and approves nothing.

"Sent automatically" lists registration received, registration approved, checkout confirmation (when the
trip takes checkout) and the payment confirmation and payment failed mail (when the trip is payable), each
with the wording in force for the trip's organization, placeholders left as written. They stay automatic: each
is a reply to something the traveler just did.

## Deploying

- The `trip_reminders` table is in the CDK (`TripApp`); `cdk deploy` is owner-run, and PITR is enabled by
  hand after the first deploy.
- Rerun `install-starter-templates.sh` to install the four starter texts (optional, see above).
- Turn on `reminders.enabled` under Settings, "Scheduled trip messages", when ready.

## Tests

`TripReminderTest` (the approval rule), `ReminderDAOTest`, `ReminderTextTest`, `ReminderCommandsTest` (gate,
drafts, audiences, sending and the ledger, against the in-memory store with a fixed clock),
`ReminderSchedulerTest` (each kind fires once on its date, a draft never fires, two servers send once, catch-up),
and the browser test `CommunicationsPwIT` in the medjugorje repo.
