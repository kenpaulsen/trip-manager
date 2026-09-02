# The Org Admin Area (org migration, 2026-08)

Organizations are the tenancy boundary; this doc covers the self-service surface an org's own staff use and
the privilege model behind it. Payments-side org details (processor configs, the payment-config ladder) are
in `payments.md`; family-admin gating is in `family-accounts.md`.

## The hub and its pages

`admin/orgSettings.jsf?orgId=…` is a dashboard of cards and the org area's **only** navigation (user
decision 2026-09-02: cards, not tabs — `WEB-INF/orgTabs.xhtml` is deleted). Every sub-page carries one
control back: a **Done** `p:linkButton` (`id="orgDone"`, a plain GET) top-right of its heading. All of them
follow the self-gating pattern — org authority has no role or privilege row, so these pages gate themselves
in `initPage`, NOT via defaultAuth:

| Page | View gate (`OrgCommands`) | Mutations |
|------|---------------------------|-----------|
| `orgSettings.jsf` (hub) | `canViewOrgHub` — admin OR any org-scoped privilege here | — (cards render per-permission) |
| `orgProfile.jsf` | `canManageOrg` | `saveOrgEdits`, **the name included** |
| `orgTrips.jsf` | `canViewOrgTrips` — admin OR `addTrip@org` | New Trip: `canCreateTripFor` |
| `orgPeople.jsf` | `canViewOrgPeople` — admin OR `peopleAdmin@org` | **org admins only** (all of them) |
| `orgProcessors.jsf` | `canManageOrg` | `canManageOrg` |
| `orgConfig.jsf` | `canManageOrg` | `saveOrgSettings` (see "Per-org settings") |

The hub's cards are Profile, Trips, People, Payment Processors, Appearance
(`orgAppearance.jsf` — the Branding settings), Settings (`orgConfig.jsf` — everything else), Content
Templates (`templates.jsf?orgId=` — org admins OR whoever `priv.checkHere('contentAdmin', …)` admits on
this host), and, for site admins only, Privileges. There is no "Organization Site" card: the site's own
address is a link on the **profile**, beside the subdomain field that decides it.

**Save Profile returns to the hub.** `saveOrgEdits` answers a boolean; on true the page redirects to
`orgSettings.jsf?orgId=…&info=Organization profile saved.` (a growl carried as a URL message, the
`account/person.xhtml` idiom), on false it stays put with the typed values and the refusal's own growl.

**A page's multi-select value goes through `OrgCommands.asStringList`, never `util.asList`.** A rendered
`selectManyCheckbox` decodes to an `Object[]`, but a **disabled** one never decodes at all, so its binding
is still the `List` that `initPage` seeded — which is exactly what an org admin's Save Profile posts, the
sending-domain allow-list being site-admin-only. `util.asList` declares one `Object[]` parameter, and the EL
`MethodNotFoundException` aborted the whole ajax command handler: the 2026-09-02 report of an Abbreviation
edit that saved silently with no effect and no error was every org admin's profile save, dead.

The menu's per-org entries come from `org.menuOrgs()` — `visibleOrgs()` (admins ∪ holders of any org-scoped
privilege), minus a SITE admin on a shared host, who would get an entry per tenant and uses the
Organizations page instead. They are labelled `Manage {name}` (the org's full name) and are the FIRST items
of the Admin submenu, inserted before the static ones by `jsftComp.insertUIComponentBefore` because
`addUIComponent` can only append. On an organization's own site `visibleOrgs()` is that org alone, site
admins included, so managing Acme from `acme.unitetrip.com` leads that menu with "Manage Acme Inc".

The Admin menu's **Content Templates** entry is the SITE-WIDE manager and is gated on the GLOBAL grant
(`priv.check('contentAdmin', null, …)`) plus `showAll`; an org's own templates are reached from its hub
card. The separate "Email Templates" entry is gone — it was the same page with `?kind=MAIL`.

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
the `org-invite` MAIL template (From/base-URL from the `reg.mail.*` settings, Reply-To the org's contact
email). Invites are STATELESS by design — nothing is recorded, no membership is pre-granted; the admin
adds the address again once the account exists, and an account that appears between check and send
folds into a plain add.

**The invite link lands on the login page** (2026-09-01, `OrgCommands.inviteLoginUrl`):
`{siteUrl}/account/login.jsf?email={invitee}` — the org's own site when it has one. The old link went
straight to `createAccount.jsf`, which was wrong the moment an account existed by the time the mail was
opened. `login.xhtml` reads `?email=` into `requestScope.loginEmail` (a query-param read on a GET — no
session until Next posts, the issue-19 rule), pre-fills the field, and Next decides: an existing account
goes to the password/code/passkey step, an unknown address continues to Create Account with the email
carried over (`sessionScope.loginEmail`, the first session write of the flow). Because the visitor is on
the org's site, the sign-up self-join below still applies. The template token keeps its
`createAccountUrl` name, so installed rows need no re-install; the starter's link text is now "Sign in or
create your account" (an installed row keeps its old wording until edited).

**Nobody creates a person from an admin page.** The People Manager's "New Person" button is gone
(2026-09-01): a person comes into being by signing up — where they read the privacy and legal text
themselves — by a family manager's add-member flow, or through `POST /api/people`. The profile editor
(`account/person.xhtml`) saves through `PersonCommands.saveProfile`, which refuses a person no row
exists for: `?id=<unknown>` used to hand an admin a blank Person under a fresh id whose Save created a
junk row.

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
  a no-op. The live site's address is a link on the org PROFILE, beside the subdomain field
  (`SiteCommands.orgSiteUrl`, which answers `http://{slug}.localhost:{port}/` when the admin is browsing on
  localhost); the hub card that used to carry it is gone (2026-09-02).
- **What the site shows** is decided by the request's `SiteContext` (`#{site}`), never by the session: the
  org's page key, its name as the page title, only its own public trips, albums, documents and Trips-menu
  entries (`TripCommands.getMenuTrips/getMenuOldTrips`, seeded by `template.xhtml`), and only that org in
  the admin menu's org list (`visibleOrgs`). Editing happens on the org's site, in the same edit mode as
  the shared page; the org-scoped `contentAdmin@{org}` grant is a later phase — until then site admins /
  global `contentAdmin` edit org pages.
- **A shared site's MENUS** (the Trips menu, `TripCommands.getMenuTrips/getMenuOldTrips`) name only the
  orgs that SHARE the site: a hosted org's trips never appear there — not for their members, not for a
  site admin, who sees them on the org's own host. The site admin's topbar org selector
  (`sessionScope.currentOrgId`, offered only on shared sites and only over `OrgCommands.switchableOrgs()`
  = orgs without a site of their own) narrows the menus to one sharing org. The page's SECTIONS are
  curated separately (below).
- **Shared sites and the org's content — the double gate** (`site/ListingScope`, see
  `content-templates.md` "Organization sites"): a shared site's sections list a hosted org only when the
  section's `includeOrgs` pick includes it AND the org has not unchecked "Shared sites" on its profile
  (`Organization.allowSharedSites`, org-admin controlled, saved through `saveOrgEdits`' 8th argument). With
  no pick, a shared site lists the orgs that have no site of their own — so assigning a subdomain also
  takes the org OFF the shared sites until a site admin curates it back in.
- **What a site can reach (2026-09-01).** A trip's PAGES — contacts, itinerary, details, registration, chat,
  its ledger rows and payments — are served by a site only if that site LISTS the trip's organization:
  `ListingScope.forSite().reaches(orgId)` = `SiteContext.admits(orgId) && ListingScope.shows(orgId)`, with
  one extension for pages reached by link rather than by listing: on a shared site a hosted org is also
  reachable while any section of that site's home page curates it (`includeOrgs`, top-level or a
  container's children; `ListingScope.curatedOnPageOf`), the org still allowing shared sites. So an org
  site reaches only its own trips; the shared sites reach their sharing tenants, org-less legacy trips, and a
  hosted org only while curated in. What a site does not reach behaves exactly like something that does not
  exist: `TripCommands.getTrip`/`getTripForEdit` answer the blank trip (the page's `title == null` /
  `id.equals` proof fails as for a bad id), `BaseResource.findTrip` and every REST route through it 404,
  `ChatCommands.canParticipate`/`readDenial` refuse (`NOT_A_TRIP_MEMBER`, page, feed and long poll alike),
  and every trip LIST on `TripCommands` (`getTripsForUser`, `getRecentTrips`, `getActiveTrips`,
  `getInactiveTrips`, `recentTripsFor`) and `OrgCommands.mailableTrips` is narrowed, so pickers never offer
  what the page would then answer blank. The "current trip" behind `/account.jsf` → `tripContacts.jsf`
  (`getTripForUser`) therefore picks among the trips the site lists (on an org host, that org's), and the
  continue-registration banner hides for a trip the host does not serve. **Off a bound request** —
  `RequestContext.system()`: the digest and notification senders, schedulers, unit tests —
  `SiteContext.isBound()` is false and everything is reachable: a tenant boundary is a property of a HOST,
  and that code takes its organization from the entity in hand. The money side is in `payments.md`,
  "Money is site-scoped on read". Enforced by `SiteReachTest` and `OrgSubdomainPwIT`.

  *Site admins and a hosted org's admin pages.* The org-admin pages are org-keyed and org-gated, not
  site-gated: `admin/orgTrips.jsf?orgId=` still lists a hosted org's trips on the shared host (a site admin
  opens it from the Organizations page there), but its trip links and New Trip go to the host that serves
  them (`SiteCommands.hostFor(orgId)`: empty when this host reaches the org, else
  `https://{slug}.{base}`), with a note saying the trips are managed on the org's own site, and
  `admin/editTrip.jsf`'s post-save redirects (to the trip editor / itinerary) carry the same prefix, so a
  trip created for a hosted org from the shared host continues on the org's site (`TripCreatorRolesPwIT`
  drives the create round trip on acme's own host for that reason). The rule is
  deliberately not punched for site admins: a hosted org's trips are edited, mailed, reconciled and paid on
  the org's own host (Phase 5 verified the org-admin pages there); a site admin following such a link signs
  in again on the org host (the shared hosts' cookies are host-only, see "One login across the org sites").
- **Media is site-scoped by `MediaItem.orgId`**, for discovery AND for writes: the admin library
  (`admin/media.xhtml`), the document picker and the Documents slot show an org's items only on its own site
  and the site's items only on shared sites, and a row can be changed only from the site where it shows;
  `GET /api/media/slots/{slot}` refuses a chat album (404) unless the caller is on the trip or the trip is
  publicly listed on that site. An org's uploads are stored under `org/{orgUUID}/…`, so two orgs' (or an
  org's and the shared site's) `background.jpg` are different objects -- the key layout and the write rule
  are under "Media" in "Org-site editors" below.
- **Local fixtures**: CFPW has NO slug (shared-tier, as in production); Acme (`acme.localhost`) and Beta
  Corp (`beta.localhost`, `FakeData.BETA_ORG_ID`) are the two hosted orgs; `fake-acme-doc` is Acme's
  library document; Matt (user6) is Acme's SITE editor (`contentAdmin`+`mediaAdmin` scoped to Acme) and
  Kevin (user3) its org admin WITHOUT the grant. `OwnedFixturePolicyIT` ratchets which webtests may
  reference the seeded orgs.

## Org-site editors: `contentAdmin@org` / `mediaAdmin@org` (privilege-only, 2026-09)

Editing an organization's site is a PRIVILEGE, never a consequence of being its admin (user decision).
`contentAdmin` and `mediaAdmin` are now in `ORG_SCOPED_BASES` as well as `GLOBAL_BASES`: the global row
edits every site, the org-scoped row (stored id = base + the org UUID, granted on the org People page
like any other org grant and bounded by the org's allow-list) edits that ONE org's own site:

- **Content** (`ContentCommands.canEdit/mayEdit`): the org of a section is derived from the SECTION —
  `OrgPageBootstrap.orgOf(pageKey)`, or for a container's children the container row's page — never
  from the request's host, so a forged section cannot become editable and an org grant provably never
  reaches `page:trip-index`, `page:unitetrip-home` or another org's page. Container config fields
  (editor-privilege chips, child allow-lists) stay global-`contentAdmin`-only, and the chips themselves
  are global privilege names (an org-scoped chip is a deliberate non-feature for now).
- **Templates** (`TemplateCommands.mayAuthor`): an org editor authors templates scoped to their org only,
  may never re-scope a stored template into or out of it (seizure), sees shared + own templates in the
  manager, and gets only their org in the Scope menu; starter installation stays site-staff-only.
  Scoping this grant scopes the blast radius of template HTML (script access) to the org's own site.
  **Shared templates are copied, not edited** (2026-09-01): on the manager page Edit / History / Delete
  render only for a row the caller may author (`contentTemplate.mayAuthor(t)`), so an org editor gets no
  affordance whose save could only fail; a SITE DEFAULT in an organization's scope offers **"Customize"**
  instead (`mayCustomize(t, orgId)` / `customize(id, orgId)`), which clones it into the org's scope as
  `{id}-{slug}` (`{id}-{orgUUID}` for an org with no subdomain), same kind/body/placeholders/container
  settings, version 1, audited as a template write, and opens the clone for editing -- the org customizes
  its copy, the shared original is untouched. Authorized by `mayAuthor` on the RESULT's scope (so Kevin, an
  org admin without the grant, cannot copy), refused with no org scope, for a non-shared source (another
  tenant's template is never a source), and when the copy already exists. Site staff get Customize
  alongside Edit. The scope is the page's `?orgId=` (the org hub's Templates card) or the org whose site
  the request is on -- not the host: the page itself is gated by `priv.checkHere`, which on a shared host
  counts an org-scoped grant for nothing, so the privilege is the boundary either way.
  **EMAIL templates are customized too** (2026-09-02, user decision -- this reverses the 2026-09-01 rule
  that email copy stays a site-staff edit): every sender now resolves per organization
  (`TemplateCommands.resolveForOrg`, `MailCommands.sendManagedTemplateForOrg`), so an org's copy of
  `registration-received` is the mail its registrants actually get. The manager shows ONE row per use case
  in an org scope -- the site default with **Customize**, or the org's own row badged **Customized** with
  **View site default** and **Revert to site default** -- and the site-wide page (`/admin/templates.jsf`
  with no `orgId`) hides org-owned rows and edits the site defaults only. A MAIL copy's name gets NO
  "({org})" suffix: a MAIL template's name is its subject line. Full rules, including the resolution order
  and which entity each sender takes its org from: `content-templates.md` "Per-organization email copy".
- **Media** (`MediaCommands.mayManage/mayUploadHere`, resolved from the request-bound `Caller.bound()`
  so pages, the REST API and the upload servlet answer alike): every write re-checks OWNERSHIP of the
  item (global → any; org editor → the org's items; a trip's manager → its chat album), uploads are
  allowed on the site whose org the editor holds, and `admin/media.xhtml` / `mediaItem.xhtml` /
  `templates.xhtml` self-gate with `priv.checkHere(base, userId)` (site admin, global holder, or the
  holder for THIS site's org) instead of the one-privilege `defaultAuth`.

  **Key layout (2026-09-01).** An upload made on an organization's host -- the admin page's typed path, the
  Documents dialog's XHR (`/media-upload`), or the API's `upload-url`/confirm pair -- is stored under
  `org/{orgUUID}/{whatever was asked for}` (`MediaCommands.siteKey`; the UUID because a slug can be
  reassigned). The shared site's keys are exactly what they always were (`downloads/...`), so nothing
  moved and no URL changed; the row id is a fresh UUID either way, never derived from the key. The
  namespaces are disjoint by construction: Acme's `background.jpg` is `org/{acme}/background.jpg`, Beta's
  is `org/{beta}/background.jpg`, the shared site's is `background.jpg`, and none can be typed into
  existence from another host -- `org/...` counts as a reserved prefix (with `profilePics/`, `chat/`,
  `badgeImages/`) everywhere except under the site's own org, on the page path as well as the API's. Rows
  that predate the layout (an org's first uploads, stored bare) keep their keys; they are still the org's
  by `orgId`, and a rename from the org's host moves them into its namespace.

  **The write rule.** Two halves, both re-checked by every write (`upload`, `confirmUpload`, `update`
  incl. rename, `delete`, `assignToSlot`, `setHidden`, `getManageable` for the edit page, the servlet, and
  the REST resource, which 404s a row it may not touch): (1) the SITE half, `writableHere` -- a row is
  writable only from the site where it is discoverable, an org's rows on that org's host and the shared
  site's rows on a shared host, **whoever asks, site admins included** (a site admin manages Acme's
  library on `acme.unitetrip.com`, where `checkHere` admits them, and never from `visitqueenofpeace.com`;
  a foreign org's row is never writable from any other host); chat albums are the exception, moderated
  from their trip's pages wherever those are served. (2) the CALLER half -- global `mediaAdmin`/site admin,
  or the org-scoped editor for the row's org, or the trip manager for a chat album. Uploading over a key
  that a row already claims is allowed only when that row passes both halves (re-uploading your own file is
  the page's replace path); otherwise the upload is refused before any byte is written, which is what
  protects the pre-layout bare keys. A rename never lands on another row's key at all.

  **The plain answer** to "does Acme's `background.jpg` collide with anyone else's?": no. It is stored as
  `org/{acme-uuid}/background.jpg`, a different object from every other site's, listed and editable on
  Acme's host only, and neither the shared site nor another org can write, overwrite, rename or delete it
  from theirs. Its public URL is still URL-referenceable by anyone (locked decision: only discovery and
  writes are scoped).
- The org DASHBOARD stays with the operational grants (`ORG_HUB_BASES`): editors work on the site itself.
- Menu entries (Media, Content/Email Templates) and the landing page's upload/edit affordances use
  `priv.checkHere`, so an editor sees them on their org's host only.

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
collect an org site's traffic — and the ten org-only **Branding** defs (`site.theme.palette`,
`site.logo.url`, …; see "Branding" below). `KnownSettings.orgOverridable()` is the authoritative list;
marking a new setting is a product decision (`OrgSettingsLadderTest` pins the set).

Resolution is automatic for request-bound code: `ConfigCommands.getString/getInt/getBoolean/getLong(def)` —
and the page entry points `#{config.getString('key')}` etc. — consult `SiteContext.current()`, and on an ORG
host walk the ladder for an org-overridable def. Every other host, every other setting, and every read with
no bound request take exactly the pre-ladder path (the `(name, default)` overloads), so nothing shared
changed. Background code (`RequestContext.system()`: the digest and notification senders, schedulers) has
no host and must name the organization explicitly — `ConfigCommands.getString(def, org)` or
`OrgCommands.effectiveSetting(def, orgId)` — deriving it from the entity in hand (trip → org), never from
a session. `ConfigCommands.siteString(def)` is the site rung alone, whatever host the request is on.

The editor: `admin/orgConfig.jsf?orgId=…` (hub card "Settings"; `canManageOrg`) lists every org-overridable
setting with the site's value as its placeholder ("Inherit (…)" for booleans), blank = inherit — **minus the
look-and-feel ones**, which `admin/orgAppearance.jsf` owns (see "Branding"). The two lists are one split
(`KnownSettings.branding()` / `.orgOverridableNonBranding()`, offered to the pages as
`#{config.orgConfigDefs}`), so no setting can appear on both pages or on neither.
`OrgCommands.saveOrgSettings(orgId, map)` refuses non-overridable keys and values that do not
parse as the declared type, applies onto a `Cached.NO` read, writes only when something changed, and
audits the change list. Rows bind by setting NAME into a viewScope map (names and scalars only), like the
site Settings page. Both pages end with a "Done" link back to the org dashboard.

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
are filled by `sendOrgInvite`, and `{{createAccountUrl}}` is the site's login page with the address
pre-filled (name kept for compatibility). Installed template ROWS are runtime-editable and are *not*
rewritten by "Install starter templates" (it only creates missing ones), so a deployment that installed
the older copy must delete its `org-invite` template on the Templates page and re-install, or edit the
row's subject/body to use the tokens; a row installed with the "Create your account" link text keeps
working unchanged (the token's VALUE changed, not its name).

### Branding (org-site look, 2026-09)

An org site's look comes from the org's OWN settings and from nowhere else (user-locked): the shared site's
logo, footer and contact details are literals in the sibling repo's XHTML and stay there, and an org host
**never inherits them** — every branding def is `.withOrgOnly()`, so on an org host it resolves to the org's
override or, when blank, to the NEUTRAL platform default (never the `config` table's site row, which the
Branding section on the site Settings page can still write but nothing applies). The defs live in
`KnownSettings`' **Branding** section (`BRANDING_SECTION`, right after Site), all `STRING` and default `""`
apart from the one `BOOLEAN`:

| Setting | Blank means | Notes |
|---------|-------------|-------|
| `site.theme.palette` | the platform's default look (`freya-medj-l/d`, `layout-light/dark`) | a MENU: `avocado, blue, green, orange, purple, red, turquoise, yellow` (`THEME_PALETTES`); the Freya build ships `freya-{palette}-{light|dark}` + `layout-{palette}-{light|dark}.css` for exactly these |
| `site.theme.dark` | light (`BOOLEAN`, default `false`) | the org's own light/dark choice; a VISITOR who has used the topbar's Dark Mode toggle still overrides it for themselves — see the precedence below |
| `site.logo.url` | the org's name as a text wordmark | http(s) URL |
| `site.favicon.url` | no icon: the page emits `href="data:,"` so the browser fetches no `/favicon.ico` | http(s) URL |
| `site.ogImage.url` | the logo (no logo either: no preview picture) | http(s) URL |
| `site.background.url` | no image, so the background COLOR shows | http(s) URL |
| `site.background.color` | **follow the palette**: `var(--surface-ground, #333333)` | `#rgb`/`#rrggbb`, validated by `SettingDef.hexColor` |
| `site.footer.title` | the org's name | plain text |
| `site.footer.text` | nothing | plain text |
| `site.contact.name`, `site.contact.phone` | left off the "Questions?" card | the card's email is the org profile's `contactEmail`; the card is hidden when all three are blank |
| `site.donate.url` | no Donate card / menu entry | http(s) URL |

**Dark mode has a precedence, not an owner** (`BrandCommands.isDark`): the visitor's OWN choice when they
have made one (the topbar's Dark Mode toggle stores `sessionScope.dark`, read through `getSession(false)`
so no session is ever created, and `false` counts as a choice), else the organization's `site.theme.dark`
on its own site or in its Appearance preview, else light. Everything derived from it — the theme name, the
Freya layout stylesheet, and `template.xhtml`'s `layout-topbar-{light|dark}` / `layout-menu-{light|dark}`
classes — reads that one answer, so an org's choice moves all of them at once.

Three `SettingDef` markings carry the rules: `.withChoices(...)` (an ordered, immutable list the settings
pages render as a menu; `hasChoices()`/`allows(value)`), `.withHttpUrl()` and `.withHexColor()`. **One judge
for all save paths**: `ConfigCommands.rejection(config)` checks the declared type, then — for a DECLARED
key — the choices, the URL rule (`ContentRenderer.requireHttpUrl`, the same check content placeholders
use) and the hex-colour rule (`SettingDef.hexColor`, which is also what `BrandCommands` re-screens with
before the value reaches a `style` attribute); `ConfigCommands.save` (site page) and
`OrgCommands.applyOverride` (org editor, so the Appearance page too) both ask it, and blank is always
"unset". Choices are matched exactly (a palette is a stylesheet path).

**The page background: the palette, a colour, or an image — one of the three.** An image covers the page, so
a colour under one is a setting that silently does nothing (the rule chat backgrounds already follow). The
image wins wherever both are somehow set, the Appearance page offers the three as a mutually exclusive
choice showing only the chosen control, and Save CLEARS the settings the choice does not use
(`BrandCommands.forSave`), so what is stored is what shows. With neither set — an organization that has
configured nothing at all — an org host renders
`--site-bg:none;--site-bg-color:var(--surface-ground, #333333)`: **the palette's own ground colour** and no
image, never the shared site's rainbow photograph, which is a picture of somebody else's place. Every
shipped palette declares `--surface-ground` in its `:root` (light `#F2F4F6`, dark `#3E4754`), so the page
behind the cards tracks both the palette and the dark-mode choice by itself; the literal inside the `var()`
covers a theme that somehow declares none. A `var()` nested in a custom property's value is substituted
where the property is DECLARED — the `<html>` element, which carries the theme's `:root` block too — so
`resources/css/site.css`'s own `background-color: var(--site-bg-color, transparent)` (alongside
`background-image: var(--site-bg, url(rainbow))`) sees a plain colour, and a shared host — which sets
neither property — computes exactly what it always did.

Blank is the stored form of "follow the palette", and a colour picker can never hold a blank, so the
chooser has a third radio (`BrandCommands.BG_MODE_PALETTE`, the default) rather than an empty picker: that
is how a blank round-trips through the page.

The pages read everything through **`#{brand}` (`action/BrandCommands`)**, modeled on `SiteCommands`: it
reads only the request's `SiteContext` plus the org row, never session/view scope (the visitor's own dark
flag is the one session read — via `getSession(false)`, so no session is ever created for a visitor). Off
an org host (shared, marketing, no bound request) EVERY getter answers the neutral/empty
value — `getTheme()` = `freya-medj-l`/`-d`, `getLayoutCss()` = `layout-light`/`-dark`, everything else
null/false — and the templates branch on `#{site.orgSite}` to keep their literal chrome; the bean changes
no byte of a shared page. On an org host: `getTheme()`/`getLayoutCss()`/`isDark()`, `getLogoUrl()` /
`getWordmark()` (mutually exclusive), `getFaviconHref()` (`data:,` when unset), `getOgImage()`,
`getRootStyle()` (`--site-bg:url(<url>)`, or `--site-bg:none;--site-bg-color:<hex or the palette var>`, for
the root element's `style`), `getDetailFields()` (the branding settings the Appearance page repeats over),
`getFooterTitle()`/`getFooterText()`, `getContactName()`/`getContactPhone()`/`getContactEmail()` +
`isShowContact()`, `getDonateUrl()` + `isShowDonate()`, `getAnalyticsId()`. An org whose row cannot be
read gets the neutral look and its slug as the name — never a broken page. Every URL is re-screened in the
bean even though the save paths validate (a hand-written row bypasses the page): anything with a quote,
angle bracket, backslash or whitespace is dropped (null → the blank behaviour), and the background URL is
additionally percent-encoded for the unquoted CSS `url()` context (`( ) ' " ; \ { }`). Text values
(footer, contact) are returned raw for the page to escape as usual.

**The page side** (sibling repo): `web.xml`'s `primefaces.THEME` is `#{brand.theme}`; `template.xhtml`
puts `brand.rootStyle` on `<html>` (`site.css` reads `--site-bg` with the shared rainbow as the `var()`
fallback), swaps the layout sheet, og:image and favicon per host, and gates the analytics tag on BOTH an
id for the host (the shared site's literal, or the org's own) AND the visitor's consent: the
`trip_consent` cookie (`1`/`0`, one year) that `WEB-INF/consentBanner.xhtml` sets, read straight off the
request so an anonymous page stays sessionless; no id means no tag and no banner. `topbar.xhtml` (logo or
wordmark), `footer.xhtml`, `menu.xhtml` (the shared site's Overview / Links / Help entries hidden, an org
Donate entry added) and `mainTemplate.xhtml`'s sidebar (Donate and "Questions?" cards from the settings,
"Trips" for "Pilgrimages") branch on `#{site.orgSite}`, so a shared host renders byte-for-byte what it
did apart from the Privacy footer link and the consent banner. `privacy.xhtml` is the stateless cookie
notice. The site Settings page hides org-only rows (`SettingSection.hasSiteSettings()` skips the Branding
section whole); the org editor renders a `choices` def as a menu whose blank item is "None (platform
default)". Fixtures: Beta Corp is fully branded (`FakeData.seedBetaBranding`, incl. `G-BETAFIXTURE`),
Acme is deliberately unbranded; `OrgSubdomainPwIT` pins both and the shared host.

**Never pair a literal text colour with `var(--primary-color)`.** Two chrome elements did, and both became
unreadable under a pale palette (yellow, avocado, red and turquoise put DARK text on their primary): the
sidebar Donate button and the topbar "Viewing:" chip. The Donate control is worse than a literal — it is an
`h:outputLink` wearing `.ui-button`, and every palette's `a:link{color:var(--primary-color)}` outranks
`.ui-button` on specificity, so the label was drawn in the button's own background colour and disappeared
whatever palette was chosen. Both now use **`var(--primary-color-text)`**, the theme's own contrast colour
for that background, with a literal only as the `var()` fallback.
`OrgSubdomainPwIT.anOrgSitesSidebarFollowsThePaletteAndStaysReadable` computes the rendered colours in a
real browser and fails if the label's colour ever equals its background. The rest of the site still carries
inline colour literals; sweeping them is a separate, tracked job.

#### The Appearance page and its live preview (2026-09-02)

`admin/orgAppearance.jsf?orgId=…` ("Appearance", hub card, `canManageOrg`, "Done" back to the dashboard)
is where an organization sets its look. Two fieldsets:

- **Appearance** — the colour palette as a menu, **Dark mode** as a radio right beneath it, the logo and
  favicon URLs, and the background chooser (Colour palette / Colour with a `p:colorPicker` / Image with a
  URL box).
- **Site details** — every OTHER branding setting (`site.ogImage.url`, both footer settings, both contact
  settings, `site.donate.url`), as labelled text boxes, in the same preview / Save / Cancel flow. The page
  repeats over **`brand.detailFields`** — `KnownSettings.branding()` minus `BrandCommands.DEDICATED_FIELDS`,
  the six with a control of their own — rather than hand-listing rows, because when this page was carved
  out of `orgConfig.jsf` (which excludes the Branding section whole) those six landed on NEITHER page and a
  site could not be given the contact name and phone its "Questions?" card shows.
  `OrgAppearancePreviewTest.everyBrandingSettingIsReachableOnTheAppearancePage` holds dedicated + detail
  against the section, and `theTwoOrgPagesPartitionTheOverridableSettings` holds branding +
  `orgOverridableNonBranding()` against `orgOverridable()`.

It replaces the generic name/value rows those settings used to get on `orgConfig.jsf`, which now renders
everything else.

**The preview is server-rendered, per user, and scoped to that one page.** The decision behind it: *"The
PrimeFaces theme is designed to be flexible, including per-user. It should be trivial to set the theme in
variables specific to this user and refresh the page. Refreshing from server ensures an accurate preview.
We do not need to show the homepage (for now), but I also do not want it to only be a client-side mock."*
So:

- Every control change posts back, stores the submitted values on that admin's own session
  (`BrandCommands.preview` → `Sessions.APPEARANCE_PREVIEW_ORG` + `APPEARANCE_PREVIEW`, a
  `Map<String,String>` — **scalars only, never a domain object**), and redirects to the page (a GET, so
  no re-post). The whole document is then rendered afresh with the unsaved values applied: the PrimeFaces
  theme, the Freya layout sheet and the page background really change. A redirect rather than an ajax
  update because `web.xml`'s `primefaces.THEME` is `#{brand.theme}`, resolved while `h:head` renders — no
  partial update can replace that stylesheet link.
- `BrandCommands` consults the preview BEFORE the org's stored settings, and only then. **The trigger is
  the view id plus the `orgId` request parameter** (`BrandCommands.appearanceViewOrgId`:
  `FacesContext.getViewRoot().getViewId()` equals `/admin/orgAppearance.xhtml` AND the request's `orgId`
  equals the preview's org). It is keyed that way because the theme is resolved during `h:head` render,
  *before* any `initPage` handler has necessarily run — a request-scope flag the page sets would be too
  late, so the preview would apply unreliably. The page carries `?orgId=` on every GET (that is what the
  redirects go to) and re-supplies it on postbacks with one hidden field, so the parameter is always there.
- The session is read with `getSession(false)` and **never created**: a request with no session gets the
  stored values, like every other visitor.
- **Cancel** clears the preview; **Save** writes through `OrgCommands.saveOrgSettings` (which re-checks
  `canManageOrg` — the preview itself is not an authorization point, it only shows a caller their own
  submission) and then clears it. Arriving at the page fresh — a non-postback GET with no preview for that
  org — always seeds from the stored values (`BrandCommands.appearanceEdit`), so what is on screen is never
  a stale copy of an earlier visit.
- Nothing else is affected: another page, another organization, another person's session, and the org's
  live site all read the stored values. Off the org's own host only the palette and background preview (the
  logo, footer and contact card are `#{site.orgSite}`-gated in the shared pages), which is why the page is
  most useful opened on the organization's own site.

Guarded by `OrgAppearancePreviewTest` (background precedence, the hex rule, and each half of the trigger)
and by `OrgSubdomainPwIT.anOrgAdminPreviewsAPaletteBeforeSavingIt`, which changes the palette on
`acme.localhost`, proves the new theme stylesheet is in the markup with nothing saved, cancels, saves, and
restores the fixture.

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
