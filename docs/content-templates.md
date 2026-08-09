# Content templates — admin-editable page sections

The mechanism behind the data-driven landing page's Events and Introduction sections, built to be reused by
any page that wants admin-editable content without a deploy.

## Model

Two DynamoDB tables (both PK `id`, single `content` JSON column, PAY_PER_REQUEST, RETAIN + deletion
protection, created by CDK in `AppStack`):

| Table | Row type | Holds |
|-------|----------|-------|
| `templates` | `TemplateRecord` | the current `ContentTemplate` + up to *n* previous versions |
| `content` | `ContentRecord` | the current `ContentInstance` + up to *n* previous versions |

- **`ContentTemplate`** — a reusable rich-HTML body containing `{{name}}` tokens, plus the `Placeholder`
  declarations (name, type, label, hint, required) that describe them. Versions are 1-based and only move
  forward.
- **`ContentInstance`** — a filled template placed in a page *section* (`home.events`, `home.intro`,
  `home.reflection`, …): `templateId` + **`templateVersion`** (pinned — a later template edit never reshapes
  published content), a `values` map (placeholder name → raw value), an optional `eventDate` (the instance
  stops rendering publicly the moment it passes; nothing is deleted), and a `position` for ordering. The
  `title` doubles as the item's **on-page heading in sections that display one** (Events renders it bold,
  Reflection as its `h3`); the hosting page decides, so a heading-less section (the intro) just ignores it.
- **Versioning / undo** — every save bumps the version, pushes the old current into `previous`, and trims
  history to the `content.versions.retained` setting (default 5). *Restore* re-saves an old snapshot as a
  NEW version; history stays linear. A save whose version does not match the stored current is refused
  (lost-update guard).

## Caching

Both DAOs sit on `PartitionScanCache` with a **5-minute soft TTL**: reads always answer from cache
(stale-while-revalidate — an expired read still answers immediately and refreshes in the background), so a
public page render never waits on DynamoDB.

- The **template cache field is `id + "#v" + version`** and the loader flattens every retained version into
  its own entry: content pinned to v3 keeps finding v3 in cache after the template moves to v4, and a cached
  body can never be paired with values authored against a different version.
- The content cache is **partitioned by section** — rendering a section is one hash read.
- Deletes are surgical (`removeOne`); rows deleted behind the app's back (the cleanup script) are NOT healed
  by the background refresh, which merges and never removes — clear caches from the admin Settings page.

## Rendering and the security model

`ContentRenderer` substitutes values into the body; **escaping is decided by the declared placeholder type**,
because the page emits the result with `escape="false"`:

| Type | Editor widget | Rendering rule |
|------|---------------|----------------|
| `TEXT` | text field | HTML-escaped |
| `RICH_TEXT` | `p:textEditor` (Quill) | verbatim |
| `URL`, `IMAGE_URL` | text field | must parse as http(s) or renders empty; attribute-escaped |
| `VIDEO_URL` | text field | YouTube forms normalized to `youtube.com/embed/{id}`; other http(s) pass through |

Unknown tokens and missing values render as the empty string — a public page never leaks `{{token}}`.

There is deliberately **no HTML sanitizer**: template bodies and RICH_TEXT values are trusted-admin content,
consistent with the rest of the codebase (`secure="false"` editors, `escape="false"` outputs). The privilege
model is the containment: granting **`contentAdmin` is equivalent to granting script execution on public
pages** — it authors raw-HTML templates. **`eventAdmin`** only fills placeholder values on the `home.events`
section, and typed escaping limits it to what RICH_TEXT placeholders allow. If less-trusted editors are ever
added, add OWASP java-html-sanitizer to the RICH_TEXT path first.

## Beans and pages

- `#{content}` (`ContentCommands`) — `getForSection('home.events')` (visible only), `render(c)`,
  `createContent(section, templateId)`, `saveContent`, `deleteContent`, `getHistory`, `restoreContent`,
  `canEdit(section, userId)`. Mutations re-check privileges server-side; a page's `rendered=` only hides
  buttons. Site admins pass `canEdit` outright (matching `Caller.has`'s short-circuit).
- `#{contentTemplate}` (`TemplateCommands`) — template CRUD + history/restore, `installStarterTemplates()`
  (idempotent), `detectPlaceholders`. Deleting a template is refused while ANY instance (current or history)
  references it.
- Admin UI: `/admin/templates.jsf` (template manager, `contentAdmin`), `WEB-INF/templateDialog.xhtml`
  (WYSIWYG body editor with a raw-HTML toggle — Quill simplifies iframes/wrapper divs, which structural
  templates need), and the reusable `WEB-INF/contentDialog.xhtml` (template picker → placeholder-generated
  form → preview) included by any page hosting sections. Picking a template advances the dialog immediately
  (no confirm click); both dialogs are `fitViewport` so tall forms scroll inside them. Every `p:textEditor`
  on these pages has Quill's toolbar image action re-handled to insert an image **by URL** — the stock
  handler file-picks and inlines a base64 blob, which would bloat the stored row (DynamoDB's 400KB item cap)
  and defeat CDN caching.

## Production bootstrap (one-time, by hand)

1. `cdk deploy TripApp` (user-run) — creates the `templates` and `content` tables and task-role grants.
2. Enable PITR on both tables (the monthly AWS Backup plan picks them up automatically; PITR does not).
3. In `/admin/editPrivs.jsf`, create two GLOBAL privilege rows: `contentAdmin` and `eventAdmin`, and add the
   right people. (An unreferenced privilege name silently answers false — the rows must exist.)
4. Run `medjugorje/scripts/install-starter-templates.sh` (youtube-video, image, text-only) — idempotent
   conditional puts that never overwrite an edited starter; the row JSON is generated from
   `StarterTemplates.java`, so regenerate rather than hand-editing if the starters change. The admin page's
   **Install starter templates** button does the same thing from the UI, but the script is the canonical
   from-scratch path (`medjugorje/setup-instructions.txt` walks the whole bootstrap).

## Housekeeping

`medjugorje/scripts/cleanup-templates.sh` deletes templates nothing references (dry-run by default,
`--delete` to act, starters kept unless `--include-starters`). See the cache note above before expecting the
manager page to reflect a scripted delete.
