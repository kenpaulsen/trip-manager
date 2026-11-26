# Content templates v2 -- fully template-driven pages

The mechanism behind the v2 landing page (`medjugorje/webapp/trip/index.xhtml`): EVERYTHING a page's
`mainContent` shows is content instances, rendered in order by one reusable include. Future pages adopt the
same structure with their own page key. Read this before touching `ContentCommands`, `TemplateCommands`,
`ContentDAO`, `contentSections.xhtml`, or any `ptypes/` fragment.

## Kinds

A `ContentTemplate` has a `TemplateKind`, **immutable after creation** (a change would orphan children or
strand instances; `saveTemplate` rejects it):

| Kind | What a template declares | What an instance holds | How it renders |
|------|--------------------------|------------------------|----------------|
| `STANDARD` | HTML body with `{{token}}` placeholders | placeholder values | `ContentRenderer` substitution, typed escaping |
| `CONTAINER` | a body that is the ROW wrapped around each child (see below), `allowedChildTemplateIds` (null/empty = any non-container), `maxChildren` (null = unlimited) | optional WYSIWYG title, optional `editorPrivileges`, optional per-instance `allowedChildTemplateIds` | title via `renderContainerTitle`, then its CHILDREN in order, each inside the container's row |
| `PROGRAMMATIC` | `programmaticTypeId` naming a registered type | the type's NVP property values | the type's Facelets fragment (full PrimeFaces behavior) |
| `MAIL` | an EMAIL body with `{{token}}` placeholders; the template NAME doubles as the subject line | none -- mail templates have no instances | never on a page; rendered by `MailCommands.renderManagedTemplateForOrg` (resolved per organization, below), tokens filled by JAVA only (no EL -- runtime-editable EL would be code execution) |

MAIL templates are excluded from every content picker; the shipped ones are `registration-received`,
`registration-approved`, `support-request`, `payment-confirmation`, `payment-failed` (a bank debit that was
accepted and later returned; see `docs/payments.md`), and `org-invite` (the org-admin
"invite by email" flow -- see `docs/org-admin.md`). They are edited on the
same `/admin/templates.jsf` manager as everything else (contentAdmin): the page has a kind filter
(`TemplateCommands.getTemplates(kind)`, fails open to the full list on blank/bogus values) and the
admin menu carries a dedicated "Email Templates" entry deep-linking `?kind=MAIL` -- the discoverability
fix for email copy hiding behind the "Content Templates" label. Rows written before v2 carry
no kind and read as STANDARD (`getKind()` folds null; `setKind` stores
STANDARD as null so JSON round trips stay equal).

## Section-key conventions

- **A page is a section key**: `page:trip-index` holds the landing page's ordered sections. A CONFERENCE's page is
  keyed by its trip, `page:trip:{tripUuid}:home`, and edited by that trip's managers as well as its org's content
  admins (`docs/conference.md`).
- **A container's children live under the container INSTANCE's id** as their section key. Container ids
  double as the page's anchors (`<a id="...">`): the landing page's containers are `events`, `reflection`,
  `docs`, so `#events`/`#docs` deep links keep working.
- Depth is capped at page → container → leaf: containers may not allow or contain containers (validated in
  `saveTemplate` and `saveContent`, and excluded from every picker).
- A STANDARD **child**'s title renders as its heading (the Events look). A page-level STANDARD section
  deliberately does NOT render its title (the title block and intro would sprout headings) -- a heading at
  page level is either markup in the body or the reflection pattern: a titleless container whose child
  carries the heading.

## Organization sites (per-org pages, 2026-09)

The same engine renders more than one site; **which page renders is a fact about the request's
hostname**, resolved once per request into a `SiteContext` (`org.paulsens.trip.site`, carried on
`RequestContext`) and read by pages as `#{site}`:

- **Page keys per site** (`SiteCommands.getPageKey()`): the shared site renders `page:trip-index`; an
  organization's own site (`{slug}.unitetrip.com`) renders `page:org:{orgUUID}:home` (the UUID, never the
  slug -- a site admin may rename the slug and the page must survive it); the product's marketing host
  renders `page:unitetrip-home`. `OrgPageBootstrap` owns these keys. `WEB-INF/homePage.xhtml` captures the
  key from the bean at initPage on every request and never stores it anywhere a later request could read it
  back (see `SiteContext`'s hard rule: never in sessionScope/viewScope).
- **The starter page** (`OrgPageBootstrap.rows`): a welcome section naming the org (obviously placeholder
  text), a `pilgrimages` section for **every language** (`language` = `TripCommands.ANY_LANGUAGE`, "Any"; a
  fixed English filter left a Spanish-only org's page empty), and a `photo-albums` section -- "less is more".
  "Any language" is also the last choice in any Trip Listings section's Language menu; a card in such a section
  takes its eyebrow words from its own trip's language. Seeded through the
  normal DAO save path by `OrgCommands.ensureHomePage`, **exactly once per org** (the org row records
  `homePageSeededAt`): on the subdomain's first assignment, or lazily from the org dashboard for an org
  slugged earlier. An org that empties its page keeps it empty; a hand-authored page is never overwritten.
  Instance ids are minted UUIDs -- the shared page's guessable ids (`events`, `docs`) are anchors and
  child-section keys and can never be reused by a second page. No script: onboarding an org is an admin-UI
  flow by design.
- **What a site lists is decided by ONE rule, `site/ListingScope`** (every public listing -- the page
  sections, the Trips menu, the sidebar, the countdown cards, the media API's slot browsing):
  - an ORG site lists only its own content, whatever the instance's properties say and whoever is looking
    (org-less legacy content belongs to nobody and does not show there);
  - a SHARED site shows a hosted org's content only when BOTH sides agree -- the section's curation list
    (`includeOrgs`, a `MULTI_CHOICE` property on the `pilgrimages` and `photo-albums` types, declared once in
    `SharedSiteOrgChoices`) AND the org's own `Organization.allowSharedSites` (org-admin checkbox on the
    profile; null = allow, false = never). With NO list a shared site shows the orgs that have no site of
    their own (today's tenants), so a newly hosted org stays off `visitqueenofpeace.com` until a site admin
    picks it -- no data migration. Org-less legacy content keeps listing.
  - `MULTI_CHOICE` stores its picks comma-separated in the ONE string value; the dialog binds a
    `p:selectCheckboxMenu` to `editContent.listValues[name]` (`ContentInstance.getListValues()`, a computed
    write-through view -- never a second field). The prompt is hidden on org sites, where it does nothing.
  - Media: `MediaItem.orgId` (null = site-level; every pre-existing row) decides DISCOVERY --
    `MediaCommands.discoverable`: an org's items only on its site, site-level items only on shared sites, in
    the library, pickers (`FileType`), the Documents slot and `getAll/getCurated`. Chat albums are scoped by
    their TRIP (an old null-org chat photo still shows in its album); chat rows are stamped with the trip's
    org at send, library uploads with the request's site. URLs are not restricted, discovery is.
- **Template scope**: `ContentTemplate.orgId` -- null = site-level/shared, else one organization's own.
  Sharing is an AUTHORING-time choice: the Add dialog (`getTemplateChoicesFor`) offers an org site the shared
  templates plus its own (`SiteContext.isSiteOf`), any other site only the shared ones; an instance then
  pins whichever it chose. Rendering reads the field in one place only: an instance moves to a version other
  than the one its values were saved against (Stay on Latest, or the aged-out fallback) only when that version
  has the SAME organization (`ContentCommands.mayFollow`; below), and the field rides the cached template, so
  the zero-live-read rule below is untouched. The template manager's Scope column/menu
  (`TemplateCommands.scopeChoices`/`scopeLabel`) sets it; a blank menu choice saves as null; an unknown org is
  refused.
  **Customizing a shared template for one org is a COPY** (`TemplateCommands.customize(id, orgId)`, the
  manager's "Customize" button in an org scope): the clone is org-scoped at version 1 with the same
  kind/body/placeholders; the shared row stays shared and read-only to the org's editor
  (Edit/History/Delete render only where `mayAuthor` says so). **EMAIL templates are copied like every
  other kind** -- see "Per-organization email copy" below. Details: `org-admin.md` "Org-site editors".
- **Editing** an org's page happens on the org's site with the same edit mode. Authorization is
  privilege-only: site admin, global `contentAdmin`, or `contentAdmin` scoped to that org (see
  `org-admin.md` "Org-site editors") -- org admins do not edit content by being admins. The org of a
  section is derived from the section (page key, or the container's page), so an org grant never reaches
  the shared or marketing page.
- An empty page shows a plain notice (`homePage.xhtml`): the marketing host's "coming soon" until
  `bootstrap-marketing-page.sh` has seeded it (see "Bands and the full-width layout"), or "this site is
  being set up" on an org host whose page is not seeded yet.

## Named pages (2026-10)

A site has more content pages than its home page. A **named page** is a lower-case slug (`board`,
`our-team`; `OrgPageBootstrap.isPageName`) whose key is scoped to the site the same way home is
(`OrgPageBootstrap.pageKeyOf(site, name)`, exposed as `#{site.pageKey(name)}`):

| Site | `home` (unchanged keys) | any other name |
|------|-------------------------|----------------|
| shared host | `page:trip-index` | `page:shared:{name}` |
| an organization's site | `page:org:{uuid}:home` | `page:org:{uuid}:{name}` |
| marketing host (the platform org's site) | `page:unitetrip-home` | `page:org:{platform uuid}:{name}` |
| placeholder marketing host (no platform org yet) | `page:unitetrip-home` | `page:unitetrip:{name}` |

The platform org's named pages are its Editable Pages (Pricing, Why UniteTrip; issue medjugorje#41), keyed like any
org's so the Editable Pages admin page seeds and deletes them where the page host reads them; only its HOME keeps the
fixed marketing key. So every site has its OWN copy of a named page, never the shared site's. `OrgPageBootstrap.orgOf`
recognizes every `page:org:{uuid}:{name}` key and `isMarketingPageKey` every marketing one, so an org-scoped
`contentAdmin` edits all of its org's pages and the platform org's editor all of the marketing host's, through the
unchanged `canEdit`/`mayEdit` rules.

One named page of an organization is NOT an Editable Page: `page:org:{uuid}:pay`, its "How to pay" page, which
`trip/pay.xhtml` hosts on any site that reaches the organization and `PayPageCommands.ensurePage` seeds once
(`docs/org-admin.md`, "The pay page"). The names `home` and `pay` are reserved for that reason
(`OrgPageBootstrap.isReservedPageName`).

**Everything is an Editable Page on an org site** (owner decision 2026-11-13, issue medjugorje#124). An org site's
named pages exist only as `Organization.sitePages` entries, served by `page.xhtml` (`/page.jsf?name={name}`)
and managed on its Editable Pages admin page (`docs/org-admin.md`, "Editable Pages"). Before this, the hand-written
`board/index.xhtml` rendered `page:org:{uuid}:board` without being in that list, so an admin adding an Editable Page
named `board` silently took its rows over. Now `/board/index.jsf` on an org host only redirects to
`/page.jsf?name=board` (same key, so no row moved), and every new site starts with a `board` Editable Page (the
starter pages, `OrgPageBootstrap.starterPages()`).

**Adding a page**: on an org site, add an Editable Page; never a new host file. The only hosts of
`WEB-INF/contentPage.xhtml` are `page.xhtml` and, on a SHARED host, `board/index.xhtml` (rendering
`page:shared:board`, its production rows from `medjugorje/scripts/bootstrap-board-page.sh`, locally
`FakeData.addFakeBoardPage`); `SitePagePolicyIT` (webtest) fails on any other. A host composes it with three
ui:params -- `pageName`, `pageTitle` (the shared-host title; org sites show the org's name), `selfUrl` -- and
`page.xhtml` hands over the STORED page name, never the request parameter. `contentPage.xhtml` is the
home host's initPage and edit toggle without its home-only parts (banner, library uploads, photo viewer).
Its initPage reads the `pageName` ui:param directly: a jsft `initPage` runs during Facelets tag
application against the FaceletContext, where the composition's params are still visible. Only the LATER
events (beforeEncode, command, ajax) lose them -- which is why the include's command scripts still read
`viewScope.pageKey`.

### People: the `person` type and the `people-list` container

`PersonType` (`person`) renders one person's card (`WEB-INF/ptypes/person.xhtml`): picture, name and role,
email, phone. The instance stores the person's **id** (a `PERSON` placeholder) plus the page's own words --
`role`, `publicEmail`, and `name` / `phone` overrides for someone with no account, plus `photoUrl` (absolute
http(s) only), `subtitle` and a RICH_TEXT `bio` for a conference SPEAKER, who usually has no account
(`docs/conference.md`; the same card, not a second type). Name, cell, account email
and photo are read live from the account by `content.personCard` (memoized per request), so a profile edit
shows everywhere the person is listed.

**Privacy (user decision 2026-10-07):** listing a person is a content editor's deliberate publication, so
the account fields keep the reach the hand-written board page gave them -- the cell to everyone, the account
email to signed-in viewers (only when it differs from `publicEmail`) -- EXCEPT that a field the person set
PRIVATE is withheld here too (and a private email is not hashed into a gravatar either; the card shows
initials). The row's typed values always show.

`PERSON` is a provider-backed placeholder (like CHOICE: programmatic types only, never in the template
editor's type menu). The dialog renders it as a `p:autoComplete` bound to the id STRING in
`editContent.values` -- no converter (a Person would land in the string map), and `itemLabel` goes through
`people.pickerLabelFor`, which accepts a Person (suggestion rows) or an id (the stored value: PrimeFaces
evaluates `itemLabel` against the raw current value). **Tenancy:** the suggestions come from
`content.completeForContent`, authorized against the dialog's `viewScope.editSection` (an organization's
page offers only that org's members, to anyone; elsewhere only a global contentAdmin/site admin searches
everyone), and `saveContent` re-checks every PERSON value (it must exist, and on an org page be a member per
`org_members`), because the posted value is just a string.

`people-list` is a CONTAINER starter admitting only `person` items (its Add skips the type picker); its row
markup only names classes (`.peopleList` / `.peopleRow` / `.personCard` in `resources/css/site.css`).

## Per-organization email copy (2026-09-02)

An organization customizes the email sent **on its behalf** the same way it customizes page content: by
copying the shared template. What makes that mean something for MAIL is that senders no longer resolve an
email template by its fixed id alone.

**Resolution order -- one method, `TemplateCommands.resolveForOrg(templateId, orgId)`**, and everything goes
through it (the manager page and every sender, so what an editor sees and what goes out cannot disagree):

1. the organization's own copy of `templateId`, when it has one;
2. otherwise the shared row under `templateId`;
3. nothing (senders treat that as "do not send" and log it) when neither exists.

A row sitting under the copy's id that belongs to a DIFFERENT organization is ignored -- resolution checks
the owner, not just the name, so no tenant is ever served another's wording.

**The copy's id is derived, never stored**: `{templateId}-{slug}` for a hosted org (the convention the
manager has always shown) and `{templateId}-{orgUUID}` for an org with no subdomain, since an org reached
from its hub need not be hosted to customize its mail. Both the copy and the lookup compute it with
`TemplateCommands.orgCopyId`, so they cannot drift. *Caveat, inherited from the copy convention:* renaming
a hosted org's slug orphans its existing copies (they keep the old id and stop resolving); reverting and
re-customizing repairs it.

**The organization comes from the ENTITY, never from `SiteContext`.** Background senders run under
`RequestContext.system()` with no host at all, and a capture callback or an approval can happen from any
host, so each caller passes the org it already holds:

| Sender | Organization |
|--------|--------------|
| `OrgCommands.sendOrgInvite` (`org-invite`) | the org being invited to |
| `RegistrationCommands.approvePending` (`registration-approved`) | the trip's |
| `joinTrip.xhtml` (`registration-received`) | the trip's |
| `PaymentMailer.sendConfirmation` (`payment-confirmation`) | the payment's, falling back to its trip's |
| `OrgCommands.sendPaymentTestMail` / the payment-mail preview | the trip's -- a test send must resolve exactly what the live one does |
| `SupportChatCommands` (`support-request`) | **none, deliberately**: it notifies the SITE's support admins, on nobody's behalf |

`MailCommands.sendManagedTemplateForOrg` / `renderManagedTemplateForOrg` /
`renderManagedSubjectForOrg` / `renderManagedBodyForOrg` are the org-aware entry points; the org-less
`sendManagedTemplate` / `renderManagedTemplate` forms remain and delegate with a null org (the shared row).

**One row per use case.** In an organization's scope (`/admin/templates.jsf?orgId=…` from the org hub, or
the org's own site) `getTemplatesFor(kind, orgId)` shows each use case ONCE -- two rows for one email would
be a standing invitation to edit the one that is never sent:

- not customized: the shared row, read-only, offering **Customize** (which copies it and opens the copy for
  editing in one click);
- customized: the org's own row, editable, badged **Customized**, with **View site default** (the shared
  subject and body, read-only) and **Revert to site default** (`revertToSiteDefault`, behind a
  `p:confirmDialog`, which deletes only the org's own copy -- an org template with no site default behind it
  is refused rather than silently destroyed).

The SITE-WIDE page (no `orgId`, no org host) is the **site defaults** page and hides org-owned rows
entirely: a site admin editing "Registration received (Acme)" from there would be fixing one tenant while
believing they were fixing everyone.

**Naming.** A copy's name gains a "({org})" suffix for every kind EXCEPT MAIL, where the name IS the
subject line and the suffix would ship in the subject of every email the org sends. Nothing is lost: in the
org's own list the Scope column and the Customized badge say whose row it is.

## Language versions of an email (2026-11)

An email goes out in its RECIPIENT's language (`docs/i18n.md`), and its wording is an author's, so a language
is a whole MAIL row of its own, never a bundle key: the template's id plus `@` and the language tag, the
`content/PageVersions` convention (`registration-received@es`; an organization's copy's version is
`registration-received-acme@es`). The template's own row is written in **English**; that is also what the
old two-argument `resolveForOrg` and every sender not yet converted read.

**Resolution -- `TemplateCommands.resolveForOrg(templateId, orgId, locale)`**, the first that exists:

1. the organization's own version in the language (`{id}-{slug}@es`);
2. the organization's own copy (`{id}-{slug}`): an organization's wording outranks the site's translation of
   words it chose to replace;
3. the shared version in the language (`{id}@es`);
4. the shared row.

English, a null locale and a language this build does not speak resolve exactly as the two-argument form; the
locale's LANGUAGE counts, not its region (`es-MX` reads `@es`). Every level checks the OWNER as well as the id:
an organization's version is served only to that organization, and a "shared" version some organization owns
is served to nobody. `saveTemplate` refuses a version whose scope differs from its template's
(`feedback.templateCommands.versionScopeFixed`), so an organization's editor cannot plant its wording under a
shared template's version id.

**Senders pass the recipient's language** (`i18n/ReaderLanguage`) through the locale overloads
`MailCommands.sendManagedTemplateForOrg(id, orgId, locale, ...)` / `renderManagedTemplateForOrg(id, orgId,
locale, values)`:

| Sender | Language |
|--------|----------|
| `PaymentMailer` (confirmation, payment failed, checkout confirmation) | the payer's, about the trip |
| `RegistrationCommands.sendRegistrationMail` (`registration-received`) | the registrant's |
| `RegistrationCommands.approvePending` (`registration-approved`) | the traveler's, also when their family's managers are mailed |
| `OrgCommands.sendOrgInvite` (`org-invite`) | the invited org's site default (`ReaderLanguage.ofInvitee`): the invitee has no account |
| `OrgCommands.sendPaymentTestMail` / the payment-mail preview | the signed-in admin's, about the trip |
| `ReminderCommands` "Add suggested messages" and the automatic-mail list | the TRIP's: a trip's messages are written in its language |

Still English (the old signature): the support-request notice (site staff, nobody's behalf) and the approval
preview on `admin/tripRegistrations.xhtml`. The code-built token values inside these emails (fee notes,
amounts blocks, dates) are not yet in the recipient's language.

**Shipped versions.** `content/StarterTranslations` holds Spanish and Croatian versions of the seven
transactional starters and the four reminder starting texts, reviewed and approved by the owner with the bundles
(`docs/i18n.md`). Each installed row's description names its language and starter ("Español version of
registration-received; the tokens are the same."). Rows installed before the review keep their old "DRAFT
translation, not yet reviewed" description until they are reinstalled (an install never rewrites a row, so the
old row has to go first) or the description is edited on the Templates page. Their tokens are the English
starters' character for character (`MailTemplateVersionsTest` checks). They install exactly as the starters do,
after them (`StarterTemplates.all()` / `IDS`, so the Install button, `FakeData` and
`install-starter-templates.sh`), and a later install never rewrites one. A count inside a sentence cannot use
plural rules through a `{{token}}`, so the countdown versions put `{{daysUntilDeparture}}` after a label.
`ReminderDefaults.text(id, language)` is the built-in fallback in a language.

**Customize and Revert take the versions along.** Customizing copies the shared versions into the
organization's scope (otherwise its English copy would outrank the site's Spanish and switch Spanish readers to
English); Revert to site default deletes the organization's versions with its copy.

**The manager page** (`/admin/templates.jsf?kind=MAIL`). A version never lists as a row of its own
(`getTemplates` hides ids carrying a language mark); under each email's id the "Language versions:" block
(`TemplateCommands.versionsOf`) lists one entry per language the page's site offers besides English
(`versionLanguages`: `SiteLanguages.parse(site.languages)` for the scope organization, or the shared site's on
the site-wide page), so an English-only site shows nothing new. A written version shows its language's name,
with Edit and "Delete this version" (behind a `p:confirm`) where the caller may author it; a missing one offers
"Add a version in {language}" to anyone who may author the template (`addVersion`: a copy of the template, in
its scope, opened in the editor at once). Privileges are the template's: an organization's editor reads the
shared versions and authors only their own organization's. `OrgSubdomainPwIT` drives it on Beta's site.

## Programmatic types

`ProgrammaticContentTemplate` (in `org.paulsens.trip.content`, EL-friendly getter naming): `getTypeId()`,
`getDisplayName()`, `getDescription()`, `getProperties()` (a `List<Placeholder>` -- the NVP prompts the content dialog
shows), `getFragmentPath()` (`/WEB-INF/ptypes/*.xhtml` in the medjugorje repo), and `choicesFor(prop)` feeding
`Placeholder.Type.CHOICE` dropdowns (e.g. the File type's media picker, the pilgrimage language menu). Registry:
`ProgrammaticTypes.ALL` (KnownSettings pattern). Shipped types: `pilgrimages`, `photo-albums`, `file` (one media item by
id -- title/size/URL read LIVE from the cached media table; hidden or deleted media renders nothing), `person`, the
conference page's `trip-schedule` and `trip-registration` (`docs/conference.md`), Trip Detail Pictures
(`trip-pictures`) and Trip Details (`trip-details`), the trip page's top block (`onlyTripPage()`: offered on a trip's
own page alone; `docs/trip-pictures.md`, "On the trip page"), and `metric-graph`, one graph of the
app's own metrics, the metrics dashboard's building block (`docs/metrics.md`), laid out by the `graph-grid` CONTAINER
(four columns; each child cell spans its graph's own width and height). `offeredTo(Caller)` (default true) keeps a type
out of the Add dialog for an editor it does not serve: `metric-graph` answers with `MetricsCommands.mayViewMetrics`, the
one rule for who may read what it draws (site admins, for now). `fitsTripPage()` (default true) keeps a type off a TRIP
page's Add dialog: `pilgrimages` lists other trips and answers false; `photo-albums` fits, since on a conference's page
it shows that event's own album (`conference.md`).

Fragments are **pure render-time** (cards/galleria/rows only -- no build-time tags) and read the
hosting instance as `#{instance}`. A template of kind PROGRAMMATIC copies its type's properties into
`placeholders` on every save, so version pinning and the manager's placeholder table work exactly as for
STANDARD -- **but the stored list is advisory at most: every reader takes the registry's LIVE property list**
(`ProgrammaticTypes.placeholdersOf(template)`, used by the content dialog via `content.placeholdersOf(instance)`,
by `createContent`, RICH_TEXT validation/normalization, the version-switch migrator, and the manager's
`getTemplate` copies). The stored copy is a snapshot of the registry as it was when the row was written:
production's `pilgrimages`/`photo-albums` starters predate the `includeOrgs` property, and the dialog once
iterated the row, so the "Organizations shown" menu never appeared there (2026-09-01). A property added to
a type therefore needs NO re-install and no data migration; a row whose type is no longer registered falls
back to what it stored. On the page, `contentSections.xhtml` emits one statically-included fragment per
registered type behind a `rendered=` guard -- the ONE place build-time `c:forEach` is used, safe because the
registry never changes at runtime.

## Rendering, editing, privileges

- `#{content}` helpers drive the include: `getForView`, `childrenFor`, `getKind`, `typeId`, `render`
  (STANDARD only; "" otherwise), `renderTitle`, `isVisibleNow`, `getTemplateChoicesFor`,
  `autoStartContent`, `getChoices`, `idsFor`, `titleOf`, `canEdit`, `canEditPage`, `applyOrder`,
  `frameClass`, `completeEditorPriv`, `getEditorPrivNamesJson`, `offersVersionMenu`, `versionChoices`,
  `choiceLabel`, `getTemplateVersions`, `pinnedVersion`, `versionLabel`, `isTemplateOutdated`,
  `retargetTemplateVersion`, and for adding a section (below) `templateCards`, `sampleRender`,
  `previewInstance`, `saveNewContentAt`, `landingAnchor`.
- **Edit mode is asked for AND permitted.** A host captures it once with
  `content.editModeFor(param.edit, pageKey, userId, alsoAllowed)`: `?edit=true` and `canEditPage` (or whatever
  else that host's own "Edit page" button admits). Edit mode makes `getForView` list every row, the hidden and
  not-yet-visible ones included, so the bare parameter with only the buttons gated showed them to any signed-in
  reader. `../medjugorje/webtest/.../EditModePolicyIT` scans the pages for any other capture.
- **Privileges**: contentAdmin (or a site admin) edits everything. A container instance may name
  `editorPrivileges` (only contentAdmin can set them -- the save path guards the field): holders of ANY
  listed privilege may add/edit/reorder/delete that container's CHILDREN (the landing page grants
  `eventAdmin` on `events` and `mediaAdmin` on `docs`). The dialog renders them as autocomplete CHIPS
  fed by the stored global privilege names; free-typed names matching no stored privilege turn red and
  are silently DROPPED on save (`guardContainerConfig`/`sanitizeEditorPrivileges`). The old
  SECTION_EDIT_PRIVS map is gone. `canEdit(section)` resolves the section as a container id; pages must
  keep it BEHIND `viewScope.editMode and ...` so anonymous renders never reach its uncached lookup.
- **Per-container child restriction**: a container INSTANCE may carry its own `allowedChildTemplateIds`
  (contentAdmin-only, "Allowed items" in Edit-this-section); when non-empty it takes precedence over the
  container template's list, in both the Add dialog's choices and the save-path validation. When the
  effective choice is exactly ONE template, the Add flow skips the picker entirely
  (`autoStartContent`) -- the Documents container (Files only) opens straight on the media picker.
- **Edit-mode visuals** (emitted by the include, not host pages): each section is wrapped -- buttons
  included -- in a rotating dashed color frame (`content.frameClass(index)`), item-scoped buttons render
  outlined (`contentItemBtn`) vs the filled section/page buttons, and the page toolbar is titled "Page
  Operations".
- **HTML validation**: `HtmlCheck.structureProblem` (jsoup's HTML5 parser: every place a browser would repair
  the markup, such as a stray or out-of-order end tag, an element left open, or a tag, quote or comment left
  unfinished; the end tags HTML lets an author leave out are fine) gates `saveTemplate` bodies and `saveContent`
  RICH_TEXT values and markup-bearing titles. Failures keep the dialog open and are listed IN it (`dlgMsgs`, a
  `p:messages`: the page's growl renders under a modal dialog's overlay, so a message that only went there is never
  seen). It reads markup as a browser does, which `RichTextRules`' own tag scanner (`HtmlTags`) does not attempt:
  that one only finds block boundaries for its normalizing.
- **RICH_TEXT is BLOCK html, and that is not negotiable**: the editor writes every line as a block element, so a
  one-line caption comes back as `<p>…</p>`, and rows written before 2026-11-23 by Quill (the editor then) carry
  alignment as a `ql-align-*` CLASS that only works where Quill's stylesheet is loaded. Two consequences, each
  handled in its own place:
  - **On save** (`RichTextRules.normalize`, called from `saveContent` AFTER validation): alignment classes
    become inline `text-align` styles, trailing empty paragraphs are dropped, and a value that is exactly
    ONE ATTRIBUTE-FREE `<p>` loses that wrapper. Multi-paragraph values keep their blocks (there the blocks
    are the author's meaning), an aligned value keeps its `<p>` (alignment needs a block to live on), and
    anything with attributes is treated as hand-authored and left byte-identical -- the Source toggle makes
    raw authoring reachable, and it must round trip.
  - **In a template BODY**: a RICH_TEXT `{{token}}` must sit in a block container (`<div>`), never inside a
    `<p>` -- paragraphs cannot nest, so the browser closes the outer one early and the value renders OUTSIDE
    its intended spot (this is a real bug that shipped: an image caption escaped its paragraph). `saveTemplate`
    warns about it (`RichTextRules.richTextTokensInsideParagraph`) without blocking, and `RichTextRulesTest`
    holds the shipped starters to the rule.
- **Changing an instance's template version**: the content dialog shows a "Template version" menu for EVERY
  instance whose template exists (`offersVersionMenu`): new ones and one-version templates included. Its first
  choice is **Stay on Latest** (below) when the instance may follow the template's current version (`mayFollow`)
  or is already marked; then each retained version `mayFollow` allows, the template's actual current version
  marked "(latest)" (no row carries it when that version is not offered) and a pinned instance's own
  "(in use)"; the selected value is `pinnedVersion`, and an amber note shows when a newer
  version exists (never for a Stay on Latest instance). Switching calls `retargetTemplateVersion`, which runs
  `TemplateValueMigrator` over the values: same name is kept, then same label (case-insensitive), then a lone
  same-type survivor on each side; anything left is DROPPED and named in the growl. New placeholders arrive
  declared-but-empty. Nothing is stored until Apply, so Cancel abandons a bad migration. The version menu's
  remoteCommand processes the WHOLE form so typed-but-unsaved values migrate too. A new instance is still
  pinned to the newest version at creation unless the editor picks Stay on Latest.
- **Stay on Latest** (`ContentInstance.stayOnLatest`, JSON `stayOnLatest`, written only when true; absent =
  pinned, every row written before 2026-11-14): the instance renders with its template's CURRENT version, so a
  template save shows on every such instance with no edit of it. Picking it retargets the working copy to the
  newest version exactly like picking that version (same migration, same growl) and marks it; Apply stores the
  mark with `templateVersion` = that version. Picking a number pins it again, even the number it is already on.
  `templateVersion` keeps meaning "the version the stored values were saved against": when the template has
  moved on, every READ carries the values across in memory with the same migrator (`ContentCommands.followLatest`,
  applied to each row a section listing returns, to the dialog's copy, and by `render` to a row read any other
  way) and never writes; the next save stores the carried values (`saveContent` carries a stale Stay on Latest
  copy forward before validating, so a template saved while the dialog was open, or an older row from a script,
  saves against the current version). When a carry on the dialog's open or on a save renames or drops a value,
  the editor is told (a warning growl with the migration summary; the Edit openers update the growl). Values
  never move backward (a lagging cache answering an older "latest" changes nothing). A saved-against version
  that aged out of retention carries same-named values only, which is what a pinned instance gets from its own
  fallback; a deleted template falls back exactly as a pinned instance does (the pinned version, then nothing).
  **Tenancy** (owner decisions 2026-11-15, `ContentCommands.mayFollow`): who may use a template is decided
  when content is added, and a template re-scoped to an organization later must not carry that organization's
  markup onto pages it was never offered to. So while the version an instance's values were saved against is
  retained, the instance moves (follows the latest, or is switched in the version menu) only to a version of
  the SAME organization (`ContentTemplate.orgId`, null = shared); otherwise it renders as pinned, choosing Stay
  on Latest is refused, and the version menu neither offers nor accepts another organization's version. Once
  the saved-against version has aged out its organization is unknown, and the Add dialog's own rule decides:
  a shared version, or one owned by the requesting site's organization (`SiteContext.isSiteOf`), so an
  organization's own template keeps rendering on its own site and nowhere else. That aged-out fallback (of
  EVERY instance) renders the latest when the site may use it, else the newest retained version it may use (a
  walk down the cached `id#vN` fields, stopping at the first one gone). Only when no retained version qualifies
  does it fall back to nothing (a container to its built-in row). Containers too: a Stay on Latest
  container's row, wrapper and title slot follow the template. **Cost**: the template's current version has
  its own cache field (`CacheKeys.templateLatestField`, `id#latest`, beside the `id#vN` fields in the same
  hash; written by every save and the scan rebuild, removed by a delete), read through
  `DAO.getLatestTemplate` (and, only while that field is missing, as right after this build's first deploy or
  for a template a script wrote, the row-backed `getTemplate(id)` the render path used before; the aged-out
  fallback of a pinned instance reads the same way): one cached hash-field read, the same as a pinned lookup
  (heap-served under `Cached.YES`), and zero DynamoDB reads on a warm cache (`RenderPathCacheTest`). Only a row
  whose stored values lag the current version adds one more cached field read (the saved-against version) and an
  in-memory migration per section read.
- **Growl messages must carry everything in the SUMMARY**: `template.xhtml` renders message details only for
  the URL-parameter messages (`hasDetail`), so a detail-only explanation is invisible to the user. Found by
  clicking through the container; no test can see it.
- **The editor never removes what the author did not remove (owner's rule, 2026-11-23).** Only the server's rules for
  a field decide what is stored, and anything they drop is named before saving, never silently. Script, object, embed,
  noscript and srcdoc markup becomes a locked placeholder block while editing ("kept as written") and comes back
  exactly on save; an on* handler is renamed to an inert data- name while editing and renamed back on save; the HTML
  view shows and edits the real markup; the editor's own clean-up on load never counts as the author's change. Four
  kinds of host: (1) trusted code, a STANDARD template body: everything is kept, scripts and handlers included, and
  stored as written; (2) published web content (the content drawer's sections, Editable Pages, trip page body and pay
  page, waivers (whose publish refuses, naming it, anything but a document's own elements, a handler or a script
  address, and otherwise stores the body as written), the lodging option policy, and to a lesser degree event notes):
  every tag and style the author wrote is kept, and whatever the server will not store is named before saving (the
  drawer's strip question; elsewhere the save's notice); (3) email (MAIL template bodies, mail merge, scheduled
  messages): what mail clients show is kept and offered (colors as plain values, alignment, indent, tables, images by
  web address), and no video; (4) notes written by or about members (event notes, the itinerary's private notes): a
  small toolbar, and member-supplied text (a name in a placeholder, an aria-label, a tooltip, a swatch label) is
  always text, never markup. Machine checks: a stored body opened and saved with no edit comes back byte for byte, and
  one holding a script and an onclick keeps both and runs neither while edited.
- **The rich-text editor (2026-11-23)**: every rich-text field on the site is an `h:inputTextarea` with the style
  class `tripRichEditor`, which `resources/trip-js/richEditor.js` turns into a Jodit editor (MIT, vendored in
  `resources/trip-js/vendor/jodit/`), on a page that includes `WEB-INF/richEditor.xhtml`. It replaced PrimeFaces'
  `p:textEditor` (Quill), which wrote its HTML back out from its own model and silently dropped any markup it had no
  format for: an image's float, a styled wrapper `div`, a frame's size, the moment an author typed (found
  2026-11-23 on the visitqueenofpeace.com home page). Jodit edits the markup itself, and the config turns off every
  clean-up that would change what the author did not touch (`wrap-nodes`, `safeLinksTarget`,
  `sandboxIframesInContent`, old-tag replacement, empty-element removal); a spike over every rich-text value of the
  home page and two trip pages found an edit changes only what was typed, and undo and the HTML view round-trip
  exactly. `RichEditorPolicyIT` fails the build on any `p:textEditor` or Quill, on an editor made outside
  `richEditor.js`, on a rich-text field whose page never loads the editor, and on a test naming the editor's markup
  outside `pw/Editors`.
  - **The field holds what will be saved**: the stored markup until something changes (Jodit spells a few things its
    own way, `<img>` for `<img />`, so an untouched editor never rewrites a row), then Jodit's value, or in the HTML
    view exactly what is typed. Jodit copies its value into the textarea only after a short pause, so every click,
    submit and Enter first brings each field up to date (capture phase, ahead of PrimeFaces' request); each change
    also fires a bubbling `input` on the textarea, which is what the content drawer listens for.
  - **Toolbars** follow what the field's cleaner keeps: the full one for page content, templates and the lodging
    policy; the same without video (`tripRichNoVideo`) for waivers, whose publish check refuses every frame; one
    mail toolbar for both mail editors, a scheduled message and the mail merge (plain colors, alignment, indent,
    tables and images by web address, which `RichTextHtml` keeps in mail since 2026-11-23, and no video, which it
    drops);
    `tripRichNote` for an event note and `tripRichNote tripRichPrivate` for a private note.
    An event note keeps a video only as Quill's `ql-video` frame, so the note editor marks the frames it inserts.
  - **Words**: Jodit carries Spanish; `resources/trip-js/richEditorLang.js` adds Croatian (formal) and moves its
    Spanish to "tú". The image prompt is `js.editor.imageUrl`.
  - **A template BODY** also has the model-bound "Edit raw HTML" mode in `templateDialog` (no editor at all), the
    one way to write a CONTAINER body. **The content drawer's editors** once carried a Visual | HTML | Preview tab
    strip; it went when the drawer started drawing the item in the page as it is typed (the in-page preview,
    below). That redraw carries `contentPreview=1`, which every `required` in the dialog honors as a bypass, or a
    half-filled form could never preview.
- **Markup lost while its words stayed is never saved silently (2026-11-23).** The net under the editor: the
  content drawer's Apply compares each stored rich-text value with what is about to be saved (`content/MarkupLoss`,
  called from `ContentCommands.wouldRemove`) and names every tag with attributes that went while its words, its
  image's address or its link's address stayed, in the same question that names what the allowlist removes. An
  author's own deletion or replacement (the words went too; word PAIRS are compared, since common words are in any
  text) and a change of spelling (`b`/`strong`, a `div` that became a `p` with the same attributes, attribute order,
  Quill's alignment classes) are not reported. "Clear formatting" over styled text is the everyday way to reach it.
  Re-applying the lost markup onto the edited HTML was considered and rejected: an editor splits and merges
  elements, so which edited element a lost wrapper belonged to cannot be told reliably.
  The check leans to asking (the owner's choice, 2026-11-23): it reads the whole edited value, so joining two styled
  lines into one, or deleting one of several lines alike, asks about formatting that did not go. A rule that left out
  the other lines' words stopped those questions but hid real losses inside an unchanged tag of the same spelling, and
  was reverted: an unneeded question costs a click, a missed loss costs the formatting. Only tags that carry
  formatting (an attribute beyond an address) are compared at all. **What it compares and indexes follows the size of
  the edit, not of the page** (reading the two values and one set of the edited value's words follow the page, which
  the length bound caps), because it runs on a page request at every redraw of the drawer, on the one server every
  organization shares, over whatever an author posts. Equal values are answered unread; a reading holds each word once
  (a tag's text is a range of the words, however deep the tag sits); tags identical on both sides cancel in one pass;
  only the tags an edit touched are compared by content, through an index of the edited text's word pairs (never a
  scan of every tag: that was quadratic, 14 seconds for a 300 KB page whose font had changed), and a restyled version
  is looked for under the RAREST attribute name the stored tag has. What is left is bounded by COUNTS of work, never
  by a clock: comparisons, each attribute name compared counting as one (`MarkupLoss.WORK_BOUND`; a value of tags with
  two dozen attributes once held a request thread for eight seconds while counting as little work), what is kept per
  indexed tag, charged before it is built (`HELD_BOUND`; a tag with 300 attributes around 50,000 words was once
  indexed once per attribute, 2.4 GB from one 254 KB value), and a value's length (`LENGTH_BOUND`, 400,000 characters,
  more than any row holds). And at most two checks run at once (`MarkupLoss.AT_ONCE`, a semaphore; the next waits), so
  whatever they cost, they never hold every carrier thread; a check that waits more than 1.5 seconds for a turn is
  skipped, and an untouched field never waits. That wait is the one clock: when it runs out at Apply but not at the
  redraw, the two lists differ and Apply asks once more, never saves unasked. The redraw and Apply must list the same
  losses, since Apply is answered by the key of the list that was shown. Above a bound no tag is listed, a warning is
  logged, and the drawer shows one line instead saying the text is too large to check
  (`server.stripNotes.notChecked`), so Apply still asks. `wouldRemove` itself is answered once per request and working
  copy (`RequestMemo`; one redraw asks four times), and any write of a content row forgets the answer. Both sides are
  tidied alike before the check (`RichTextRules.normalize`), so a row still holding Quill's alignment classes does not
  read as lost.
- **The content dialog (2026-11)**: 90% of the window (capped at 1400px by `trip.css`, `.contentDlg`); each
  prompt's label is a `p:outputLabel` FOR the row's widget (`content.widgetIdFor`), so PrimeFaces draws the
  required star from the widget's own `required` and a blank required prompt refuses Apply with the message in
  the dialog (and
  `validateForSave` refuses it on the API too, an empty paragraph counting as blank); a rich-text prompt a
  starter once labeled "Text" or "Body" reads **Content** (`content.promptLabel`, on every installed version;
  the starters themselves say Content now). **The template picker** lists a row per template
  (`content.templateCards`: name, description, a Group or Live badge) and draws the selected one in the page
  from its SAMPLE render (a section template's placeholders' `sample` values: `Placeholder.sample`, the label
  when a prompt has none, never a URL; the template manager's placeholder table has a Sample column). It has
  been a drawer at the side of the page, with no card grid and no thumbnails, since 2026-11-16: "The content
  drawer", below.
- **Reordering**: the shared arrange dialog (`p:orderList`, drag or buttons) calls
  `applyOrder(section, orderedIds)` → `ContentDAO.reorderContent`, a **version-silent** in-place position
  rewrite (no version bump, no history churn; an editor dialog open across a reorder re-saves its stale
  position -- accepted).
- **Structural edits reload the page** (`tripApplyDone`/`tripReloadIfSaved` + the `:form:reloadFlag`
  contract in `contentSections.xhtml`): the include iterates viewScope-frozen lists, so in-place ajax
  cannot reflect adds/deletes/reorders.


### Fixed choices and lengths (2026-11-25)

Two optional fields on a prompt (`Placeholder`), both written only when set, so older rows read and re-save
byte for byte (and `ContentTemplate.ser` keeps its `serialVersionUID`):

- **`options`**: a hand-written template's prompt with options is a `CHOICE`, shown as a menu of exactly those
  values (`content.getChoices`, which answers a programmatic type's provider otherwise). The template editor has an
  Options column (comma-separated) and offers CHOICE in its type menu; a save refuses a CHOICE with no options and a
  sample that is not one of them. A content save refuses a value that is not a choice, but only a value the save
  CHANGES: a value typed by hand before the prompt became a menu keeps saving and rendering as typed, and the menu
  offers it last, marked "(not one of the choices)", because a PrimeFaces menu whose items omit its value posts its
  first option instead. The drawer lists such values when it opens (`content.unlistedChoices`).
- **`format`**, one value: `css-length` (a number with px, em, rem, %, vh or vw, or 0; `Placeholder.isLength`). Such
  a value is the only kind a template may write inside a `style` attribute: the renderer writes it only when it is a
  length and writes nothing otherwise (escaping alone would let `1px;position:fixed` restyle the page), and a save
  refuses a changed value that is not one. The Spacer and the Opening banner's width use it.

**Required** (owner, 2026-11-25): a section's Title is optional for every kind, and a rich-text prompt is never
required whatever its template says (`content.isRequired`, the one rule for the drawer's `required=` and
`validateRequired`; the template editor's Required box is disabled on a RICH_TEXT row). An untitled section is
named where sections are listed (Arrange sections, "Adding above ...") by its template's name and the first words
of its text (`titleOf`, `untitledLabel`). The drawer names the template an item is built on, adding and editing
alike ("Template: ...", `content.templateName`), with "Edit this template" for a template designer.

**Spacer** (`spacer`, STANDARD): empty room with `height` and `width` (css-length) and `display` (a menu: block,
inline-block, inline), all inside its `style`; `.tripSpacer` in `site.css` gives a blank height 20px and outlines it
in edit mode.

**Clone** (`content.cloneContent`): the Clone button beside a section's (or a group item's) Edit saves a copy right
after the original, a group's items copied beneath it, and the page reloads on the copy (`cloneLanding`). A group's
allow-list and size still apply; required prompts are not asked again (it copies what is stored), and a seeded
placeholder's copy keeps the seed as author and its "Show until", so it stays hidden until its first save, like the
original. A group's editor privileges are a contentAdmin's grant (`guardContainerConfig`): the copy keeps them only
when a contentAdmin clones it (a trip manager's clone of a group has no editors of its own). One recipe builds every
copy (`ContentCommands.copyOf`): the Clone button, a page's language version (which keeps them: the same page's
editors) and another trip's page (`TripPageCommands.clonePage`, as Clone).

**The bottom of a page**: once a page has a section, "Add section here" is drawn below the last one too
(`.addHereEnd`, adding at the end), and the page toolbar keeps only Arrange sections; an empty page keeps the
toolbar's Add section.

### Adding a section (2026-11-16)

The foundations of the Add flow's redesign. The layout built on them is the content drawer, the section after
this one.

- **What a template says about itself** (`ContentTemplate`, set on the template editor, never on an email
  template): `tags`, lower-case search words beyond the name and description (`TemplateCommands.normalizeTags`:
  trimmed, deduplicated, at most 12 of 30 characters; the editor's chips offer the typed word first, so one
  Enter takes a new tag, then the tags already in use, `completeTag`); `childOnly` ("Only inside a group": a
  leaf made for one group's row, such as a feature card); `fullWidth` ("Made for full-width pages": a band). All
  three are written to the row only when set, so a row from before them reads and re-saves unchanged, and
  `ContentTemplate.ser` was regenerated under the same `serialVersionUID`.
- **What is offered** (`getTemplateChoicesFor`): at page level everything the site may use EXCEPT `childOnly`
  templates; inside a group nothing changed (its allow-list, and a single choice still skips the picker). A
  `fullWidth` template is **never hidden** on a classic page: it renders there by design (`.ui-card .band`), and
  hiding it would take a capability away. `templateCards(section, pageFullWidth)` sorts those last on a classic
  page so a picker can list them as one trailing group; the HOST passes its own `fullWidth` ui:param to
  `contentSections.xhtml`, because the page is the authority (an organization's Editable Pages stay classic
  whatever `site.layout` says, so `BrandCommands.isFullWidth()` would be wrong there).
- **The card** (`ContentCommands.TemplateCard`, never stored in a scope): `kindLabel` is "" for a section
  template, **Group** for a container, **Live** for a programmatic type ("Section" used to name both the thing
  added and one kind of it); `tags`; `searchText` (lower-cased name, description and tags); `fullWidth`;
  `fields` (the prompts an author will fill in, a required one ending in " *", without the shared-site-only ones
  on an organization's site); `holds` (a group's allowed item templates by name and its limit).
- **A sample render for every kind** (`sampleRender`, the card's `html`): a STANDARD body with its prompts'
  sample values, and where a picture prompt has none the bundled neutral picture
  (`StarterTemplates.SAMPLE_PICTURE`, `medjugorje/webapp/resources/images/samplePicture.svg`). That path is
  site-relative, which a picture value may never be, so the SAMPLE render alone retypes such a prompt as a link
  (`requireLinkUrl`) on a copy of the template; a stored value still goes through `requireHttpUrl`. A CONTAINER
  renders its wrapper and row exactly as the page emits them (`ContentRenderer.containerBody` /
  `renderChildRow` / `fillContainerTokens`) around three sample items (fewer when `maxChildren` is lower) built
  on the first STANDARD template it admits, else its first live one, else plain text; the items' titles come
  from `StarterTemplates.sampleChildTitles`, not the template's name. A PROGRAMMATIC type answers
  `ProgrammaticContentTemplate.getSampleHtml()`: static markup in its fragment's own classes with made-up data
  (no token, no literal color; `ProgrammaticTypesTest` holds all six to that). A card never runs a live fragment.
- **A live preview with the site's real data** (`previewInstance(section, templateId)`): a transient instance of
  a live template, never stored and never put in a view, filled by the type's `previewValues(PreviewContext)`:
  the trip whose page the section is on, else the site's next public trip (for the schedule, one with public
  sessions); the first document the site's picker offers; the signed-in person; the first language the site
  lists a trip in; the recent albums when one qualifies. It answers null when there is nothing real to show (the
  preview is then the card's static sample, to be labeled "Sample data"), for a template that is not live or not
  this site's, and for a caller who may not edit the section. The preview renders it through
  `WEB-INF/contentLeaf.xhtml` (param `instance`), the ONE place a STANDARD or PROGRAMMATIC item's branches live;
  `contentSections.xhtml` includes it for a page section and for a group's child. **The leaf's contract** (the
  owner chose the in-place layout, 2026-11-16: the editor previews the UNSAVED working copy in the page through
  this include, for adding and for editing): render only, no edit affordance; nothing in it or in a fragment may
  assume the item is stored or sits inside the sections `ui:repeat`; a bean memo behind a fragment is keyed on
  what it shows (`personCard` keys on the values too, since a working copy's id and version stand still while
  its values change); and because the fragments carry fixed ids it is included once per naming container
  outside a repeat. A CONTAINER renders nothing through it: its title, wrapper and row are still
  `contentSections.xhtml`'s. Only the template being
  previewed should render live (one ajax update): many live previews at once are slow and heavy.
- **Insert where the author chooses**: "Add section here" above each section (edit mode, the page-level gate)
  sets `viewScope.insertBeforeId`, a scalar id that rides an `f:param`; the page toolbar's Add section still means
  "at the end" and clears it. Apply calls `saveNewContentAt(instance, beforeId)`: a NEW item takes that place's
  position before it is saved, and the rest of the section is renumbered through the existing version-silent
  `applyOrder`, with the new id spliced into the order by hand (a read right after a write may not list it). An
  existing item, a blank id and an id that is not in the section all save exactly as `saveContent` does.
- **Landing on what was added**: Apply reads `landingAnchor(instance, pageKey)` before the save (the new
  section's id, or the group's for an item added inside one; "" for an edit) into `viewScope.landOnId`, which
  the `reloadFlag` region carries to the browser. `tripApplyDone` puts it in the address as the fragment and
  reloads; each page section already has `<a id="{id}">`. A reload restores the scroll position, which would win
  over the fragment, so `tripLandOn` sets `history.scrollRestoration` to manual for that one load and
  `tripLanded` scrolls to the section and takes the fragment out of the address. Restoration goes back to auto
  only as the page is LEFT (`pagehide`): set back during the load, the browser at once returns the page to where
  it was before the reload (found in the container; `AddSectionPwIT` holds it in a short window). The edit-mode
  stylesheet marks the section (`a:target + .contentItem`: a ring in `--primary-color` that fades; with reduced
  motion nothing animates and the ring stays).
- **From the picker to the template manager**: `/admin/templates.jsf?new=1` opens the New Template dialog on
  arrival and `?edit=ID` a template the caller may author (`TemplateCommands.deepLinkTemplate`; anything else
  opens nothing). The way back is the page the link was on, from the Referer header
  (`TripUtilCommands.refererPath`, the profile page's pattern), so no return URL travels in a parameter; it is
  kept only for a deep link and shown as "Back to the page you were editing".
- **Usage counts** (`TemplateCommands.usageCounts(orgId)`): how many CURRENT items are built on each template,
  only an organization's pages in its scope (the item's organization is derived from its section exactly as
  the edit gate derives it). It is one uncached scan of the content table, the read the referenced-delete check
  makes, so it loads on the manager's **Show usage** click (a Used column) and is meant for a designer's "Used N
  times" click in the picker, never on open. No aggregate is cached: `caching.md` makes the DAO caches the
  shared layer, this table has no per-template index to invalidate, and the view keeps only the scalar map.

### The content drawer (2026-11-16)

The owner tried three working layouts in a throwaway prototype and chose "In place": adding and editing happen
in a panel at the side of the page, and the item is drawn IN the page, where it goes and at its real size. He
then asked for the preview to follow the form, field by field. It is `WEB-INF/contentDialog.xhtml` plus
`resources/trip-js/contentDrawer.js`; the look is `trip.css`, `.contentDrawer`.

- **Still one `p:dialog`** (`contentDlg`, not modal, pinned to the window's edge by CSS; `fitViewport` stays on, as for
  every dialog with fields), so every opener, `update=":form:contentDlg"` and `PF('contentDlg')` are unchanged. Its
  heading says what is happening (`content.drawerTitle`: Add a section / Add an item / Edit section / Edit item).
  `#form:dlgState` carries scalars for the script: the step, the working item's id, kind and newness, the target
  section, the page, the insertion point. `onShow` fires after every re-render; a command that hides an already
  re-rendered dialog (Cancel) gets no `onHide` and calls `tripDrawer.hidden()` itself.
- **No mask, so the page is locked by hand while a form is open.** The modal dialog's mask used to keep an editor
  away from every other opener; the drawer has none, and each opener re-renders it for another item, dropping what
  was typed without a word. While the form step is shown, `lockPage` makes the page's openers (`.contentEditBtn`,
  `.addHereBtn`, the Page Operations buttons) disabled, the way the site's other locks are (`disabled` plus
  `ui-state-disabled`, so they take the theme's disabled look and a screen reader hears them as unavailable), with a
  title saying to Apply or Cancel first. A watch keeps the clicked opener locked when PrimeFaces' `disableOnAjax`
  enables it again after its request. Apply or Cancel ends it. The list step locks nothing: nothing typed there is
  lost. `AddSectionPwIT`.
- **Step 1, the list** (`dlgPick`). Search (every word must be in the name, description or tags:
  `TemplateCard.searchText`), tag chips (the tags two or more offered templates share), a recently-used group
  (the last few template ids in `localStorage`, per browser; rows are moved, never copied) and, on a classic
  page, a trailing "Made for full-width pages" group: all in the browser, over the rows' data. Selecting a row
  only PREVIEWS it: its sample (an inert `template` element in the row) is drawn at the insertion point, and a
  live type then asks `pickPreview` for the site's real data (`content.previewInstance`; with none, the sample
  stays and its label says "sample data"). "Add this section" (`pickTemplate`) is what creates the working
  copy. On a page the insertion point moves a section at a time (`content.moveInsertPoint`; named by
  `content.insertLabel`); inside a group a new item goes at the group's end. A designer
  (`priv.checkHere('contentAdmin')`) also gets "Edit this template" and "New template" (the manager's deep
  links) and "How often is it used?" (`TemplateCommands.usedBy`, the content scan, on that click only).
- **Step 2, the form** (`dlgFill`), for a new item and for an edit alike. Every field change redraws the
  preview: `liveContent` processes the form with `contentPreview=1` (so a half-filled form still applies; nothing
  is saved) and re-renders ONLY the preview host, on each change and after a typing pause, since each redraw is
  a round trip. The rich-text editor writes its field and fires `input` on it at each change (`richEditor.js`,
  `flush`), so the drawer only listens (an untouched editor keeps its stored markup).
- **Examples while adding** (owner-confirmed): a prompt the author has not touched shows its example in the
  preview, so a new section does not collapse on the first keystroke; a touched prompt shows exactly what was
  typed, blank included; only typed values are ever saved. `content.liveHtml(working, touched, plain)` renders
  a NEW section template that way (the sample picture's lenient typing applies only to untouched picture
  prompts), and `content.liveInstance` lends a NEW live section the site's real preview values for the choices
  not made yet. "Hide example text" (`plain`) and every edit of an existing item show the working copy as it
  is. A group has no examples: its sample (adding) or its own block (editing) stays, and the script follows
  the Title field in the one heading that holds the title as plain text.
- **The preview host.** `#form:livePreview` is the ONE server-rendered preview, built with the page, parked in
  `#tripLivePark`, and rendering through `contentLeaf.xhtml` (or `requestScope.liveHtml`). The script MOVES
  that element into the ghost block in the page and PrimeFaces then updates it in place by id; it is outside
  `dlgFill` so its fragments are evaluated after the typed values are applied. Nothing but a request holds the
  item it shows. The script finds a stored item's block through its edit buttons' wrapper (`data-item-id`,
  rendered only for someone who may edit) and a group's Add row through `childAddBar`; an item being edited is
  hidden while its preview stands in its place, and closing the drawer puts the page back.
- **A Title the page does not show.** A plain section placed straight on a page has no slot for its title
  (`content.isTitleUnseen`), which the live preview makes obvious, so the field says "Used in Arrange sections
  and History; not shown on the page."
- Tests: `AddSectionPwIT` (search, tags, preview before create, the form followed by the page, edit and
  Cancel, recently used), `HomeContentPwIT`, `ContentStayOnLatestPwIT`; `PlaywrightApp.pickTemplate` is the one
  "pick a template" step.

### Colors in the rich-text editors (2026-11-16)

The color button of every rich-text editor offers the theme's colors and the organization's brand colors only, never
fixed hex values (`WEB-INF/richEditor.xhtml`, `resources/trip-js/richEditor.js`, `EditorColors`); Jodit's own hex box
and system color picker are hidden, and its habit of turning a picked color into hex is overridden so a variable is
applied as offered. Stored content therefore holds
`style="color: var(--status-danger, #b71c1c)"` or `var(--brand-<id>, #a07a2c)`: the variable follows the org's palette
and dark mode, the fallback is the org's light value at the time of writing. A MAIL template's body (and the mail
merge) is a `mailEditor` and gets the plain `#rrggbb`. `RichTextRules.normalize`, the mail cleaner
`RichTextHtml` (a color, size, alignment and indent) and `HtmlCheck` (the template and waiver checks, which change
nothing) keep these inline colors. Doc for the brand colors themselves: `org-admin.md`, "Brand colors".

## Typed HTML and the allowlist (2026-11, medjugorje#158)

What an author TYPES is never written to a page as stored. A rich-text value, a title that carries markup, a
speaker's biography, and outside this feature a trip's description and a to-do's details, all go through ONE
allowlist cleaner, `content/PageHtml` (the OWASP Java HTML Sanitizer), on every save and again where a page or the
API writes them out, so a row that reached storage some other way is still clean on the page.

- **Kept:** layout tags, classes, ids, tables, figures, pictures and media by http(s) or from this site, https
  frames, `svg`/`path` icons, links, comments, inline styles as typed, `style` blocks (only `@import` and a selector
  that reads the page are refused), and any `data-` attribute except a name the site's own pages use
  (`PageHtml.DATA_RESERVED`; the private repo's `DataAttributePolicyIT` keeps that list current).
- **Removed:** script, any `on...` attribute, form controls, `base`, `meta`, `link`, `srcdoc`, a script or
  `data:` address. A feature that needs a tag or attribute not kept must add it to the allowlist, with a test.
- **A title** is markup when it holds a `<` (`PageHtml.titleHasMarkup`, the one test save, render and migration
  share); a title whose `<` was only text is stored and shown as text.
- **The author is told.** `content/StripNotes` words each removal. The content drawer lists what a save would
  remove while the author types and Apply asks first (`ContentCommands.wouldRemove`, `applyFromDrawer`); the to-do
  page says it on save; a REST write names it in `X-Markup-Removed` (`docs/api-contract.md`).
- **A template BODY is different.** It is written as stored, tokens and all: the sanitizer rewrites `{{` and moves
  text out of tables, so a body cannot pass through it. That is why writing a template is the power to put script
  on a page. Cleaning what a template COMPOSES to, and a separate privilege for raw bodies, are the next stage
  (`docs/migrations/html-clean.md`, which also holds the one-time migration of rows stored before the rule).

## A container's row (the body of a CONTAINER template)

A container's body is **the row wrapped around EACH child**, not the container's own markup -- the
container is what iterates, so the layout of that iteration belongs to it and a child template never
needs to know how it is being listed. Its vocabulary is deliberately separate from `{{token}}`
(`CHILD_TOKEN` in `ContentRenderer`, not `TOKEN`), so no STANDARD template can declare a placeholder
named `child` and "Detect from body" never offers one:

| Token | Renders |
|-------|---------|
| `{{child}}` | where the child itself renders -- **required**; save is refused without it |
| `{{child:title}}` | the child instance's title, HTML-escaped (empty for a PROGRAMMATIC child, which titles itself from live data) |
| `{{child:id}}` | the child instance's id, escaped -- an anchor target |
| `{{child:index}}` | its 1-based position in the container |
| `{{children:start}}` / `{{children:end}}` | optional pair delimiting the repeated row; everything outside them is emitted ONCE, before and after the whole list |
| `{{container:title}}` | the container INSTANCE's own title, wherever the body puts it (plain text escaped, markup through the allowlist, blank as ""). A body that carries this slot renders NO separate heading above itself: `ContentCommands.renderTitle` answers "" for it, so the title is written exactly once. The band containers use it to center their heading inside the band |

The default, seeded and used whenever the body is blank, reproduces exactly what the page markup used to
hardcode, so adopting this changed no rendered byte:

```html
<div class="contentTitle">{{child:title}}</div>{{child}}
```

Rules worth knowing before changing this:

- **The row splits at `{{child}}`; the page emits the halves around the child's real components.** A
  child is a component tree -- a PROGRAMMATIC fragment, the three edit buttons -- so it can never be
  produced by string substitution the way a STANDARD body's tokens are. `ContentCommands.childRowBefore`
  / `childRowAfter` are the two EL calls; `ui:repeat`'s `varStatus.index` supplies the index.
- **A body that has lost its slot falls back to the default row.** Dropping every child silently is the
  one container failure an editor cannot see or diagnose, so it must not be reachable by mis-saving.
- **The row resolves the version the container instance renders with**, like every other template lookup:
  the version it PINNED, so editing the row changes nothing on a pinned container's page until it is moved
  to the new version through the version menu on its own edit dialog, or the template's current version for a
  container set to Stay on Latest (above). Making the latest the default was tried and refused: the
  unversioned lookup, `TemplateDAO.getTemplate(id)`, falls back to `getAllTemplates()`, which rescans the
  whole table whenever the cache is not authoritative, a live read per child per public page view. Stay on
  Latest reads its own cached field instead (`getLatestTemplate`); `RenderPathCacheTest` pins both, including
  a guard that fails if the unversioned lookup ever becomes cache-served.
- **CONTAINER bodies edit as raw HTML only** (`templateDialog.xhtml` hides the visual editor for the
  kind): a row is not prose, and the `{{child}}` marker must stay exactly where it is written.
- `.contentTitle:empty` is `display:none` in `trip.css` -- the row writes the heading unconditionally, so
  an untitled child (and every programmatic one) would otherwise leave an empty heading box.
- **The wrapper is a region, not two extra fields.** `beforeAll`/`afterAll` as their own columns would each
  hold an unbalanced fragment (`<ul>` … `</ul>`), which `HtmlCheck` must reject on its own --
  so they would have to skip validation or be validated concatenated, at which point they are one
  document. As a region the whole body validates normally and `ContentTemplate` gains no fields:

  ```html
  <ul class="eventList">
  {{children:start}}
    <li><h4 class="contentTitle">{{child:title}}</h4>{{child}}</li>
  {{children:end}}
  </ul>
  ```

- **Half a region is refused at save and ignored at render.** One marker without its partner, a reversed
  pair, or a second pair is a save error; if such a body somehow reaches the page it degrades to the
  built-in row with NO wrapper, because emitting an unclosed `<ul>` would corrupt everything after it.

## Live sections with a layout (2026-11-25)

The owner: "Why doesn't `trip-details` have a template that I can edit to adjust the layout?" The trip page's two live
sections, Trip Details (`trip-details`) and Trip Detail Pictures (`trip-pictures`), and since 2026-11-26 a File (`file`,
below), are PROGRAMMATIC templates whose body is a LAYOUT: HTML around the trip's (or the document's) own values, edited
in the template editor as raw HTML (like a container's row, with a reference line of every word the section knows and a
field help card). Every other programmatic type still has no body (`ProgrammaticContentTemplate.layoutVocabulary()`
answers null, and `TemplateCommands` blanks the body on save as before).

- **Blank is the built-in look.** A blank body draws `WEB-INF/tripTopFacts.xhtml` / `tripTopPictures.xhtml` exactly
  as before layouts; the embed draws neither, whatever the body says. The starters
  (`StarterTemplates.TRIP_DETAILS_LAYOUT`, `TRIP_PICTURES_LAYOUT`) reproduce those fragments element for element
  (`StarterTemplatesTest`), so filling a blank body with the starter changes no page.
- **The words** (`content/TripLayout`, filled by the `tripLayout` bean, `TripLayoutCommands`). Colon forms, which a
  hand-written template's `{{name}}` pattern never matches:
  - values `{{trip:dates}}`, `{{trip:destination}}`, `{{trip:director}}`, `{{trip:guide}}`, `{{trip:coordinators}}`,
    `{{trip:price}}`, `{{trip:flyerUrl}}` (screened, `TripCommands.flyerUrlOf`), and the labels in the READER's
    language, `{{trip:directorLabel}}`, `{{trip:guideLabel}}`, `{{trip:coordinatorsLabel}}`, `{{trip:priceLabel}}`,
    `{{trip:priceTerms}}`, `{{trip:flyerLabel}}`; for pictures `{{trip:cover}}` (the cover `<img>` exactly as the
    built-in hero draws it, srcset, sizes, width and height), `{{trip:coverUrl}}`, `{{trip:coverAlt}}` and
    `{{trip:coverColor}}` (the placeholder color, only when it is a hex color). Every value is HTML-escaped; a
    missing one draws nothing;
  - blocks `{{#trip:x}} ... {{/trip:x}}`, kept only when `x` holds: one per fact (`TripPageCommands.FACTS`: the trip
    has it AND the section's "Facts shown" picks it), and for pictures `single` (one picture, or Show: cover only)
    and `gallery` (two or more, not cover only). Blocks nest, eight deep;
  - `{{trip:gallery}}`, pictures only: a COMPONENT SLOT. The fragment cuts the filled body there and draws the real
    `p:galleria` (`WEB-INF/tripTopGallery.xhtml`) between the halves, the way a group's row is cut at `{{child}}`;
    it is drawn only when the `gallery` block holds, so a stray slot never makes a one-picture gallery;
  - `{{tone}}`, the section's own Tone property (below), escaped and screened to the tones.
  A save refuses an unknown word, an unknown or unbalanced block, blocks nested too deep, a second gallery slot, a
  gallery slot in the facts layout, and (as for every body) markup a browser would repair (`HtmlCheck.templateProblem`).
  A typed trip value is never read again as a word: the section's properties are filled first, then the values.
- **The layout follows the template's newest version** (`ContentCommands.layoutBody`): the row is resolved as if it
  were Stay on Latest, through the same organization guard (`mayFollow`), so a layout edit reaches every trip page
  with no row touched. The row's own pin still decides its properties, as before. Cached reads only: one versioned
  field and the latest field (`RenderPathCacheTest`), once per section per request (`RequestMemo`).
- **A template of this type under another id** draws through the same fragment, so `TripLayoutCommands` is asked per
  fragment (`facts`, `pictures`), never by template id.
- **Tone** (the owner: "the same background options as the Heading and text template"): both sections and the Spacer
  take the bands' Tone menu (`StarterTemplates.TONES`: plain, tint, dark, transparent). Blank adds no background, so
  no page changes until an editor picks one. The class goes on the section's wrapper (`tripLayout.withTone`) whether
  or not a layout is set; `site.css` shares each band tone's ground and text with a `.tone-*` class, without the
  band's padding (`.tone-dark` keeps the white text and inherited links).
- **Installing.** `install-starter-templates.sh` ships the starter layouts and the toned Spacer for a new
  environment; `refresh-starter-templates.py` (rule 5) gives a stored blank layout the starter one, every version,
  and moves a Spacer still holding its shipped body to the toned one. Until it runs, nothing on any page changes.
- **A File's layout** (2026-11-26; the owner: "A file is a file (not an item in a list w/ it's own bullet)", and its
  name, size and description should be laid out by a template like Trip Details). The `file` type draws no bullet:
  blank, it is the document's name as a link and its size, nothing around them, and the LIST that holds it owns the
  bullet. The Documents groups are `document-list` (a CONTAINER starter admitting only File items, its row
  `<li>{{child}}</li>` inside a `qopBullets` list; `StarterTemplates.DOCUMENT_LIST_BODY`), never the generic
  `container`. Its words carry their own prefix (`TripLayout.Vocabulary.prefix`, `file`; a save refuses a trip
  section's word in a File layout and the reverse): `{{file:title}}`, `{{file:url}}`, `{{file:size}}`,
  `{{file:description}}` and the block `{{#file:description}} ... {{/file:description}}`, kept only when the document
  has one; no Tone. Filled by `FileLayoutCommands` (`#{fileLayout}`), drawn by `ptypes/file.xhtml`. A File whose
  document is hidden or deleted draws nothing, and its group then leaves out the row too
  (`ProgrammaticContentTemplate.drawsNothing`, read by `ContentCommands.childRowBefore`/`After`), or a hidden
  document would leave an empty bullet. The template editor explains a File's words and help with their own keys
  (`contentTemplate.layoutPrefix`). Production moved to it with `../medjugorje/scripts/document-list-template.sh`.
- **Not built:** an organization's own layout. A "Customize" copy is never what a content page renders (only mail
  reads `resolveForOrg`), so a per-organization layout needs that first.

## Bands and the full-width layout (2026-09)

A **band** is a section that spans the whole window with its content capped at a readable 1200px column
-- the shape of a marketing page. The band family is eleven starter templates (`StarterTemplates`, ids
never renamed), all rendered by the same engine as everything else, and all usable inside the classic
card too (there a band simply fills the card, with rounded corners). Since 2026-11-16 the nine `band-*`
starters carry plain names (Opening banner, Text beside a picture, Call to action, Quote, Heading and text,
Feature cards, Key numbers, Questions and answers, Logo strip) and `fullWidth`, the two leaves are `childOnly`,
and the old words (hero, testimonial, stats, faq) are tags:

| Template | Kind | Placeholders / children |
|----------|------|-------------------------|
| `band-hero` | STANDARD | `eyebrow`, `headline`*, `subheadline`, `primaryText`/`primaryUrl`, `secondaryText`/`secondaryUrl`, `imageUrl`/`imageAlt`, `imageSize` (menu: small, medium, large, half; blank = large) and `imageWidth` (a CSS length that overrides the size). No tone: always the deep gradient of the site's colors |
| `band-split` | STANDARD | `tone`, `side` (menu: left, right), `imageUrl`/`imageAlt`, `icon` (PrimeIcons class, shown when there is no picture), `heading`*, `body` (RICH_TEXT), `linkText`/`linkUrl` |
| `band-cta` | STANDARD | `tone`, `heading`*, `text` (RICH_TEXT), `buttonText`*, `buttonUrl`*, `note` |
| `band-testimonial` | STANDARD | `tone`, `quote` (RICH_TEXT), `name`, `role`, `photoUrl` |
| `band-text` | STANDARD | `tone`, `heading`, `body` (RICH_TEXT) |
| `feature-card` | STANDARD (leaf) | `icon`, `text` (RICH_TEXT); its HEADING is the instance title, written by the Features row |
| `stat-item` | STANDARD (leaf) | `value`*, `label`* |
| `band-features` | CONTAINER | children `feature-card` only; a responsive card grid under `{{container:title}}` |
| `band-stats` | CONTAINER | children `stat-item` only, max 4; dark tone |
| `band-faq` | CONTAINER | children `text-only` only: the child's TITLE is the question, its body the answer, as `<details>` rows |
| `band-logos` | CONTAINER | children `image` only; a centered strip of small logos |

`tone` is a menu (since 2026-11-25; a TEXT prompt before) whose value becomes a class (`band-plain`,
`band-tint`, `band-dark`, `band-transparent`; blank = plain), escaped like any text. Transparent paints no ground
of its own, so the page's ground or background picture shows through and the text keeps the theme's color. A
tone typed by hand before the menu existed keeps rendering as typed and is offered in the menu as it is ("Fixed
choices and lengths", below). The banner's typed width is written as the picture's `flex-basis`, so a percentage
is a share of the banner; a blank width leaves exactly `style="flex-basis:"`, which `site.css` reads as "no width
given". Every optional part is written so that a blank value leaves an EMPTY
element (`<a class="cta">…</a>` with no whitespace inside, `img[src=""]`, `data-icon=""`) that the
stylesheet hides -- the reason the hero's buttons are one tight anchor each. Single-child allow-lists mean
a band's Add button skips the template picker. The stylesheet is `medjugorje/webapp/resources/css/site.css`
("band vocabulary"): colors are theme variables, except that the hero and the dark tone MIX the palette's
own color toward near-black (`color-mix`) and write white on it, because neither `--primary-dark-color`
nor `--primary-color-text` can be trusted at 4.5:1 against a band on every palette
(`OrgSubdomainPwIT.bandsStayReadableOnEveryTone` measures the marketing page). Modern-browser CSS
(`:has()`, `color-mix()`) is used deliberately.

**Link URLs.** A `Placeholder.Type.URL` value (a link target, as opposed to an image or video source) may be
an absolute http(s) URL, a site-relative path (`/account/createAccount.jsf`) or a page fragment
(`#<section id>`); `ContentRenderer.requireLinkUrl` renders anything else -- another scheme, a
protocol-relative `//host` or `/\host` -- as "". `IMAGE_URL` and `VIDEO_URL` keep `requireHttpUrl`. A link that is
exactly a trip's own registration page (`/trip/joinTrip.jsf?trip=<uuid>`, what the trip-page starters store) renders
as that trip's `tripPage.registerUrl` (`TripPageCommands.contentLink`, the hook `ContentCommands.render` hands
the renderer), so an EXTERNAL trip's Register buttons leave for its External Trip URL whenever it is set. Decided
at render, never at seeding: the URL may be added or changed after the page exists. Only a trip the site may show
(`ListingScope.reachable`) is resolved; another organization's trip id stays as stored, as does a failed lookup.

**The full-width layout.** `BrandCommands.isFullWidth()` is true on the product's marketing host and on an
org site whose `site.layout` setting is `full-width` (`org-admin.md`, "Branding"); `homePage.xhtml` passes
it to `mainTemplate.xhtml` as the `fullWidth` ui:param, which builds (`c:if`, build time -- the
`mainContent` insert may only be built once) either the classic card-and-sidebar grid or a bare
`.layoutFull` holding the sections and the footer, and `template.xhtml` widens `.layout-content` with
`layout-content-full`. Only the home page, a TRIP's page (`trip/tripDetails.xhtml`, which reads the trip
from the request parameter because the branch is built before its initPage; `conference.md`) and, on the marketing
host only, an Editable Page (`page.xhtml` passes `site.marketingSite`: the platform org's Pricing and Why UniteTrip are
bands; an org's Editable Pages keep the classic card whatever its `site.layout` says; `MarketingSitePagesPwIT`) change;
every other page, and every shared host, renders the classic markup byte for byte
(`OrgSubdomainPwIT.theClassicLayoutIsUnchanged`). In edit mode the dashed section frames wrap each band, which then
stays inside its frame rather than reaching the window's edges.

**A page's own stylesheet** (every content page since 2026-11-26; `PageStylesheets` as `#{pageStyles}`). Each
page's stylesheet is a setting OF THAT PAGE, stored with whoever owns it: a trip's page on the trip
(`Trip.page.stylesheetUrl`, also the Trip Page dialog's menu; `conference.md`, "Look and feel"); an organization's
pages, its home included, on the organization row by page name (`Organization.pageStylesheets`; the home page has no
Editable Pages entry, so it cannot live there; the Editable Pages settings dialog's Stylesheet menu writes it too);
the shared site's own pages (its home and `page:shared:*`) in the `site.pageStylesheets` setting, by page key. Any
other key (a container, a trip's sub-page, the placeholder marketing host's pages, a trip with no organization) has
no owner and so no stylesheet: a lookup miss never falls through to the shared library (`PageStylesheets.ownerOf`).
A page links only a `text/css` item of its OWNER's library: an organization's, or for a shared page the shared
library (`stylesheets/{slug}.css`, no organization, kept by site admins). The `<link data-trip-stylesheet>` in the
head of `contentPage.xhtml` and `homePage.xhtml` comes from `pageStyles.linkForSitePage(name)`: cached reads,
never a throw, and nothing for a stored value that names anything else (the shared setting is hand-editable on the
Settings page). Trip pages keep their own head link (`tripPage.lookFor`).

The edit toolbar of every content page carries the same Styles button (`WEB-INF/pageStyles.xhtml`, shown to whoever
edits the stylesheets of the page owner's library, in edit mode or not: `TripStylesheets.canEditPage`), which opens
the live editor on the page the host pinned as `viewScope.pageKey`; `?styles=new|true` opens it on arrival
(`firstOpen`). Besides Save, the editor has **Use on this page** (save, then link it: `saveAndUse` through
`PageStylesheets.link`, for whoever may change the page's settings: the org's admins, site admins for shared pages;
the page's link is swapped in the browser at once), **Stop using**, and **Delete**, which refuses while any page links
the sheet (a "Used by" line names them first) and otherwise removes the row and the file; the Media page's Delete
refuses a linked sheet the same way (`PageStylesheets.usersInWords`, also on a rename and on the API, which answers
409), so no page ever links a missing file. Any page can also still carry styles as a content section: an org-scoped
STANDARD template whose body is a `<link rel="stylesheet">` or a `<style>` block, placed once on the page it styles;
authoring that is the template manager's raw-HTML trust (`contentAdmin`). Either way the rules belong under
`.contentItem` so the site's
own chrome is never restyled.

**The marketing page** (`page:unitetrip-home`) is seeded from `MarketingPageBootstrap`: a hero, a
six-card Features band, three split bands, a four-item Stats band, a five-question FAQ and a dark call to
action, with deterministic row ids (`UUID.nameUUIDFromBytes` over a slot name) so the script's conditional
puts, the local seed (`FakeData`, so `www.localhost` renders it) and the hero's `#features` anchor all
agree. No testimonial, logo or number is seeded: every claim names a shipped feature.

## The include contract (`WEB-INF/contentSections.xhtml`)

Host page's `initPage` captures into viewScope BEFORE the include's tree builds: `pageKey` (a viewScope
value, NOT a ui:param -- jsft command scripts resolve at invoke time where Facelets aliases no longer
exist), `editMode`, `pageSections = content.getForView(pageKey, editMode)`, `childLists =
content.childrenFor(pageSections, editMode)`. The include renders sections + all edit affordances and
pulls in `contentDialog.xhtml` and `arrangeDialog.xhtml` itself. See `trip/index.xhtml` for the reference
host.

The include holds **no per-item layout of its own**: a container child is wrapped by
`content.childRowBefore(sec, child, childStatus.index)` / `childRowAfter(...)`, which come from the
container template's row (above). Markup added here instead is markup a content editor cannot reach.

## Performance rule

**The public render path performs no live DynamoDB reads** (beyond first-request cache warming):
section listings and child lists are cached partitions, template lookups hit the versioned template cache
(`id#vN`, or `id#latest` for a Stay on Latest instance), media/trips come from their own caches.
`RenderPathCacheTest` enforces zero persistence reads against a warm cache -- keep it passing. Uncached point
reads (`getContent`, container resolution in `canEdit`, `titleOf`) are edit-mode/admin paths only.

## Versioning / undo (unchanged from v1)

Row = current + up to `content.versions.retained` previous versions; saves bump the version with a
lost-update guard; restore re-saves a snapshot as a NEW version. Reading the history (`getHistory`) and
restoring from it both ask `mayEdit` on the instance's section again: the buttons render behind `canEdit`,
but the id rides the postback, and old versions hold text the page no longer shows anyone. Deleting a container
cascades to its children -- each via the DAO's surgical `removeOne` (NEVER `invalidate()`: local mode's cache
is the datastore).

## Bootstrap (production, one-time -- scripts in `medjugorje/scripts/`)

1. Privilege rows (hand-created in `/admin/editPrivs.jsf`): `contentAdmin` (writes template BODIES,
   which reach pages as stored: grant like script access; typed values go through the allowlist),
   `eventAdmin`, `mediaAdmin`.
2. `install-starter-templates.sh` -- every starter (`StarterTemplates.IDS`; JSON generated from `StarterTemplates`
   by the test-scope `BootstrapScriptJson` tool: the originals, the Spacer, the seven MAIL bodies, the four reminder
   texts, the eleven bands, the conference types, the trip page's pictures and details types, and the language
   versions). It never overwrites a row; `refresh-starter-templates.py` brings rows installed earlier up to date in
   place, and since 2026-11-16 that includes the plain band names (only
   where the stored name is still the shipped one), the tags and the two flags (only where the row has none), and
   since 2026-11-25 the band menus (tone, side) and the Opening banner's picture size, each only where the stored
   prompt or body is still exactly what the starter shipped, and the two live sections' starter layouts (only into a
   blank body) with the Spacer's Tone (only where its body is still the shipped one).
3. `bootstrap-home-v2.sh` -- the `page:trip-index` skeleton (JSON generated from `V2PageBootstrap`);
   `--purge-v1` deletes the retired `home.*` rows. New rows appear on their own within ~5 minutes (the
   cache refresh merges); only DELETES need the Settings page's "Clear caches" button (the refresh never
   removes).
4. `bootstrap-marketing-page.sh` -- the `page:unitetrip-home` rows (JSON generated from
   `MarketingPageBootstrap`, same tool); run AFTER step 2, since the rows pin v1 of the band templates.
   `update-marketing-site.sh` then brings an already-installed page up to date (a row still holding copy the
   bootstrap once shipped gets the current copy as its next version; an edited row is left alone) and installs the
   platform org's Editable Pages (`MarketingPageBootstrap.sitePages()`, the resource
`content/marketing-site-pages.json`). Everything else is edited in place: `/trip/index.jsf` → Edit page. **Organization
pages need no script**: assigning the org's subdomain seeds its starter page (see "Organization sites" above).

## Retired v1 behaviors

Documents' 92-day auto-age (a doc's visibility = media `hidden` + the child's own "Show until"),
`home.docs.maxAgeDays`, the select-existing/`assignToSlot` dialog flow (superseded by the File picker), and the
`home.intro`/`home.events`/`home.reflection` section keys.

`home.banner.*` is NOT retired: the announcement banner moved to `/index.xhtml` (the live landing host) on
2026-09-15 when `medjugorje/index.xhtml` was retired to a redirect. It fills a `banner` slot that
`WEB-INF/homePage.xhtml` inserts at the top of `mainContent`, so it appears on the live site and
deliberately not on the `/trip/index.jsf` staging host.
