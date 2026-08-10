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
| `CONTAINER` | `allowedChildTemplateIds` (null/empty = any non-container), `maxChildren` (null = unlimited) | optional WYSIWYG title, optional `editorPrivileges`, optional per-instance `allowedChildTemplateIds` | title via `renderContainerTitle`, then its CHILDREN in order |
| `PROGRAMMATIC` | `programmaticTypeId` naming a registered type | the type's NVP property values | the type's Facelets fragment (full PrimeFaces behavior) |

Rows written before v2 carry no kind and read as STANDARD (`getKind()` folds null; `setKind` stores
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
`placeholders` at creation, so the dialog's form generation and version pinning work exactly as for
STANDARD. On the page, `contentSections.xhtml` emits one statically-included fragment per registered type
behind a `rendered=` guard — the ONE place build-time `c:forEach` is used, safe because the registry never
changes at runtime.

## Rendering, editing, privileges

- `#{content}` helpers drive the include: `getForView`, `childrenFor`, `getKind`, `typeId`, `render`
  (STANDARD only; "" otherwise), `renderTitle`, `isVisibleNow`, `getTemplateChoicesFor`,
  `autoStartContent`, `getChoices`, `idsFor`, `titleOf`, `canEdit`, `canEditPage`, `applyOrder`,
  `frameClass`, `completeEditorPriv`, `getEditorPrivNamesJson`.
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
  markup-bearing titles. Failures surface as growl messages and keep the dialog open.
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

## The include contract (`WEB-INF/contentSections.xhtml`)

Host page's `initPage` captures into viewScope BEFORE the include's tree builds: `pageKey` (a viewScope
value, NOT a ui:param — jsft command scripts resolve at invoke time where Facelets aliases no longer
exist), `editMode`, `pageSections = content.getForView(pageKey, editMode)`, `childLists =
content.childrenFor(pageSections, editMode)`. The include renders sections + all edit affordances and
pulls in `contentDialog.xhtml` and `arrangeDialog.xhtml` itself. See `trip/index.xhtml` for the reference
host.

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
2. `install-starter-templates.sh` — the SEVEN starters (JSON generated from `StarterTemplates`).
3. `bootstrap-home-v2.sh` — the `page:trip-index` skeleton (JSON generated from `V2PageBootstrap`);
   `--purge-v1` deletes the retired `home.*` rows. New rows appear on their own within ~5 minutes (the
   cache refresh merges); only DELETES need the Settings page's "Clear caches" button (the refresh never
   removes).
Everything else is edited in place: `/trip/index.jsf` → Edit page.

## Retired v1 behaviors

Documents' 92-day auto-age (a doc's visibility = media `hidden` + the child's own "Show until"),
`home.banner.*` and `home.docs.maxAgeDays` on this page (still used by the OLD `medjugorje/index.xhtml`
until promotion), the select-existing/`assignToSlot` dialog flow (superseded by the File picker), and the
`home.intro`/`home.events`/`home.reflection` section keys.
