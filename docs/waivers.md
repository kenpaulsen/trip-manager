# Trip documents ("waivers")

A trip can require every traveler to agree to one or more documents before they register: a liability waiver, a
code of conduct, a photo release. "Waiver" is used loosely for all of them. Read this before touching
`WaiverCommands`, `WaiverDAO`, `Trip.waivers`, the registration gate, or the apps' registration flow.

## Owner decisions (2026-10-27)

| Decision | Answer |
|---|---|
| Format | Rich text (the site's Quill editor, stored HTML). The traveler can download the SIGNED copy as a PDF. |
| Ownership | Documents belong to an ORGANIZATION (its dashboard's Documents card); a trip may only use its own org's documents. |
| Versioning | Every save publishes the next version; no published text is ever rewritten or pruned. A trip PINS a version (default: the newest when added). Publishing a new version moves no trip. |
| Moving a pin | The trip admin decides at that moment whether signatures of the earlier version still count (`acceptOlder`, **default yes**) or everyone must sign again. |
| Enforcement | A traveler cannot be REGISTERED until every required document is agreed. A document added or re-versioned later never un-registers anyone: it is flagged to them and to the admins. |
| Signature | Two checkboxes, ALWAYS both, for yourself too: "I have read and agree to {title}." and "I attest that I am {name}, or that I am authorized to sign on {name}'s behalf." |

Assumptions recorded with the plan: a signature is per TRIP (signing for trip A does not count for trip B); only
"required" exists (a trip that does not need a document does not add it); the signer is always the signed-in
person (identity never changes, the family-acting rule), the traveler is the subject.

## The model

| Type | Where | What |
|---|---|---|
| `WaiverDocument` | `org_waivers` (PK `orgId`, SK `id`) | `title`, `retired`, `versions` (ALL of them, oldest first), `createdBy`, `created`, `version` (the row's optimistic counter, unrelated to the document's version numbers) |
| `WaiverVersion` | inline | `number` (1..n), `title`, `bodyHtml` (sanitized at publish), `sha256` of the body, `publishedBy`, `publishedAt`, `note` |
| `TripWaiver` | `Trip.waivers` | `waiverId`, `version` (the pin), `acceptOlder` (absent reads true), `addedBy`, `added` |
| `WaiverAcceptance` | `waiver_acceptances` (PK `tripId`, SK `personId#waiverId#00005`) | the traveler (`personId`), the document and version, its `title` and `sha256`, the signer (`signerId`, `signerName`, `signerEmail`), the traveler's `subjectName`, both attestations, `acceptedAt`, `client` (WEB/IOS/ANDROID/API), `ip`, `userAgent`, `orgId` |

`org_waivers` is the `PaymentProcessorDAO` shape: the `(orgId, id)` read IS the tenancy check, a per-org
`PartitionCache`, the `FamilyDAO` conditional put (version 0 creates, any other replaces that exact version, a lost
race throws with the version restored), and a 350 KB row guard (every version counts toward it). The content
template history is NOT reused: it ages old versions out (`content.versions.retained`).

`waiver_acceptances` is WRITE-ONCE and UNCACHED, like the audit table: `attribute_not_exists` on the key, no update
or delete path in code, and an IAM grant of PutItem/Query/GetItem only (`AppStack`). A second signature of the same
version answers the first row. Names and the signer's email are captured at signing so the record reads correctly
after a profile changes or a person is erased. Neither a trip delete nor an account deletion touches it. The audit
trail (`AuditAction.WAIVER`) is written beside it and is never the record.

## The standing rule (`WaiverCommands.standing` / `requirements`)

Per pinned document, for one traveler: `SIGNED` (an acceptance of the pinned version or a newer one), `SIGNED_OLDER`
(only an earlier version, and the pin accepts older), `OUTDATED` (only an earlier version, and it does not),
`MISSING`. `isSatisfied` = every requirement SIGNED or SIGNED_OLDER. A pin whose document vanished is skipped.

## The gate

`RegistrationCommands.registerPartyOutcome` (the page and `POST .../registrations/party` both go through it), per
traveler, after the caller may act for them: the submit's agreements for that traveler are recorded first
(`applyAgreements`: both boxes, the pinned version, not yet satisfied), then a NEW registration is refused
`WAIVERS_REQUIRED` while `isSatisfied` is false. An agreement stands even if the registration is then refused for
another reason. Already registered travelers are never refused or changed by a document; they re-sign from the
registration page's Sign button or the apps' documents screen.

`accept` refuses `MISSING_ATTESTATION` (a box unticked), `NOT_ALLOWED` (not yourself, not someone you manage, not a
site admin; or a login with no person row), `NOT_PINNED` (the version is not the trip's pin: the page was stale),
`NOT_FOUND`.

## Pages (private repo)

- `admin/orgWaivers.xhtml` (org dashboard card "Documents", `canManageOrg`): the library, an editor whose Save
  creates v1 or publishes the next version, Retire/Restore, a version history with a read-only preview.
- `trip/edit.xhtml` "Required Documents" row + `WEB-INF/tripWaiversDialog.xhtml` (`waiver.canConfigure`: registration
  admin or trip manager): add, remove, and "Move to vN" with the re-sign radio. **Saved immediately** onto the stored
  trip as well as the editor's draft: a decision about other people's signatures must not hang on the page's Save.
- `trip/joinTrip.xhtml` + `WEB-INF/waiverSign.xhtml`: under each selected traveler, every pending document's text
  and the two boxes, bound into the session draft (`RegistrationDraft.waivers`, a flat map keyed
  `personId|waiverId|version|agree|attest`, lazily created so an old session's draft still binds). Registered rows
  show a "Documents to sign" flag and their own Sign button; signed ones say who signed which version when, with a
  PDF link.
- `admin/tripRegistrations.xhtml`: a Documents column (status + a PDF per signature) and a sentence in the approval
  dialog when something is unsigned (approval is still allowed).
- The signed copy: `GET /waivers/signed?trip=&person=&waiver=&version=` (`web/WaiverPdfServlet`, never a `.pdf`
  URL), drawn by `WaiverPdf` with `PdfSheet`: the version's text flattened by `WaiverHtml.toText`, then the
  signature record. Readable by the traveler, whoever manages them, the trip's registration admin or manager, a
  site admin.

## REST

`TripWaiversResource` (`/api/trips/{tripId}/waivers`) and `OrgWaiversResource` (`/api/orgs/{orgId}/waivers`), media
type `application/vnd.trip.waivers.v1+json`; shapes in `api-contract.md`, "Waivers". `RegistrationDto.waiversSatisfied`
lets a status strip flag a registered traveler without another call; the party POST carries `waivers` per traveler.
The apps sign ONLINE (never through the outbox: an agreement must not sit queued) and repeat the answers in the
party POST so a registration queued offline still passes the gate.

## Local data

`FakeData` seeds ONE CFPW document, "Participation Waiver" (`CFPW_WAIVER_ID`, two versions), required by NO seeded
trip on purpose: the existing registration tests register on seeded trips. Tests that need a requirement pin it on a
trip they own (`WebFixtures.requireWaiver`).

## Tests

Unit: `WaiverCommandsTest` (library, pins, the standing matrix, signing, the gate, the PDF), `WaiversResourceTest`,
`WaiverDAOTest`, `WaiverHtmlTest`, `WaiverModelTest`, `WaiverPdfServletTest`. Browser: `WaiverPwIT` (author and
version, require and move with the re-sign decision, Ken registers Lucy only once both boxes are ticked, her PDF,
the re-sign flag).

## Out of scope

Optional (non-required) documents; typed-name or drawn signatures; uploading a PDF as the document itself; sharing a
document between organizations; reminders by email.
