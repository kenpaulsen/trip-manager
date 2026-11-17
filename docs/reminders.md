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
- Approve is tied to what was SHOWN: the review dialog carries the approval hash of the message it rendered,
  and `ReminderCommands.approve(tripId, id, shownHash)` refuses when the stored message no longer hashes to it
  (someone edited it, or the trip's date moved, while the dialog was open). There is no approve without a hash.
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

A trip delete removes the whole partition (`TripDeleteCommands`). An ACCOUNT deletion keeps the rows (so "this
reminder already ran" still holds) but erases the person from them: `DAO.scrubReminderSends` rewrites their rows
with the name "Deleted Account" and no address, and blanks the address on any row that mailed theirs (a family
manager's address sits on a dependent's row). `AccountDeletionCommands.leaveTrips` calls it for every trip.

`TripReminder`: `kind` (`BALANCE_DUE`, `WAIVER_UNSIGNED`, `PASSPORT`, `COUNTDOWN`, `CUSTOM`), `anchor`
(`BALANCE_DUE_DATE`, `TRIP_START`, `TRIP_END`, `FIXED_DATE`) + `offsetDays` (negative = before) or `fixedDate`,
`audience`, `subject`, `body` (cleaned HTML), `pushText` (blank = email only), and the approval fields. A
built-in kind FIXES its audience (the constructor enforces it); only `CUSTOM` chooses one. A reminder whose
anchor date is missing on the trip (no balance due date, no start date) has no send date and can be neither
approved nor sent. A send date later than `TripReminder.MAX_DAYS_AFTER_END` (365) days after the trip ends is
refused at save and at approve: the daily run reads trips that ended within that window, so a later date
would be approved and never reached.

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

### What a viewer of the page may see

Being let into the page (below, "Access") is not being shown everything. Owner decision 2026-11-15: redact
only the values a viewer may not see, and keep the rest of the message as it is. `ReminderGate.sight(tripId)`
answers per caller, by the rules the rest of the site already keeps:

| Value | Shown to | Otherwise |
|-------|----------|-----------|
| an amount owed (`{{balanceDue}}`, "Owes $250.00") | whoever may see the trip's money (`TripFinanceGate.canView`) | "(amount not shown)", reason "Owes a balance" |
| a passport fact (`{{passportIssue}}`) | a site admin or the trip's `tripMgr` (`AccessLevel.TRIP_ADMIN`) | "(passport details not shown)", reason "Passport needs attention" |
| an email address (recipient list, sent history) | the same trip-admin rule | "(address not shown)" |

`Target.seenBy(sight)` applies it, and only to what is SHOWN: the review dialog, the preview and the test email
(which carries exactly what its sender was shown). The real send always uses the real values. Words that imply
a balance or a passport problem are deliberately kept: that a traveler is in the audience is what the page is
for. The approval hash is the same whoever looks, so what is approved does not depend on the viewer.

There is no email opt-out on `Person` today; these are operational messages about a trip the person is on.
Push honors the person's own push settings.

## Wording

`ReminderText` holds the `{{token}}` vocabulary, the unknown-token check and the substitution (the engine,
`TokenText`, is shared with the mail merge's `MergeText` since 2026-11, when the merge stopped using EL). It is Java-only
substitution with no EL, like the managed MAIL templates, so a facilitator's text can never evaluate anything
and it renders on the scheduler's thread. A message with an unknown token is refused at save. The body is
HTML-escaped per value (`render`); the subject and the push text are plain text (`renderPlain`).

The body is cleaned by `ReminderHtml.clean`, an ALLOWLIST: a facilitator (who may hold no privilege) writes it,
and it is rendered unescaped to every reviewer, admins included, and mailed to travelers. Only the tags the
editor writes are kept, each rebuilt with no attributes except a link's checked address (`http://`, `https://`,
`mailto:`, `tel:`, `sms:` or exactly one `{{...Link}}` placeholder), a list item's `data-list` kind, and the few
Quill layout classes the toolbar can produce (never `ql-hidden` or `ql-clipboard`, which Quill's stylesheet
hides: the reviewer's page has that stylesheet and a mail client does not). Inline styles, images and every
unknown tag are dropped, and the page's toolbar offers only what is kept. A paste can still bring a color, an
image or a link the cleaner refuses (a scheme-less `example.com` leaves an `<a>` that goes nowhere), so the save
still stores the draft but then warns "Some formatting this message cannot carry (colors, images, or a link that
is not a full web, email or phone address) was removed. Check the preview." The cleaner itself reports the drop
(`ReminderHtml.cleanNoting`, the same single pass that produces the stored body, noting each tag, style, class or
address it refuses); a string comparison would fire on every body, since cleaning always rewrites a link's
`rel`/`target`, escapes and trims. A small image (a remote `https://` one) is dropped with that warning, but a
pasted image whose tag runs past `MAX_TAG` (any data address of real size) is refused outright: the cleaner could
only write it out as that much visible text (`ReminderHtml.hasUnreadableTag`; "Something pasted into this message
(usually an image) is too large to send").
Quill 2 writes every list as `<ol>` and marks bullets with `data-list`; the cleaner writes a bullet list as `<ul>`
so a mail client shows bullets.

It runs when a message is saved and again at EVERY place the stored body is written out: the editor
(`formFor`), `ReminderText.renderBody` (the clean-then-merge helper behind the preview and the test email), and
the send, which cleans the body once in `ReminderSender.sendAll` and merges it per traveler with
`ReminderText.render`. The raw body is refused above `MAX_RAW_BODY` before it is cleaned, and a save is refused
when any tag runs past `ReminderHtml.MAX_TAG` (a link's, an address of about 1,950 characters or more, gets its own
message): the cleaner would otherwise write that tag out as visible text. "Add suggested messages" never copies
such a tag either: an org starter template holding one falls back to the built-in text. Never clean this body with
`WaiverHtml.sanitize`: that is a blocklist for text only an org admin writes, and the first review found three
bodies it passes unchanged (`ReminderHtmlTest` pins them).

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
twice. The facilitator sees it in the history and may "Allow resend", which clears that one row; the message's
row then offers "Send to anyone missed", which mails only travelers with no ledger row for that date (the
cleared one, or someone who joined the audience since). A sent row can never be cleared. A run in which every
send failed shows as "Failed", not "Sent".

`reminder/ReminderScheduler` is `ChatDigestScheduler`'s shape: one virtual timer thread, a run at
`reminders.hour` in `reminders.zone` (which also decides what day it is; `ReminderClock` is the one reading of
it, shared with the page, and a bad value falls back to the setting's default), a 5-minute retry after a
failure for up to 2 hours, and the system identity bound for the attempt. A failed run RELEASES its lock
before it rethrows, or the retry would be turned away by the run's own lock; and a lock refused by a cache
that cannot even store a value means the cache is down, not that someone holds the run, so the run goes ahead. It is
started from `ChatLifecycleListener` (the
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
and approves nothing, goes through `ReminderSender`'s mail seam, and is capped at 20 per person per hour so the
button is not a way to mail strangers from the organization's address.

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
