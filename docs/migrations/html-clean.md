# Cleaning hand-authored page HTML (medjugorje#158)

Status 2026-11-21: stage 1's code is in place. Every field named below goes through the cleaner
(`content/PageHtml`) on save and again where a page or the API writes it out: content sections' rich-text values
and markup titles (also on restore), trip descriptions, to-do details, speaker bios, and the page rows seeded
from a description. The migration commands exist and have been rehearsed on production; no row has been
rewritten yet, so rows stored earlier are cleaned only as shown.

## Why

Trip descriptions, the rich-text values and markup titles of content sections, to-do details and speaker bios
were written to pages unescaped and uncleaned, and people below the top tier can write them: a trip manager
(anyone who creates a trip), a global `eventAdmin` or `mediaAdmin` on the landing page, any REST caller with
`tripMgr`. One login cookie covers every `*.unitetrip.com` site and a shared host serves many organizations from
one origin, so a script in one trip's page acts as whoever opens it, in every organization.

The 2026-11-17 production scan found no script, no event handler and no script address anywhere (55 descriptions,
137 rich-text values, 34 template bodies, 84 to-do details). Authors use HTML, CSS, images, video frames, a few
`<style>` blocks and small inline icons. So the hole can be closed without costing anyone what they use.

## Owner decisions (2026-11-17)

1. **Clean by role.** Everything a non-developer types goes through ONE allowlist cleaner, on save and on render.
   The model is WordPress multisite's: only the top tier writes raw HTML.
2. **A new global-only privilege, `webDeveloper` ("Web Developer"),** holds raw HTML and script in template
   bodies. `contentAdmin` (global and per organization) keeps authoring templates and loses script. A site admin
   holds it implicitly, as with every privilege. Not built yet (stage 2).
3. **The OWASP Java HTML Sanitizer** does the parsing and writing for the new wide profile. The hand-written
   `action/RichTextHtml` modes (reminders, notes, event notes) stay as they are.
4. **A frame may show any https site,** for everyone (the documented behavior before this work).
5. **Stored rows are migrated proactively,** not left to be cleaned only as they are shown, so the migration is
   exercised and tested.
6. **Every trip is judged on its own.** A trip with ANY detected difference, in its text or in how it looks, is
   not changed until the owner has seen a screenshot of that difference and approved it. No blanket approval. An
   approval is tied to the stored and the cleaned text it was given for. If the owner says no, the fix is to widen
   the allowlist until the trip comes out the same (never drop a capability to simplify), and every trip is then
   judged again. The same evaluation gates the DEPLOY, because cleaning on render changes what every trip shows
   the moment the code is live.

7. **Decisions of 2026-11-21.**
   - A `<style>` block is allowed in typed HTML and is NOT confined to its own section: the people who can edit
     these pages are granted the role (an organization's admins, `addTrip`, a trip's managers, the landing page's
     editors), and advanced authors restyle parts of the page outside their section on purpose. Two rules
     are refused and nothing else: `@import`, and a selector that tests a form field's `value` (`PageCss.block`).
   - Cleaning happens in the editor's live preview, and a notice names exactly what is being removed and why
     (`content/StripNotes`), while the author can still go back and change it. Apply ASKS before it removes
     anything ("Go back and change it" or "Remove it and save", `ContentCommands.applyFromDrawer`); nothing is
     ever stripped silently, or later. BUILT for the content drawer 2026-11-21 (`ContentStripNoticePwIT`).
   - To-do details: the notice on save. REST: the API stores and returns the cleaned text and lists what was
     removed.
   - Trip `04aaff2b` ("2026 Ago: Vengan y Vean") is APPROVED to lose its pasted document's wrapper tags (`html`,
     `head`, `meta`, `title`, `body`); the pictures match. The approval is for the stored and cleaned text of the
     2026-11-17 evaluation and lapses if either changes. It LAPSED the same day: the cleaner began to write `@`,
     `=` and `+` plainly, so the cleaned text is no longer the text that was approved (same removals, different
     spelling). The owner approved the new cleaned text the same day
     (stored SHA-256 `3869e1e2e354ccda...`, cleaned `027361d74755dd90...`).

8. **Pictures, 2026-11-21.** All 100 units compared: 97 the same. Three home pages did not compare as identical
   and the owner approved each as unchanged by cleaning after seeing its picture: organizations `1d88a054`,
   `3c9d71b8` and `5f0c2a4e`. None loses markup; the pixels that differ are a browser scaling a photograph
   differently from one rendering to the next (for `5f0c2a4e`, between two pictures of the STORED page). Like
   every approval, these are for the stored and cleaned text of that run.

## What the cleaner keeps and drops

`content/PageHtml` (its class comment is the reference; `PageHtmlTest` holds the attack list and every production
construct). Kept: layout tags, classes, ids (never one a page owns or one shaped like a Faces client id), `aria-`
attributes, tables, figures, images by http(s) or from this site, `video`, `audio` and `picture` by the image's
address rule, https frames with only harmless features in `allow`, `svg` and `path` icons, links by http(s),
mailto, tel, sms or within this site, a link's own `rel`, inline styles, and `style` blocks. Dropped: script, any
`on...` attribute, form controls, `base`, `meta`, `link`, `srcdoc`, a script or `data:` address, an address that
hides another host (`//host`, `/\host`).

CSS is the author's (owner, 2026-11-21). An inline `style` attribute is kept exactly as typed: it holds
declarations only, and nothing in it can leave the attribute. A `style` block is kept as typed and is not confined
to its section; `content/PageCss` takes out only a rule that loads another stylesheet (`@import`) and a rule whose
selector reads the page (it tests a form field's `value`, or names a `data-` attribute the site's own pages use),
and reports each. The library's own CSS schema is not used: it edits inside
a value (`margin:-10px auto 0 -5px` came out as `margin:auto 0`) and reports nothing it removes.

Any `data-` attribute is the author's too, except a name a script on the site's pages acts on
(`PageHtml.DATA_RESERVED` and its prefixes; PrimeFaces runs the text of `data-pfconfirmcommand` as script). The
private repo's `DataAttributePolicyIT` fails when a page starts to use a name an author can still type.

Comments are kept, exactly as typed (owner's decision, 2026-11-17; `content/PageComments`). Authors park text in
them: 19 trips hold a commented-out notice or paragraph, and a sanitizer throws comments away. A comment's text is
not cleaned, so it is kept only where a browser reads it as a comment and nothing else: never one that could end
early (`<!-->`, `<!--->`, `--!>`), never an old conditional comment (`[if ...]`), never inside an attribute value
or a frame. One between the items of a list or the rows of a table moves into the next text that follows, because
the library keeps no text there. A comment that cannot be kept is dropped and reported like anything else.

A template body never goes through `PageHtml`: the library rewrites `{{` and moves text out of a table, so tokens
and region markers do not survive. Stage 2 cleans what a template COMPOSES to instead.

## Where an author is told

Nothing is stripped silently, and never later (owner, 2026-11-21). `content/StripNotes` words each removal: the
exact tag, attribute, rule or comment, how often, and why.

- **Content drawer:** a notice under the form, redrawn with the preview as the author types; Apply asks before it
  removes anything ("Go back and change it" or "Remove it and save"), and the OK covers the list shown.
- **To-do details:** a notice on save, kept across the page's redirect.
- **REST:** the response to a write carries one `X-Markup-Removed` header value per removal
  (`BaseResource.cleanedHtml`, `docs/api-contract.md`), and its body holds the cleaned text.

## Evaluating (read-only)

`migrate/HtmlClean` judges every in-scope stored value and groups the verdicts by UNIT: a trip (its description,
every section on its page including children of containers, its to-do details), or a page of no trip (one page
key). Verdicts: IDENTICAL (cleaning returns the stored text), EQUIVALENT (spelled differently, same content by
`content/HtmlShape`, nothing dropped), CHANGED (anything else). A title is in scope only when it holds `<`, and a
value only when its template declares it RICH_TEXT; anything else is escaped where it is shown.

```sh
cd trip && mvn install                                   # the CLI runs from trip/target/ROOT
../medjugorje/scripts/html-clean.sh plan   --out <dir>   # plan.json, plan.txt; exit 2 when a unit is CHANGED
../medjugorje/scripts/html-clean.sh visual --out <dir>   # <dir>/visual/: visual.json, diff/<unit>/ pictures
```

`plan` answers for the text. `visual` (in the private repo's browser-test module) answers for the look: it
fetches each unit's real page once, signed out, serves that response back to a browser with and without each
cleaned value swapped in, and compares the pictures pixel by pixel at 1280, 768 and 375 wide. A value the
signed-out page does not show is rendered on its own inside the page's stylesheets. It cannot see signed-in views
or Dark Mode, and it never looks inside a frame. A page that moves by itself (a photo strip, a carousel) is
compared a second time with its scripts off; identical there, it is reported SAME_SCRIPTS_OFF, a verdict of its
own that is never counted as SAME. One thing is set aside, and noted when it is: inside a picture element whose address,
place and size are the same in both renderings, a pixel a few levels off (up to 16 of 255) is the decoder's grain,
not a change; a browser does not scale one photograph to the same pixels every time.

`<dir>` holds production content: the script creates it for its operator alone, and it is never committed.

Run of 2026-11-21, with `style` blocks kept and plain spellings: 100 units; 27 IDENTICAL, 72 EQUIVALENT, 1 CHANGED
(trip `04aaff2b`).
The pictures were compared again on that run: see decision 8.

First run, 2026-11-17 (text): 95 units; 23 IDENTICAL, 70 EQUIVALENT, 2 CHANGED. The two: trip `04aaff2b` ("2026 Ago:
Vengan y Vean"), whose description is a whole pasted HTML document (`html`, `head`, `meta`, `title`, `body` go);
and one organization's home page, where a rich-text value holds a `<style>` block.

## Migrating the stored rows

`migrate/HtmlCleanMigration` rewrites each stored value to its cleaned form, one unit at a time, and holds the
owner's rule in code. Every unit with anything to rewrite has a FINGERPRINT, a hash over each value that would
change (its stored and its cleaned text). A unit is written only when the owner approved exactly that fingerprint
(`approvals.json`), or when its text is the same content respelled AND the evaluation in the run's directory
still describes it (same fingerprint in `plan.json`) AND that evaluation's pictures found it the SAME
(`visual/visual.json`). Anything else stops the apply at that unit, with nothing of it written. An edit to the
stored text or a change to the cleaner moves the fingerprint, so an old approval or an old picture comparison stops
counting by itself.

```sh
../medjugorje/scripts/html-clean.sh approve  --out <dir> --unit KEY --words "what the owner said"
../medjugorje/scripts/html-clean.sh rehearse --out <dir>           # reads only; the whole apply, in memory
../medjugorje/scripts/html-clean.sh apply    --out <dir> --apply   # WRITES; stops at the first unit not cleared
../medjugorje/scripts/html-clean.sh verify   --out <dir>           # reads only
../medjugorje/scripts/html-clean.sh rollback --out <dir> --apply   # WRITES; puts back applied.json's rows
```

- **Order.** `plan`, `visual` and the owner's approvals come BEFORE the deploy: the picture comparison swaps each
  cleaned value into the page as it is served, which only works while the page still writes values as stored.
  Then deploy, so that no uncleaned row can be saved behind the migration; then `rehearse`, `apply`, `verify`. (An
  edit made in between moves that unit's fingerprint, and the apply stops there.)
- **A row is written as text** (`dynamo/RawRows`): read as a JSON tree, only the cleaned fields replaced, written
  back only if the row still holds what was read. A row saved by someone meanwhile is refused and reported, never
  overwritten. Content rows' undo history is cleaned too. A row a script stored in another JSON spelling (four in
  production hold `\u00f1` where the app writes the letter) comes back in the app's spelling, the same data.
- **Backups.** `<dir>/before/` gets each row as it was BEFORE its write; `applied.json` lists every row written,
  with both texts, and is what `rollback` reads. Point-in-time recovery on the three tables is the second net.
- **When apply stops.** Show the owner that unit's difference (`plan.txt`, the pictures under `visual/diff/`),
  record their yes with `approve`, run `apply` again: it goes on from where it stopped.
- **Caches.** The run has the shared cache off and touches none; the script then drops the app's `trip`, `todo`
  and `content` caches (with `TRIP_APP_URL` and `TRIP_ADMIN_EMAIL` set), or asks for "Clear caches".
- **Done means** `verify` says cleaning anything stored returns it as it is.

Rehearsal on production, 2026-11-21 (read-only, with the owner's four approvals): 121 rows to write (47 trips, 49
content, 25 to-dos), no unit stops, and the rehearsed result verifies. Before any apply, 133 stored values would
still be changed by cleaning. The run's directory is `html-clean-migration/` in the workspace root (not a
repository, mode 700).

## After the migration (2026-11-21)

Applied on production that day: 121 rows (47 trips, 49 content rows, 25 to-dos), verified. The pre-push review of
the stage had found two holes in the cleaner, which the owner chose to ship with and fix straight after: a comment
or `style` block typed inside an attribute's name, and a `style` block inside `svg`, each put back where a
browser reads it as markup. Both are closed (nothing is put back inside a tag or inside `svg`), and
`PageHtmlTest`'s attack list holds the reviewers' inputs.

## Still to come

- Stage 1, what is left: the deploy, then the apply itself (owner-run), with the production snapshots around both.
- Stage 2: `webDeveloper`, the template save rule (refuse what the cleaner would drop unless the caller holds it,
  then stamp the version), cleaning of composed output.
