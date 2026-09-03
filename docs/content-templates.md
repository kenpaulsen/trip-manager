# Content templates v2 — fully template-driven pages

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
| `MAIL` | an EMAIL body with `{{token}}` placeholders; the template NAME doubles as the subject line | none — mail templates have no instances | never on a page; rendered by `MailCommands.renderManagedTemplateForOrg` (resolved per organization, below), tokens filled by JAVA only (no EL — runtime-editable EL would be code execution) |

MAIL templates are excluded from every content picker; the shipped ones are `registration-received`,
`registration-approved`, `support-request`, `payment-confirmation`, and `org-invite` (the org-admin
"invite by email" flow -- see `docs/org-admin.md`). They are edited on the
same `/admin/templates.jsf` manager as everything else (contentAdmin): the page has a kind filter
(`TemplateCommands.getTemplates(kind)`, fails open to the full list on blank/bogus values) and the
admin menu carries a dedicated "Email Templates" entry deep-linking `?kind=MAIL` — the discoverability
fix for email copy hiding behind the "Content Templates" label. Rows written before v2 carry
no kind and read as STANDARD (`getKind()` folds null; `setKind` stores
STANDARD as null so JSON round trips stay equal).

## Section-key conventions

- **A page is a section key**: `page:trip-index` holds the landing page's ordered sections.
- **A container's children live under the container INSTANCE's id** as their section key. Container ids
  double as the page's anchors (`<a id="...">`): the landing page's containers are `events`, `reflection`,
  `docs`, so `#events`/`#docs` deep links keep working.
- Depth is capped at page → container → leaf: containers may not allow or contain containers (validated in
  `saveTemplate` and `saveContent`, and excluded from every picker).
- A STANDARD **child**'s title renders as its heading (the Events look). A page-level STANDARD section
  deliberately does NOT render its title (the title block and intro would sprout headings) — a heading at
  page level is either markup in the body or the reflection pattern: a titleless container whose child
  carries the heading.

## Organization sites (per-org pages, 2026-09)

The same engine renders more than one site; **which page renders is a fact about the request's
hostname**, resolved once per request into a `SiteContext` (`org.paulsens.trip.site`, carried on
`RequestContext`) and read by pages as `#{site}`:

- **Page keys per site** (`SiteCommands.getPageKey()`): the shared site renders `page:trip-index`; an
  organization's own site (`{slug}.unitetrip.com`) renders `page:org:{orgUUID}:home` (the UUID, never the
  slug — a site admin may rename the slug and the page must survive it); the product's marketing host
  renders `page:unitetrip-home`. `OrgPageBootstrap` owns these keys. `WEB-INF/homePage.xhtml` captures the
  key from the bean at initPage on every request and never stores it anywhere a later request could read it
  back (see `SiteContext`'s hard rule: never in sessionScope/viewScope).
- **The starter page** (`OrgPageBootstrap.rows`): a welcome section naming the org (obviously placeholder
  text), an English `pilgrimages` section, and a `photo-albums` section — "less is more". Seeded through the
  normal DAO save path by `OrgCommands.ensureHomePage`, **exactly once per org** (the org row records
  `homePageSeededAt`): on the subdomain's first assignment, or lazily from the org dashboard for an org
  slugged earlier. An org that empties its page keeps it empty; a hand-authored page is never overwritten.
  Instance ids are minted UUIDs — the shared page's guessable ids (`events`, `docs`) are anchors and
  child-section keys and can never be reused by a second page. No script: onboarding an org is an admin-UI
  flow by design.
- **What a site lists is decided by ONE rule, `site/ListingScope`** (every public listing — the page
  sections, the Trips menu, the sidebar, the countdown cards, the media API's slot browsing):
  - an ORG site lists only its own content, whatever the instance's properties say and whoever is looking
    (org-less legacy content belongs to nobody and does not show there);
  - a SHARED site shows a hosted org's content only when BOTH sides agree — the section's curation list
    (`includeOrgs`, a `MULTI_CHOICE` property on the `pilgrimages` and `photo-albums` types, declared once in
    `SharedSiteOrgChoices`) AND the org's own `Organization.allowSharedSites` (org-admin checkbox on the
    profile; null = allow, false = never). With NO list a shared site shows the orgs that have no site of
    their own (today's tenants), so a newly hosted org stays off `visitqueenofpeace.com` until a site admin
    picks it — no data migration. Org-less legacy content keeps listing.
  - `MULTI_CHOICE` stores its picks comma-separated in the ONE string value; the dialog binds a
    `p:selectCheckboxMenu` to `editContent.listValues[name]` (`ContentInstance.getListValues()`, a computed
    write-through view — never a second field). The prompt is hidden on org sites, where it does nothing.
  - Media: `MediaItem.orgId` (null = site-level; every pre-existing row) decides DISCOVERY —
    `MediaCommands.discoverable`: an org's items only on its site, site-level items only on shared sites, in
    the library, pickers (`FileType`), the Documents slot and `getAll/getCurated`. Chat albums are scoped by
    their TRIP (an old null-org chat photo still shows in its album); chat rows are stamped with the trip's
    org at send, library uploads with the request's site. URLs are not restricted, discovery is.
- **Template scope**: `ContentTemplate.orgId` — null = site-level/shared, else one organization's own.
  Sharing is an AUTHORING-time choice: the Add dialog (`getTemplateChoicesFor`) offers an org site the shared
  templates plus its own (`SiteContext.isSiteOf`), any other site only the shared ones; an instance then
  pins whichever it chose, so rendering never consults the field and the zero-live-read rule below is
  untouched. The template manager's Scope column/menu (`TemplateCommands.scopeChoices`/`scopeLabel`) sets
  it; a blank menu choice saves as null; an unknown org is refused.
  **Customizing a shared template for one org is a COPY** (`TemplateCommands.customize(id, orgId)`, the
  manager's "Customize" button in an org scope): the clone is org-scoped at version 1 with the same
  kind/body/placeholders; the shared row stays shared and read-only to the org's editor
  (Edit/History/Delete render only where `mayAuthor` says so). **EMAIL templates are copied like every
  other kind** — see "Per-organization email copy" below. Details: `org-admin.md` "Org-site editors".
- **Editing** an org's page happens on the org's site with the same edit mode. Authorization is
  privilege-only: site admin, global `contentAdmin`, or `contentAdmin` scoped to that org (see
  `org-admin.md` "Org-site editors") — org admins do not edit content by being admins. The org of a
  section is derived from the section (page key, or the container's page), so an org grant never reaches
  the shared or marketing page.
- An empty page shows a plain notice (`homePage.xhtml`): the marketing host's "coming soon" until
  `bootstrap-marketing-page.sh` has seeded it (see "Bands and the full-width layout"), or "this site is
  being set up" on an org host whose page is not seeded yet.

## Per-organization email copy (2026-09-02)

An organization customizes the email sent **on its behalf** the same way it customizes page content: by
copying the shared template. What makes that mean something for MAIL is that senders no longer resolve an
email template by its fixed id alone.

**Resolution order — one method, `TemplateCommands.resolveForOrg(templateId, orgId)`**, and everything goes
through it (the manager page and every sender, so what an editor sees and what goes out cannot disagree):

1. the organization's own copy of `templateId`, when it has one;
2. otherwise the shared row under `templateId`;
3. nothing (senders treat that as "do not send" and log it) when neither exists.

A row sitting under the copy's id that belongs to a DIFFERENT organization is ignored — resolution checks
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
| `OrgCommands.sendPaymentTestMail` / the payment-mail preview | the trip's — a test send must resolve exactly what the live one does |
| `SupportChatCommands` (`support-request`) | **none, deliberately**: it notifies the SITE's support admins, on nobody's behalf |

`MailCommands.sendManagedTemplateForOrg` / `renderManagedTemplateForOrg` /
`renderManagedSubjectForOrg` / `renderManagedBodyForOrg` are the org-aware entry points; the org-less
`sendManagedTemplate` / `renderManagedTemplate` forms remain and delegate with a null org (the shared row).

**One row per use case.** In an organization's scope (`/admin/templates.jsf?orgId=…` from the org hub, or
the org's own site) `getTemplatesFor(kind, orgId)` shows each use case ONCE — two rows for one email would
be a standing invitation to edit the one that is never sent:

- not customized: the shared row, read-only, offering **Customize** (which copies it and opens the copy for
  editing in one click);
- customized: the org's own row, editable, badged **Customized**, with **View site default** (the shared
  subject and body, read-only) and **Revert to site default** (`revertToSiteDefault`, behind a
  `p:confirmDialog`, which deletes only the org's own copy — an org template with no site default behind it
  is refused rather than silently destroyed).

The SITE-WIDE page (no `orgId`, no org host) is the **site defaults** page and hides org-owned rows
entirely: a site admin editing "Registration received (Acme)" from there would be fixing one tenant while
believing they were fixing everyone.

**Naming.** A copy's name gains a "({org})" suffix for every kind EXCEPT MAIL, where the name IS the
subject line and the suffix would ship in the subject of every email the org sends. Nothing is lost: in the
org's own list the Scope column and the Customized badge say whose row it is.

## Programmatic types

`ProgrammaticContentTemplate` (in `org.paulsens.trip.content`, EL-friendly getter naming):
`getTypeId()`, `getDisplayName()`, `getDescription()`, `getProperties()` (a `List<Placeholder>` — the NVP
prompts the content dialog shows), `getFragmentPath()` (`/WEB-INF/ptypes/*.xhtml` in the medjugorje repo),
and `choicesFor(prop)` feeding `Placeholder.Type.CHOICE` dropdowns (e.g. the File type's media picker, the
pilgrimage language menu). Registry: `ProgrammaticTypes.ALL` (KnownSettings pattern). Shipped types:
`pilgrimages`, `photo-albums`, `file` (one media item by id — title/size/URL read LIVE from the cached
media table; hidden or deleted media renders nothing).

Fragments are **pure render-time** (accordions/galleria/rows only — no build-time tags) and read the
hosting instance as `#{instance}`. A template of kind PROGRAMMATIC copies its type's properties into
`placeholders` on every save, so version pinning and the manager's placeholder table work exactly as for
STANDARD — **but the stored list is advisory at most: every reader takes the registry's LIVE property list**
(`ProgrammaticTypes.placeholdersOf(template)`, used by the content dialog via `content.placeholdersOf(instance)`,
by `createContent`, RICH_TEXT validation/normalization, the version-switch migrator, and the manager's
`getTemplate` copies). The stored copy is a snapshot of the registry as it was when the row was written:
production's `pilgrimages`/`photo-albums` starters predate the `includeOrgs` property, and the dialog once
iterated the row, so the "Organizations shown" menu never appeared there (2026-09-01). A property added to
a type therefore needs NO re-install and no data migration; a row whose type is no longer registered falls
back to what it stored. On the page, `contentSections.xhtml` emits one statically-included fragment per
registered type behind a `rendered=` guard — the ONE place build-time `c:forEach` is used, safe because the
registry never changes at runtime.

## Rendering, editing, privileges

- `#{content}` helpers drive the include: `getForView`, `childrenFor`, `getKind`, `typeId`, `render`
  (STANDARD only; "" otherwise), `renderTitle`, `isVisibleNow`, `getTemplateChoicesFor`,
  `autoStartContent`, `getChoices`, `idsFor`, `titleOf`, `canEdit`, `canEditPage`, `applyOrder`,
  `frameClass`, `completeEditorPriv`, `getEditorPrivNamesJson`, `getTemplateVersions`, `pinnedVersion`,
  `versionLabel`, `isTemplateOutdated`, `retargetTemplateVersion`.
- **Privileges**: contentAdmin (or a site admin) edits everything. A container instance may name
  `editorPrivileges` (only contentAdmin can set them — the save path guards the field): holders of ANY
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
  (`autoStartContent`) — the Documents container (Files only) opens straight on the media picker.
- **Edit-mode visuals** (emitted by the include, not host pages): each section is wrapped — buttons
  included — in a rotating dashed color frame (`content.frameClass(index)`), item-scoped buttons render
  outlined (`contentItemBtn`) vs the filled section/page buttons, and the page toolbar is titled "Page
  Operations".
- **HTML validation**: `HtmlFragmentValidator` (structural: balance, nesting, quotes, comments; void and
  raw-text elements understood) gates `saveTemplate` bodies and `saveContent` RICH_TEXT values and
  markup-bearing titles. Failures surface as growl messages and keep the dialog open. It shares its tag
  scanner with `RichTextRules` (`HtmlTags`), so the two never disagree about where a `<p>` begins.
- **RICH_TEXT is BLOCK html, and that is not negotiable**: Quill's document model is a list of lines and
  every line is a block element, so a one-line caption comes back as `<p>…</p>` plus a trailing empty
  paragraph, and alignment arrives as a `ql-align-*` CLASS that only works where Quill's stylesheet is
  loaded. Two consequences, each handled in its own place:
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
- **Changing an instance's template version**: the content dialog shows a "Template version" menu for saved
  instances whose template has more than one retained version, with the pinned one selected, the newest
  marked, and an amber note when a newer one exists. Switching calls `retargetTemplateVersion`, which runs
  `TemplateValueMigrator` over the values: same name is kept, then same label (case-insensitive), then a lone
  same-type survivor on each side; anything left is DROPPED and named in the growl. New placeholders arrive
  declared-but-empty. Nothing is stored until Apply, so Cancel abandons a bad migration. The version menu's
  remoteCommand processes the WHOLE form so typed-but-unsaved values migrate too.
- **Growl messages must carry everything in the SUMMARY**: `template.xhtml` renders message details only for
  the URL-parameter messages (`hasDetail`), so a detail-only explanation is invisible to the user. Found by
  clicking through the container; no test can see it.
- **Raw HTML in the WYSIWYG editors**: every `p:textEditor` on the site carries an "HTML" toolbar button
  that swaps Quill for a textarea (`WEB-INF/quillEditor.xhtml`, patched onto the widget prototype, so
  editors created later by ajax get it too). While in Source mode the textarea writes straight into the
  widget's hidden input, so markup Quill has no format for (wrapper divs, iframes, inline styles,
  data-attributes) reaches the server VERBATIM. Switching back to visual re-parses through Quill and
  therefore simplifies such markup, so that pass runs only when the text actually changed. A template
  BODY additionally has the model-bound "Edit raw HTML" mode in `templateDialog` (no Quill parse at all
  in either direction) -- the right choice for structural template bodies.
- **Reordering**: the shared arrange dialog (`p:orderList`, drag or buttons) calls
  `applyOrder(section, orderedIds)` → `ContentDAO.reorderContent`, a **version-silent** in-place position
  rewrite (no version bump, no history churn; an editor dialog open across a reorder re-saves its stale
  position — accepted).
- **Structural edits reload the page** (`tripApplyDone`/`tripReloadIfSaved` + the `:form:reloadFlag`
  contract in `contentSections.xhtml`): the include iterates viewScope-frozen lists, so in-place ajax
  cannot reflect adds/deletes/reorders.

## A container's row (the body of a CONTAINER template)

A container's body is **the row wrapped around EACH child**, not the container's own markup — the
container is what iterates, so the layout of that iteration belongs to it and a child template never
needs to know how it is being listed. Its vocabulary is deliberately separate from `{{token}}`
(`CHILD_TOKEN` in `ContentRenderer`, not `TOKEN`), so no STANDARD template can declare a placeholder
named `child` and "Detect from body" never offers one:

| Token | Renders |
|-------|---------|
| `{{child}}` | where the child itself renders — **required**; save is refused without it |
| `{{child:title}}` | the child instance's title, HTML-escaped (empty for a PROGRAMMATIC child, which titles itself from live data) |
| `{{child:id}}` | the child instance's id, escaped — an anchor target |
| `{{child:index}}` | its 1-based position in the container |
| `{{children:start}}` / `{{children:end}}` | optional pair delimiting the repeated row; everything outside them is emitted ONCE, before and after the whole list |
| `{{container:title}}` | the container INSTANCE's own title, wherever the body puts it (plain text escaped, markup verbatim, blank as ""). A body that carries this slot renders NO separate heading above itself: `ContentCommands.renderTitle` answers "" for it, so the title is written exactly once. The band containers use it to center their heading inside the band |

The default, seeded and used whenever the body is blank, reproduces exactly what the page markup used to
hardcode, so adopting this changed no rendered byte:

```html
<div class="contentTitle">{{child:title}}</div>{{child}}
```

Rules worth knowing before changing this:

- **The row splits at `{{child}}`; the page emits the halves around the child's real components.** A
  child is a component tree — a PROGRAMMATIC fragment, the three edit buttons — so it can never be
  produced by string substitution the way a STANDARD body's tokens are. `ContentCommands.childRowBefore`
  / `childRowAfter` are the two EL calls; `ui:repeat`'s `varStatus.index` supplies the index.
- **A body that has lost its slot falls back to the default row.** Dropping every child silently is the
  one container failure an editor cannot see or diagnose, so it must not be reachable by mis-saving.
- **The row resolves the version the container instance PINNED**, like every other template lookup, so
  editing the row changes nothing on a page until each container is moved to the new version through the
  version menu on its own edit dialog. Reading the LATEST would be friendlier (a container has no values
  of its own to protect) and was tried — but the only unversioned lookup, `TemplateDAO.getTemplate(id)`,
  falls back to `getAllTemplates()`, which rescans the whole table whenever the cache is not
  authoritative. That is a live read per child per public page view; `RenderPathCacheTest` now pins both
  halves of this, including a guard that fails if the unversioned lookup ever becomes cache-served.
- **CONTAINER bodies edit as raw HTML only** (`templateDialog.xhtml` hides the visual editor for the
  kind): Quill rewrites structural markup and would move the `{{child}}` marker out of place.
- `.contentTitle:empty` is `display:none` in `trip.css` — the row writes the heading unconditionally, so
  an untitled child (and every programmatic one) would otherwise leave an empty heading box.
- **The wrapper is a region, not two extra fields.** `beforeAll`/`afterAll` as their own columns would each
  hold an unbalanced fragment (`<ul>` … `</ul>`), which `HtmlFragmentValidator` must reject on its own —
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

## Bands and the full-width layout (2026-09)

A **band** is a section that spans the whole window with its content capped at a readable 1200px column
-- the shape of a marketing page. The band family is eleven starter templates (`StarterTemplates`, ids
never renamed), all rendered by the same engine as everything else, and all usable inside the classic
card too (there a band simply fills the card, with rounded corners):

| Template | Kind | Placeholders / children |
|----------|------|-------------------------|
| `band-hero` | STANDARD | `eyebrow`, `headline`*, `subheadline`, `primaryText`/`primaryUrl`, `secondaryText`/`secondaryUrl`, `imageUrl`/`imageAlt`. No tone: always the deep gradient of the site's colors |
| `band-split` | STANDARD | `tone`, `side` (left/right), `imageUrl`/`imageAlt`, `icon` (PrimeIcons class, shown when there is no picture), `heading`*, `body`* (RICH_TEXT), `linkText`/`linkUrl` |
| `band-cta` | STANDARD | `tone`, `heading`*, `text` (RICH_TEXT), `buttonText`*, `buttonUrl`*, `note` |
| `band-testimonial` | STANDARD | `tone`, `quote`* (RICH_TEXT), `name`, `role`, `photoUrl` |
| `band-text` | STANDARD | `tone`, `heading`, `body`* (RICH_TEXT) |
| `feature-card` | STANDARD (leaf) | `icon`, `text`* (RICH_TEXT); its HEADING is the instance title, written by the Features row |
| `stat-item` | STANDARD (leaf) | `value`*, `label`* |
| `band-features` | CONTAINER | children `feature-card` only; a responsive card grid under `{{container:title}}` |
| `band-stats` | CONTAINER | children `stat-item` only, max 4; dark tone |
| `band-faq` | CONTAINER | children `text-only` only: the child's TITLE is the question, its body the answer, as `<details>` rows |
| `band-logos` | CONTAINER | children `image` only; a centered strip of small logos |

`tone` is a TEXT prompt whose value becomes a class (`band-plain`, `band-tint`, `band-dark`; blank = plain),
so it is escaped like any text. Every optional part is written so that a blank value leaves an EMPTY
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
protocol-relative `//host` or `/\host` -- as "". `IMAGE_URL` and `VIDEO_URL` keep `requireHttpUrl`.

**The full-width layout.** `BrandCommands.isFullWidth()` is true on the product's marketing host and on an
org site whose `site.layout` setting is `full-width` (`org-admin.md`, "Branding"); `homePage.xhtml` passes
it to `mainTemplate.xhtml` as the `fullWidth` ui:param, which builds (`c:if`, build time -- the
`mainContent` insert may only be built once) either the classic card-and-sidebar grid or a bare
`.layoutFull` holding the sections and the footer, and `template.xhtml` widens `.layout-content` with
`layout-content-full`. Only the home page changes; every other page, and every shared host, renders the
classic markup byte for byte (`OrgSubdomainPwIT.theClassicLayoutIsUnchanged`). In edit mode the dashed
section frames wrap each band, which then stays inside its frame rather than reaching the window's edges.

**The marketing page** (`page:unitetrip-home`) is seeded from `MarketingPageBootstrap`: a hero, a
six-card Features band, three split bands, a four-item Stats band, a five-question FAQ and a dark call to
action, with deterministic row ids (`UUID.nameUUIDFromBytes` over a slot name) so the script's conditional
puts, the local seed (`FakeData`, so `www.localhost` renders it) and the hero's `#features` anchor all
agree. No testimonial, logo or number is seeded: every claim names a shipped feature.

## The include contract (`WEB-INF/contentSections.xhtml`)

Host page's `initPage` captures into viewScope BEFORE the include's tree builds: `pageKey` (a viewScope
value, NOT a ui:param — jsft command scripts resolve at invoke time where Facelets aliases no longer
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
(`id#vN`), media/trips come from their own caches. `RenderPathCacheTest` enforces zero persistence reads
against a warm cache — keep it passing. Uncached point reads (`getContent`, container resolution in
`canEdit`, `titleOf`) are edit-mode/admin paths only.

## Versioning / undo (unchanged from v1)

Row = current + up to `content.versions.retained` previous versions; saves bump the version with a
lost-update guard; restore re-saves a snapshot as a NEW version. Deleting a container cascades to its
children — each via the DAO's surgical `removeOne` (NEVER `invalidate()`: local mode's cache is the
datastore).

## Bootstrap (production, one-time — scripts in `medjugorje/scripts/`)

1. Privilege rows (hand-created in `/admin/editPrivs.jsf`): `contentAdmin` (raw-HTML power — grant like
   script access), `eventAdmin`, `mediaAdmin`.
2. `install-starter-templates.sh` — the TWENTY-THREE starters (JSON generated from `StarterTemplates` by
   the test-scope `BootstrapScriptJson` tool; the twelve originals plus the eleven bands).
3. `bootstrap-home-v2.sh` — the `page:trip-index` skeleton (JSON generated from `V2PageBootstrap`);
   `--purge-v1` deletes the retired `home.*` rows. New rows appear on their own within ~5 minutes (the
   cache refresh merges); only DELETES need the Settings page's "Clear caches" button (the refresh never
   removes).
4. `bootstrap-marketing-page.sh` — the `page:unitetrip-home` rows (JSON generated from
   `MarketingPageBootstrap`, same tool); run AFTER step 2, since the rows pin v1 of the band templates.
Everything else is edited in place: `/trip/index.jsf` → Edit page. **Organization pages need no
script**: assigning the org's subdomain seeds its starter page (see "Organization sites" above).

## Retired v1 behaviors

Documents' 92-day auto-age (a doc's visibility = media `hidden` + the child's own "Show until"),
`home.banner.*` and `home.docs.maxAgeDays` on this page (still used by the OLD `medjugorje/index.xhtml`
until promotion), the select-existing/`assignToSlot` dialog flow (superseded by the File picker), and the
`home.intro`/`home.events`/`home.reflection` section keys.
