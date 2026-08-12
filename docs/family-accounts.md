# Family accounts

One login owning several full `Person` profiles (passport and all), registered and managed together.
Read this before touching `FamilyCommands`, `Person.managedUsers`, `SupportChatCommands`, the acting-for
subject resolution, the registration party flow, or MAIL templates.

## The model

- **The `family` DynamoDB row is the source of truth**: `Family` = `{id, memberIds, managerIds,
  createdBy, created, version}` as `content` JSON, plus a TOP-LEVEL `version` (N) attribute.
  `FamilyDAO.saveFamily` is an optimistic conditional put (version 0 may only CREATE via
  `attribute_not_exists`; otherwise `#v = :expected`, always expression-aliased). A lost race throws
  `ConditionalCheckFailedException` — surfaced as "family changed, retry", never retried silently,
  because the caller's deltas were computed against the losing row.
- **`Person.familyId`** (nullable) is the reverse edge — there is deliberately no scan path and no GSI.
  One person, one family, by construction. It is the ONLY Person field added by this feature, and it is
  setter-populated (the `@Builder` sits on the 17-arg constructor precisely so new fields don't churn
  every caller).
- **`Person.managedUsers` stays the site-wide authorization primitive** (`canAccessUserId`, page auth,
  REST `AccessLevel.MANAGER`). `FamilyCommands` DERIVES each manager's list from the family row with
  surgical deltas — add/remove specific ids, never wholesale replacement — so admin-granted visibility
  outside any family survives every family operation (a property test pins this).
  Known accepted collision: revoking a manager removes exactly the family-member ids, which also
  removes an admin grant for a person who later JOINED the family (rare, admin-repairable,
  `family-consistency-check.sh` flags it).

## FamilyCommands (`#{family}`) — the security boundary

Self-service is **create-and-link only**: `createFamilyMember` builds the new Person itself, so a
non-admin can never link an id they did not just create. Linking an EXISTING person (spouse with their
own login) is `adminLink`, on the Manage People page. Everything is audited (`AuditAction.FAMILY`).

Write order everywhere: person row that must exist → versioned family put → derived `managedUsers`
sync computed from the IN-MEMORY objects just saved (a re-read can be stale in production) → audit.
`resyncFamily` is the idempotent repair: back-pointers + missing manager entries, ADDITIVE-only.

Guard rails:
- `family.maxMembers` (KnownSettings, default 10) caps size; at the limit the family page offers a
  support request instead.
- **A manager must have their own VALID email** (`EmailAddresses.isValid`, not merely non-blank) —
  managers sign in themselves, and `foo` in an email field is a filled-in field with no mailbox behind
  it. Enforced at all three grant sites (`createFamilyMember`, `setManager`, `adminLink`) with a faces
  message; the family page's action buttons redirect only on success so the refusal actually renders,
  and the add dialog pre-checks the same rule client-side (see below). Legacy data can still hold a
  no-email manager (an email removed after the grant); the unlink rules and the consistency script
  cover that.
- **Sex AND birthdate are required** wherever a person is created (`createFamilyMember`, the add
  dialog, the create-account page) — passports, rooming, insurance, and age-based pricing need them,
  and a profile created without them just becomes a follow-up later. Existing rows are NOT
  retro-forced. A future birthdate is refused as a typo.
- **Birthdates are typed, not picked**: every birthdate input is a `p:inputMask` (`99-99-9999`,
  MM-DD-YYYY) — `p:calendar`'s month/year navigator dropdowns only move the calendar view (the input
  text never changes until a day is clicked, which reads as broken), and paging back decades is worse
  than typing 8 digits. Webtests must `pressSequentially` digits into masked fields, never `fill`.
- **Save returns to the linking page**: `person.xhtml` captures `tripUtil.refererPath()` (validated
  same-site path; login pages and the profile page itself are never return targets) on first render,
  and Save redirects there with the saved banner (`tripUtil.withInfo`) — the fix-missing-info round
  trip from the family page comes back to the family page. No usable referer ⇒ the old
  `/account.jsf` fallback.
- **The profile's Family fieldset** lists the household (name, manager chip, per-member Profile and
  Balance links, up to 5 recent trips via `trip.recentTripsFor`) with a "Manage Family" button —
  rendered for anyone who can view the profile (the page is already auth-gated), the button only for
  viewers IN that family (family.jsf is viewer-scoped), the adminManagePerson link only for admins.
- **The add dialog validates twice on purpose**: `tripCheckAddMember()` (family.xhtml) catches the
  mistakes the browser can see — required fields, email shape, manager-needs-an-address — with no round
  trip, and the command re-checks every one of them. The button's ajax MUST render something
  (`update=":form:growl"`): `update="@none"` makes PrimeFaces skip the render phase entirely, growl's
  `autoUpdate` included, so a server-only refusal (a duplicate address) looks like a dead button.
- Managers may DELETE only a member with zero trips (`getTripsForUser` — the registration proxy) and
  zero transactions (soft-deleted ones count). Anything with history needs an admin **unlink**
  (Manage People, type-"remove" challenge validated server-side), blocked when the remaining family
  would keep no manager (2+ members) or no member with a valid email.
- **Person-delete is family-aware**: the Manage People delete runs `adminUnlink` first — a deleted
  person still listed in `memberIds` renders blank everywhere.
- Null emails are legitimate (members without logins); a NON-blank email must be unique across people,
  enforced in `PersonCommands.savePerson` (every writer funnels through it) and answered as 409 by the
  REST people endpoints. Clean existing duplicates first (`report-duplicate-emails.sh`) — the
  email-index `.findFirst()` makes ownership arbitrary until then.
- **Adding a missing address**: `account/person.xhtml` makes the email field editable only while it is
  EMPTY (changing an existing one renames the sign-in credentials with it — that stays with an admin).
  Leaving the field runs `PersonCommands.emailConflictName` and, when taken, opens a dialog explaining
  that one address belongs to one account, suggesting they sign in with it instead, and offering
  `SupportChatCommands.fileEmailConflictRequest` (an `@all` post to the support channel) for a merge.
  The conflict answer names the holder only as "First L." and the support message carries no id for
  them: it answers a typo, it is not a directory lookup.
- **Display-only contact fallback**: `people.contactEmail(person)` / `contactEmailVia(person)` answer a
  no-address member with their family's primary manager (the family creator while still a manager, else
  the first-listed manager, skipping any address that is not valid), rendered as
  `parent@x.com (via Ken)`. Used on the people list and trip contacts. Deliberately NOT used to address
  outbound mail — the sending paths choose recipients explicitly (see `approvalRecipient`) — and NOT on
  reports/exports, where a substituted address would silently misattribute in a spreadsheet.

## Acting-for (the switcher)

`PersonCommands.getSubject(idParam)`: explicit `?id=` (page auth still gates it) → the sticky session
selection `actingFor` (validated on every read; stale entries self-clear) → the signed-in user. The
topbar "Viewing: X" chip is both the indicator and the switcher: rendered for family MANAGERS in a
family of 2+ (2026-08-12: non-managers get no chip — acting-for is a manager power, `actFor` refuses
non-managed targets, and a switcher that never switches reads as broken; refusals also growl now),
showing the current subject (your own name when unswitched), and clicking it drops down the member
list (the Freya `topbar-item` menu behavior), filtered to members the viewer can act for. **Identity never changes** — `userId`, the
audit actor, chat authorship and payments stay the signed-in user, which is what separates this from
the admin View As swap. `payByCard` stashes the chosen subject in `sessionScope.payFor` because the
PayPal return URL carries no query string. Switching the acting-for subject clears the in-flight flow
keys (`payFor`, the pending-upload and bg-cutout tokens) so an abandoned payment target or staged
upload can never attach to the next member.

## Admin View As (2026-08-11: snapshot push/pop)

View As no longer mutates `userId`/`userRole` in place (which leaked every session key — resume
banners, `regDraft:*`, `payFor`, even the one-shot `codeLogin` grant — between identities).
`Sessions.pushViewAs` snapshots ALL session attributes onto a `viewAsStack`, clears the session, and
seeds the target identity; Back-to-Admin runs `Sessions.popViewAs`, which wipes whatever the
viewed-as browsing accumulated and restores the snapshot. Three things deliberately survive the push:
`aUser` (renders Back-to-Admin), `loginEmail` (audit rows during View As read admin-email acting as
target-id — attribution stays with the real human), and `dark`. The page hooks are
`#{people.viewAs(id)}` / `#{people.endViewAs()}` (template.xhtml `viewAs` event, topbar
`back2Admin`); the audit `impersonation` record is written BEFORE the push, while the actor is still
the admin. `SessionsTest`/`PersonViewAsTest` pin the contract.

## Subject-aware family page (2026-08-11)

`account/family.jsf` accepts `?id=` (the subject whose family to show) and `?trip=` (where a "Done —
back to registration" button returns to). No params keeps the old my-family behavior. Authorization:
a subject inside the caller's own reach (`canAccessUserId`) passes as before; anything else sets
`reqId` to the subject so `defaultAuth` admits only admins. Edit affordances render on
`family.canManage(family)` (a manager of THAT family, or a site admin — `deleteFamilyMember` /
`setManager` now anchor on the MEMBER's family so the admin path really works); the support-request
buttons stay on `amRealManager` (a support request files against the REQUESTER's family — an admin
gets an "Unlink (admin)" link to adminManagePerson instead). Member creation goes through
`FamilyCommands.addFamilyMemberFor(subjectId, ...)`, which anchors `ensureFamilyFor` on the SUBJECT
(access-checked) so an admin's add lands in the viewed family, never their own. joinTrip's
"Add New Family Member(s)" button carries `?trip=` and (when the visit was `?id=`-scoped) `?id=`;
all of family.jsf's self-redirects preserve both.

## Family visibility closures

`TripCommands.canSeeTrip` (the old double-FIXME), `ChatCommands.isTripMember` (full chat membership
for a parent whose member is on the trip; author stays the parent), `myChats` (union of own + managed
members' trips — channels are lazy, only existing channels list), `everyoneIn`/`rosterJsonForTrip`
(roster ∪ explicit JOINED membership rows: a parent gets `@all` mail once they have interacted with
the chat; no reverse who-manages-whom lookup exists or is needed), todo page, `TripsResource.canActFor`.

## Family registration

`RegistrationCommands.registerParty(trip, selected, regs, digests)` writes one ORDINARY
`(tripId, userId)` PENDING row per selected traveler — the admin approve flow, rosters, exports, and
the future mir2026 pricing layer see nothing new. Family linkage rides in `Registration.options` under
reserved underscore keys (`OPT_REGISTERED_BY`, `OPT_PARTY` — the mir2026 convention). Per-traveler
daily-digest answers are parked on the registration before saving (approval applies them, unchanged).
Per-person approval is the only status — no party-level status, by decision.

**One merged view (2026-08-11).** The old single-traveler page (dynamic option components, its own
Register/Save button) is GONE: `joinTrip` always renders the "Who's going?" party view — ANY member
of a 2+ family without `?id=` sees the whole household listed (2026-08-11: the old manager-only
listing gate hid a member's own family from them); every `?id=` deep link stays a party of one. WHO
may actually be registered is gated per row: the checkbox is enabled only when
`RegistrationCommands.canRegister(traveler)` — self, a managed user, or a site admin — the SAME
method `registerParty`/`saveResponseEdits` enforce on save, so the UI can never enable what the
write refuses (site admins were added to both sides together). Non-registrable rows show a
"(a family manager can register them)" hint. The option questions render as the app-wide
property-sheet (hand-written `td.propSheetLabel`/`td.propSheetValue` rows — a `ui:repeat` is one
child to `p:panelGrid`, so the grid cannot pair label/value cells across repeated rows). A traveler already ON the trip roster bypasses `canJoin`
in `registerParty` (people are added to rosters by hand; filing their row afterwards must keep
working — the old single page never checked `canJoin`). An already-registered traveler keeps their
row: a checked-but-disabled checkbox for visual consistency (nobody unregisters themselves here),
collapsed by default, expanding on a row click; while `reg.allowEdits` (KnownSettings, default ON)
permits, their option answers — and, while still Pending, the parked digest choice — stay editable,
saved by the same submit through `saveResponseEdits`: config-gated, applied onto the freshly-read
STORED row (never the draft object wholesale — its status can be behind an admin's approval), only
the form's own fields, no-op submits write nothing. "Add New Family Member(s)" (a
`process="@form"` button, bottom-left of the fieldset) is the path into family registration for
everyone, replacing the old famHint banner.

**The session draft (2026-08-10).** The form's working copy — per-member `Registration` (typed option
answers), selection, digest choice — is a `RegistrationDraft` held in SESSION under
`regDraft:{tripId}`, not in viewScope: registering routinely detours to the family page to add a
member, and a view-scoped copy lost every typed answer. `RegistrationCommands.loadDraft` reconciles a
returning draft against the store — an entry survives only while the store and the draft agree on
STATUS (so a stale draft can never resurrect or overwrite a registration that moved on, while an
in-progress re-EDIT of a registered row still survives the detour), members outside the current list
are carried (an `?id=` visit must not wipe the rest of the party), and new members get defaults. The
page binds inputs to the draft's own maps, so any postback persists them; the add-member button is
`process="@form"` so the detour itself saves the form.
Per-member options stay COLLAPSED until that traveler's checkbox is checked (`p:ajax process="@form"
update=":form:famReg"` — processing first so a reveal never drops another member's typing). The
daily-digest toggle defaults **ON** for an unanswered registration (`digestChoiceOrDefault`;
`digestChoice` stays strict for the approval path).

**The login deep link.** An anonymous Register click stashes `afterLoginURL` in session AND carries
it as `?to=` on the login URL, and the stateless login pages (`login`, `login-pass`, `login-code`)
carry it through their own POSTs in a hidden `tripUtil.returnPath` field — a session lost mid-login
(redeploy, or a user parked on the login screen past the timeout) used to strand the deep link and
land them on the home page after account creation. `normalizeReturnPath` accepts only same-site
absolute paths, so the user-controlled field cannot become an open redirect.

## Registrant emails (MAIL templates)

`TemplateKind.MAIL` content templates are runtime-editable email copy (`admin/templates.xhtml`, raw
HTML mode): body + NAME-as-subject with `{{token}}` substitution — tokens filled by JAVA only
(`MailCommands.renderManagedTemplate` / `sendManagedTemplate`; String values escaped, `Raw` verbatim,
domain objects are a loud error). **Never EL** — runtime-editable EL would be code execution. No
instances, never offered by content pickers, excluded from page render. Shipped templates:
`registration-received` (to the submitting owner), `registration-approved` (to the person, else
whoever registered them — `approvalRecipient`), `support-request` (to support-channel admins).
A missing template logs and SKIPS the mail; it never blocks the flow that wanted to send it.
From/reply-to/base-URL: `reg.mail.*` settings.

## The support channel

`support:main` (`ChatChannel.Kind.SUPPORT`) follows the photo-channel precedent: its own command class
(`SupportChatCommands`) carrying its own authorization over the untouched chat tables. Explicit JOINED
membership rows ARE the admin list (picked on the Settings page, `configAdmin`-gated); global
`chatAdmin` always reads. Requesters post WITHOUT joining (`fileRemovalRequest` validates the family
relationship itself; `fileLimitRequest` validates the limit) — marker first lines
(`[support:family-removal]`, `[support:family-limit]`) drive a 24h one-open-request guard. Email
fan-out is membership-row based behind `support.mail.enabled` (deliberately NOT the chat-mail master
switch, which ships off). The admin UI is the dedicated `admin/support.xhtml` (membership-gated, not
role-gated) — NOT chat.xhtml: `ChatResource`'s ~15 endpoints hard-gate on `trip:` ids on purpose.

## Migration (one-time, user-run; scripts in `medjugorje/scripts/`)

1. `report-duplicate-emails.sh` — clean duplicates by hand first.
2. `family-report.sh` — clusters legacy `managedUsers` into a PROPOSED-families file. Review it:
   delete staff-visibility grants that are not families, and read the `adds` lines (merging two
   managers means each gains the other's people, including each other).
3. `family-migrate.sh --in <file> --apply` — family rows + back-pointers + additive sync. Run at a
   quiet time (person rows are read-modify-written whole); clear caches from Settings afterwards.
4. `family-consistency-check.sh` — the invariant checker, also the ongoing ops check.

Local mode seeds one family through the real commands (`FakeData.addFakeFamily`): Ken (user2) +
Trinity (user4) as managers + "Lucy", a created member with no email.

**The two seeded managers hold real addresses** (`user2@example.com`, `user4@example.com`) because a
manager must be mailable — with bare personas the seed could not be built at all once that rule
tightened. Everyone else keeps their bare persona (`user3`, `admin`, …) ON PURPOSE: production holds
both shapes, so the rules that turn on a valid address stay exercised in both directions. Signing in is
unchanged — `user2` / `user` still works, because `PersonDAO.getPersonByEmail` falls back to
`<persona>@example.com` **in local mode only** (every path that resolves a typed login to a person goes
through there: `setPass`, `createCreds`, the fake credential store). Production never reaches it.
