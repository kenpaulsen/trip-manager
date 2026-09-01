# The Org Admin Area (org migration, 2026-08)

Organizations are the tenancy boundary; this doc covers the self-service surface an org's own staff use and
the privilege model behind it. Payments-side org details (processor configs, the payment-config ladder) are
in `payments.md`; family-admin gating is in `family-accounts.md`.

## The hub and its pages

`admin/orgSettings.jsf?orgId=…` is a dashboard of cards; each area is its own page, all sharing the
`WEB-INF/orgTabs.xhtml` nav strip and the self-gating pattern (org authority has no role or privilege row,
so these pages gate themselves in `initPage`, NOT via defaultAuth):

| Page | View gate (`OrgCommands`) | Mutations |
|------|---------------------------|-----------|
| `orgSettings.jsf` (hub) | `canViewOrgHub` — admin OR any org-scoped privilege here | — (cards render per-permission) |
| `orgProfile.jsf` | `canManageOrg` | `saveOrgEdits` (rename stays site-admin) |
| `orgTrips.jsf` | `canViewOrgTrips` — admin OR `addTrip@org` | New Trip: `canCreateTripFor` |
| `orgPeople.jsf` | `canViewOrgPeople` — admin OR `peopleAdmin@org` | **org admins only** (all of them) |
| `orgProcessors.jsf` | `canManageOrg` | `canManageOrg` |
| `orgConfig.jsf` | `canManageOrg` | `saveOrgSettings` (see "Per-org settings") |

The menu's per-org entries come from `org.visibleOrgs()` (admins ∪ holders of any org-scoped privilege), so
reachability and the hub gate agree. Site admins pass everything via the usual short-circuits.

## Org-scoped privileges

`Privilege` scope suffixes are opaque UUIDs; the base name decides whether a row scopes to a trip or an
org (`PrivilegeCommands.TRIP_SCOPED_BASES` / `ORG_SCOPED_BASES` are the authority). Migrated from global
to org scope — the global variants grant NOTHING anymore; inert global rows can be deleted from the Global
Privileges page once verified:

| Base | Grants (scoped to one org) |
|------|----------------------------|
| `peopleAdmin` | People pages + Manage People for that org's people only; family admin ops over them |
| `addTrip` | Creating trips belonging to that org (page and `POST /api/trips` use the same rule) |
| `emailAdmin` | Mail-merge bounded to that org's members + its trips' rosters (`OrgCommands.sendMerge`) |
| `paymentsAdmin` | The payment page's sandbox toggle for that org's trips |

`addTx` was deleted outright (its one button now uses `tripFinAdmin@trip`, like its destination always
did). `privilegeAdmin`, `configAdmin`, `auditAdmin`, `siteDeployer`, `contentAdmin`, `mediaAdmin`, and
`eventAdmin` remain global.

**Granting**: org admins grant/revoke org-scoped privileges to their MEMBERS on the org People page
(grantee need not be an org admin), through `OrgCommands.grantOrgPrivilege`/`revokeOrgPrivilege` — the
server-side enforcement point. Since 2026-08-25 the page uses the trip-editor chip pattern: each member
row shows a chip per held privilege (friendly names — `OrgCommands.heldOrgPrivs`; every chip carries a
remove X because revocation is deliberately not allow-list-checked) plus a "+ Add privilege" overlay
(`addableOrgPrivs`/`addableOrgPrivsFor` — allow-list minus held). Losing the last chip never drops the
row: org membership is explicit, ended only by the Remove link under the member's name, which renders
only when removal can succeed (not an org admin, on none of the org's trips) and confirms first. Org
admin itself is a confirmed checkbox in its own column; `setOrgAdmin` refuses to revoke the org's LAST
admin. Leaving an org revokes its org-scoped privileges (`removeMember`), and a
member on one of the org's trips cannot be removed at all. Trip roles (Editor Admin, Finance Admin,
Finance Viewer, Viewer, Chat Admin, Registration Admin) are granted on `trip/edit.jsf` by site admins OR
the owning org's admins, through `OrgCommands.setTripRole`. Since 2026-08-24 that page's Trip Managers
section is a chip roster: one row per role holder, a chip per held role with a remove control (rendered
only when `setTripRole` would accept the revoke — `OrgCommands.heldTripRoles`' `grantable` flag), a
per-row "+ Add role" chip opening a shared role-picker overlay (`addableTripRoles`/`addableRolesFor`),
and an Add Manager dialog (person autocomplete + role).

**Tenancy on the manager surfaces** (2026-08-24): the Add Manager autocomplete is
`OrgCommands.completeTripManagerCandidates` — the trip's org MEMBERS only, resolved from the page's
pinned `viewScope.theTripId`, and empty for callers who may not manage the trip's roles (the completion
endpoint is POSTable without the dialog, so authorization lives on the data source). Never wire a
people picker on an org-owned page to the global `people.searchPeople`. `setTripRole` enforces the same
boundary server-side: grants require membership in the trip's org (site admins included, the
`grantOrgPrivilege` stance); revokes stay open so a departed member's stale role remains removable.
Org-less trips are site-admin-only and keep the global search.

`registrationAdmin@trip` (display name "Registration Admin") opens the trip's Registrations tab and the
whole `admin/tripRegistrations.jsf` page — approve, move, rooms, approval mail — for non-site-admins
(user decision 2026-08-24: visibility means full page use). The tab gate (`tripTabs.xhtml`), the page's
`reqPriv` door, and the REST roster (`RegistrationsResource.isTripStaff`) all honor it; it deliberately
does NOT light up the other trip tabs, the way an invited chat guest gets exactly the Chat tab.

**Creating a trip** stamps `openToPublic=false` (never public until chosen) and auto-grants the creator
their standard roles on the new trip -- `tripMgr` (Editor Admin), `tripView` (Viewer), and
`registrationAdmin` -- via `OrgCommands.grantCreatorTripRoles`, called only from the create paths (page
save, REST POST). It deliberately skips the org-admin gate (an `addTrip@org` creator is usually not an org
admin) but the org's allow-list still bounds it: withheld roles surface in a warning dialog on the create
page, and a `[support:trip-roles]` notice (no duplicate guard -- one per trip) goes to the support channel
so a site admin can grant them or adjust the allow-list. A create-save then continues to
`/trip/edit.jsf?id=` (or the itinerary when Editor Admin itself was withheld). On that editor, the
settings rows answer to per-trip roles rather than `showAll`: "Show on homepage?" (`tripMgr`), Edit
Registration Options (`registrationAdmin`), Payment Settings (`tripFinAdmin`), "Enable Trip Chat?"
(`chatMgr`) -- the dialogs themselves render only for their gate's holders, which is also the decode-time
refusal for forged submits.

**Trip staff are people** (2026-08-24): `Trip.facilitatorIds`/`directorIds` (lists of `Person.Id`)
supersede the deprecated free-form `facilitators`/`director` strings. The hand-written
`getFacilitators()`/`getDirector()` resolve ids to comma-joined names (legacy string only when no ids),
and are `@JsonIgnore` so the stored legacy string never gets overwritten by resolved names on save. The
registration popup (`joinTrip.xhtml`) renders the facilitators' name/phone/email as its contact block --
no hardcoded tenant contact -- and its OK returns to the trip. No editor UI for the id lists yet
(provisioned via data/JSON by decision).

**Adding members** (org People page): site admins keep the whole-directory autocomplete; org admins add
by EXACT email address instead (`OrgCommands.addMemberByEmail`) — a name typeahead across every account
leaked other tenants' people. An address with no account offers an invite dialog: `sendOrgInvite` mails
the `org-invite` MAIL template (create-account link; From/base-URL from the `reg.mail.*` settings,
Reply-To the org's contact email). Invites are STATELESS by design — nothing is recorded, no membership
is pre-granted; the admin adds the address again once the account exists, and an account that appears
between check and send folds into a plain add.

## Email addresses (the Settings section, 2026-08-24)

Every address the application sends **with** (From) or **to** (recipient / Reply-To) is a
`KnownSettings` slot resolved by `MailAddressCommands` (`#{mailAddr}`). A slot's stored value is either
the sentinel `site` (the Site email setting), the sentinel `org` (the owning organization's contact
email from its org Profile page, **falling back to the Site email**; only slots with a trip in hand),
or a literal address. The admin Settings page renders the "Email addresses" section with a mode widget
instead of raw text; its From composer is display-name + local-part + a domain dropdown fed by
`MailCommands.verifiedSendingDomains()` (live from SES, ten-minute memo, fixed fake list in local
mode; needs `ses:ListIdentities` + `ses:GetIdentityVerificationAttributes` on the task role).

Rules that outlast the UI:

- **From is never `org`**: SES only sends from verified identities, so an arbitrary org domain as From
  would fail every send. From slots resolve `org` as `site` defensively.
- **Recipients/Reply-To take any well-formed address** — an org's contact email is often external.
- Senders resolve through `MailAddressCommands.from(def)` / `.recipient(def, trip)` /
  `.replyTo(def, trip)` (pages: `mailAddr.fromFor/recipientFor/replyToFor` with the setting key) —
  **never read these settings raw**: the value may be a sentinel, not an address.
- Slot registry: `MailAddressCommands.SLOTS`. Adding a sendable address = a `SettingDef` in the
  Email addresses section + one `Slot` entry; the page renders it automatically.

## Sending domains: every From is composed, never typed (2026-08-25)

The Settings section's composer shape is now the **only** way a From address is entered anywhere.
`/WEB-INF/mailFromComposer.xhtml` is that one editor — display name, mailbox, and a **dropdown** of
allowed domains — used by the admin Settings section, the mail merge (`admin/mailMerge.xhtml`), and the
trip Payment Settings dialog (`WEB-INF/tripPaymentDialog.xhtml`). The free-text From boxes those last
two carried are gone: they accepted any domain and the send then failed at SES with nothing on screen
explaining why.

Which domains the dropdown offers is a **per-org allow-list**, `Organization.mailDomains`:

- `null` OR **empty** = never restricted: every SES-verified domain is offered. Unlike
  `grantablePrivileges`, empty is NOT "nothing allowed" — an org that may send from no domain cannot
  send at all, so restriction is only ever expressed by listing the domains that ARE allowed. No
  migration was needed and no existing org changed behavior.
- The list is **site-admin only** (it decides which tenant may send as which domain). It is edited on
  `admin/organizations.jsf`, and rendered-but-disabled on `admin/orgProfile.jsf`; both pages share
  `/WEB-INF/orgProfileFields.xhtml`, and `OrgCommands.saveOrgEdits` silently **ignores** the field for a
  non-site-admin rather than refusing the post — that is what makes one shared include safe for both.
- `Organization.defaultMailDomain` is the org admins' own choice among what they are allowed; it only
  preselects a dropdown, so it is not an authorization control. It is dropped whenever the allow-list
  stops permitting it — a preselected item the dropdown does not offer silently posts back as the first
  option, which is how a From address changes with nobody touching it.
- Effective list = the allow-list narrowed to what SES verifies **right now**
  (`OrgCommands.mailDomains(orgId)` / `.mailDomainsForTrip(trip)`); a domain dropped in SES stops being
  offered even while still listed.

Enforcement is server-side, not just in the widget: `OrgCommands.sendMerge` refuses a From outside
`mergeMailDomains()`, and `applyPaymentFrom` refuses one outside the owning org's list, leaving the
working config untouched rather than half-applied.

**Mail merge seeding.** The org's contact address seeds the **From** only when SES could actually send
as it (`MailAddressCommands.isSendable`). Otherwise — the common case of a parish gmail or an unverified
domain — the Site email seeds the From and the contact address seeds **Reply-To** instead, so replies
still reach the org. A site admin gets no org seeding at all (`mailingOrg()` returns null for them
deliberately: holding emailAdmin everywhere would otherwise make one arbitrary tenant stand in for the
whole site).

## The per-org allow-list

`Organization.grantablePrivileges` bounds what an org may grant (both trip roles and org-scoped bases):

- `null` (never set) = **everything allowed** — existing orgs needed no migration; restriction is an
  explicit site-admin act on `admin/organizations.jsf` ("Grantable Privileges" fieldset).
- An EMPTY list = nothing grantable. The getter deliberately never lazy-inits (unlike `paymentDefaults`),
  because that distinction is load-bearing.
- Enforced server-side in `grantOrgPrivilege` and `setTripRole` and reflected in the same
  `grantableOrgPrivileges`/`grantableTripBases` lists the pages render from, so UI and enforcement cannot
  drift. Site admins bypass the allow-list; revocation is never allow-list-checked (stale grants must stay
  removable).

## The privilege editors

`admin/editPrivs.jsf` (Global) / `editTripPrivs.jsf` / `editOrgPrivs.jsf` — three structurally identical
pages (shared `WEB-INF/privEditor.xhtml`, nav via `WEB-INF/privTabs.xhtml`), all site-admin/privilegeAdmin
gated. Names come from a canonical dropdown per scope kind (+ an "Other…" escape for content-container
`editorPrivileges` names); picking a canonical name pre-fills its one-line description
(`PrivilegeCommands.baseDescription`, also defaulted at save when left blank); a picked row's name+scope
are locked (renaming used to orphan rows silently); **Delete** hard-deletes a row, auditing its holder
list.

## REST changes (deliberate breaks)

- `POST /api/trips` requires `orgId` in the body; authorized by site admin / org admin / `addTrip@org`.
- `POST /api/people` requires `?org=` for non-site-admins holding `peopleAdmin@org`; the new person is
  membered into that org. People search results are bounded per-subject (`canAdminPerson`), so counts can
  shrink for pre-migration clients.
- `AuthResource` no longer reports `peopleAdmin`/`emailAdmin`/`addTrip` flags (absent reads as false).

## Organization sites (subdomains, 2026-09)

An org can have its **own site** at `{slug}.unitetrip.com` (service tier 1 of three: subdomain, custom
domain (later), or listed on a shared site as today). Everything about it is an ONLINE admin act — no
script, no DNS step, no deploy (wildcard DNS and the wildcard certificate already cover every label):

- `Organization.slug` — site-admin only (a public namespace grant, like the sending-domain allow-list);
  edited on the shared profile include. `OrgCommands.saveOrgEdits(..., slug)` validates the DNS-label
  grammar, uniqueness, and the `RESERVED_SLUGS` list; blank clears it (the site goes offline, data and
  content kept). `DAO.saveOrganization` refreshes the `SiteIndex`, so the host serves within the request.
- **The starter home page**: the first assignment also calls `ensureHomePage`, which seeds the org's
  `page:org:{id}:home` once (`Organization.homePageSeededAt` records it) — see
  `content-templates.md`, "Organization sites". The org dashboard (`admin/orgSettings.xhtml`) calls it too,
  so an org slugged before seeding existed gets its page on its managers' next visit; for anyone else it is
  a no-op. The dashboard's "Organization Site" card links to the live site (`SiteCommands.orgSiteUrl`, which
  answers `http://{slug}.localhost:{port}/` when the admin is browsing on localhost).
- **What the site shows** is decided by the request's `SiteContext` (`#{site}`), never by the session: the
  org's page key, its name as the page title, only its own public trips, albums, documents and Trips-menu
  entries (`TripCommands.getMenuTrips/getMenuOldTrips`, seeded by `template.xhtml`), and only that org in
  the admin menu's org list (`visibleOrgs`). Editing happens on the org's site, in the same edit mode as
  the shared page; the org-scoped `contentAdmin@{org}` grant is a later phase — until then site admins /
  global `contentAdmin` edit org pages.
- **Shared sites and the org's content — the double gate** (`site/ListingScope`, see
  `content-templates.md` "Organization sites"): a shared site's sections list a hosted org only when the
  section's `includeOrgs` pick includes it AND the org has not unchecked "Shared sites" on its profile
  (`Organization.allowSharedSites`, org-admin controlled, saved through `saveOrgEdits`' 8th argument). With
  no pick, a shared site lists the orgs that have no site of their own — so assigning a subdomain also
  takes the org OFF the shared sites until a site admin curates it back in.
- **Media is site-scoped by `MediaItem.orgId`**: the admin library (`admin/media.xhtml`), the document
  picker and the Documents slot show an org's items only on its own site and the site's items only on
  shared sites; `GET /api/media/slots/{slot}` refuses a chat album (404) unless the caller is on the trip or
  the trip is publicly listed on that site.
- **Local fixtures**: CFPW has NO slug (shared-tier, as in production); Acme (`acme.localhost`) and Beta
  Corp (`beta.localhost`, `FakeData.BETA_ORG_ID`) are the two hosted orgs; `fake-acme-doc` is Acme's
  library document. `OwnedFixturePolicyIT` ratchets which webtests may reference the seeded orgs.

## Per-org settings (the settings ladder, 2026-09)

The payment-config ladder (`payments.md`) generalized: a runtime setting can be **org-overridable**, and an
organization's own value then wins on its own site. Three rungs, resolved in order:

1. the org's override — `Organization.settingsOverrides` (`Map<String,String>`, setter-populated, never null
   via the getter; a blank or absent entry means *inherit*);
2. the site's stored row in the `config` table (the admin Settings page, untouched — the table is NOT
   re-keyed per org);
3. the compiled default on the `SettingDef`.

Which settings: those `KnownSettings` marks with `.withOrgOverride()` — `site.org.name`,
`home.photos.windowDays`, `home.photos.minCount`, `home.countdown.soonDays`, `reg.allowEdits`,
`chat.background.colors`, `chat.background.image`, `chat.reactions.palette` — plus `site.analytics.id`,
which is **org-explicit** (`.withOrgOnly()`): an org host resolves it from the org's override or the
compiled default (blank) and *never* from the site's row, so the shared site's analytics property can never
collect an org site's traffic. `KnownSettings.orgOverridable()` is the authoritative list; marking a new
setting is a product decision (`OrgSettingsLadderTest` pins the set).

Resolution is automatic for request-bound code: `ConfigCommands.getString/getInt/getBoolean/getLong(def)` —
and the page entry points `#{config.getString('key')}` etc. — consult `SiteContext.current()`, and on an ORG
host walk the ladder for an org-overridable def. Every other host, every other setting, and every read with
no bound request take exactly the pre-ladder path (the `(name, default)` overloads), so nothing shared
changed. Background code (`RequestContext.system()`: the digest and notification senders, schedulers) has
no host and must name the organization explicitly — `ConfigCommands.getString(def, org)` or
`OrgCommands.effectiveSetting(def, orgId)` — deriving it from the entity in hand (trip → org), never from
a session. `ConfigCommands.siteString(def)` is the site rung alone, whatever host the request is on.

The editor: `admin/orgConfig.jsf?orgId=…` (hub card + "Settings" tab; `canManageOrg`) lists every
org-overridable setting with the site's value as its placeholder ("Inherit (…)" for booleans), blank =
inherit. `OrgCommands.saveOrgSettings(orgId, map)` refuses non-overridable keys and values that do not
parse as the declared type, applies onto a `Cached.NO` read, writes only when something changed, and
audits the change list. Rows bind by setting NAME into a viewScope map (names and scalars only), like the
site Settings page.

**Derived base URLs.** `reg.mail.baseUrl` and `chat.mail.baseUrl` are the *shared-site* rung only. Every
absolute link about an org — registration mail (`RegistrationCommands`), org invites (`sendOrgInvite`),
chat mention/reply/photo mail (`EmailChatNotifier`), the daily digest (`ChatDigestSender`), chat invite
links (`ChatCommands.inviteUrl`) — goes through `site/SiteUrls`: `https://{slug}.{site.orgsites.baseDomain}`
when the org has a subdomain, else the setting's value; the org always comes from the trip/channel/org in
hand, never from the host the sender happened to be on (the senders run under the system context, which
has none). Support-request mail stays on the site URL: its readers are the site's support admins.
`PaymentsResource` accepts a return URL on any org site the live `SiteIndex` knows (`https://` only, whole
host, `RedirectAllowlist.allowsOrgSite`) in addition to `payment.returnUrl.allowedPrefixes`, so a payment
started on an org site returns there without a site admin listing every tenant.

**Mail addresses without a trip.** `MailAddressCommands.orgRecipient/orgReplyTo(def, org)` resolve the
`org` sentinel straight from an `Organization` (distinct names, not overloads: `recipient(def, null)`
callers exist and EL picks overloads by runtime type). From is still never `org`.

**Org-invite copy.** The `org-invite` MAIL starter no longer names a host: `{{siteName}}` (the org's own
name for a subdomain org, else the site's `site.org.name`) and `{{siteHost}}` (e.g. `acme.unitetrip.com`)
are filled by `sendOrgInvite`. Installed template ROWS are runtime-editable and are *not* rewritten by
"Install starter templates" (it only creates missing ones), so a deployment that installed the older copy
must delete its `org-invite` template on the Templates page and re-install, or edit the row's subject/body
to use the tokens.

### One login across the org sites (shared cookie, 2026-09)

One Person, one login: a session established on any host under the org-site base domain
(`unitetrip.com`, `www.unitetrip.com`, every `{slug}.unitetrip.com`) is the same session on all of them,
because the session cookie carries `Domain=unitetrip.com` there. The shared hosts
(`visitqueenofpeace.com`, `centerforpeacewest.com`, `localhost`) keep host-only cookies, and a login on
one of them never reaches the org sites (cross-registrable-domain SSO is deferred).

- **The domain is stamped by the container, not the application.** Tomcat writes the `JSESSIONID`
  `Set-Cookie` below any servlet-level wrapper, and a static `sessionCookieDomain` would stamp the org
  domain on vqop responses too (browsers reject a Domain that does not cover the host). So the private
  repo ships a per-context `CookieProcessor` (`medjugorje/tomcat-ext`, `SiteCookieProcessor`, a jar in
  `tomcat/lib`, named in `conf/context.xml`) whose `generateHeader(cookie, request)` widens EVERY cookie —
  the session cookie, `trip_remember`, and their max-age-0 deletions — when `request.getServerName()` is
  the base domain or under it. Application code never sets a cookie domain; the webtest harness installs
  the same processor on its embedded context.
- **Logout signs out everywhere.** `Sessions.logout(request, response)` invalidates the session and
  expires the cookie (widened by the processor on an org host); on org hosts it also emits raw host-only
  deletions of `JSESSIONID` and `trip_remember` for browsers still holding pre-SSO host-only copies
  (Tomcat serves whichever presented session id is still valid, so a stale host-only cookie could otherwise
  keep a signed-out browser signed in). `RememberMeService.revoke` deletes the row of EVERY presented
  remember-me cookie for the same reason. Both `PassCommands.logout` and `POST /api/auth/logout` funnel
  through it. At login a leftover host-only cookie is harmless: an invalid id is skipped by Tomcat.
- **Org context never comes from the session.** One session now serves several sites; the site is
  `SiteContext.current()` (the request host), full stop. Passkeys already span the subdomains
  (`PasskeyService.rpIdFor` collapses to the registrable domain).

### Membership from the org sites (self-join, 2026-09)

Exactly two events make a person a member of an org on their own, both on `OrgCommands` and both audited
with their reason; nothing else does — **browsing an org's site never joins**, however long, however
signed in:

- **Sign-up on an org site** — `joinSiteOrgOnSignup()`, called by `account/createAccount.xhtml` right
  after `createCredsSession` established the new account's session: the signed-in caller joins the site's
  org (`SiteContext.current()`; a no-op on shared/marketing hosts). It takes no org id, so no page can be
  talked into joining an arbitrary org.
- **Registration for an org's trip** — `joinOnRegistration(trip, travelerId)`, called by
  `RegistrationCommands.registerParty` for each traveler whose row was just saved (a refused registration
  joins nobody; a family manager's registrations join each traveler). Org-less trips are a no-op.

Invite acceptance already produces a membership through the org People page's add path.

## Local-mode fixtures (`FakeData`)

Acme trip `3f7a9c15…` (Kevin on the roster — demonstrates the removal guard), `emailAdmin@CFPW` → user2,
`peopleAdmin@CFPW` → user4, and Acme's allow-list excludes `paymentsAdmin` (Kevin cannot grant it).
