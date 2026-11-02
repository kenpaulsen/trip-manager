# Conference import — people, past conferences, registrations and ledger from an older deployment

A one-time, re-runnable import of an older single-conference deployment (the `mir2026` branch's own AWS account) into
an organization of this one: its people, its conference trips, their registrations, check-in stamps, ledger rows and
trip roles. Code: `org.paulsens.trip.migrate` (public, generic). The account's mapping file and scripts live in the
private repo (`../medjugorje/scripts/conf-import/`). Owner decisions: 2026-11-01.

## The procedure

```sh
cd ../medjugorje/scripts/conf-import
./conf-export.sh --out ~/conf-import/export                      # read-only scans of the old account, mode 700
./conf-import.sh plan     --export ~/conf-import/export --out ~/conf-import/run --actor <site-admin email>
./conf-import.sh rehearse --export ~/conf-import/export --out ~/conf-import/run --actor <site-admin email>
./conf-import.sh apply    --export ~/conf-import/export --out ~/conf-import/run --actor <site-admin email> --yes
./conf-import.sh verify   --export ~/conf-import/export --out ~/conf-import/run
```

1. **plan** reads only. It writes `plan.json` (exactly what an apply writes), `snapshot.json` (the target rows the
   plan touches: the organization, the merged people's families and memberships, the operator), `plan.txt` (counts,
   checks, blockers, notes), `people.tsv` (one line per old person: action, target, email, why) and `review.tsv`
   (people whose family evidence tied). Exit 2 while anything needs a decision: answer it in the mapping and re-plan.
2. **rehearse** runs in LOCAL mode on an in-memory store seeded from `snapshot.json` and the plan's before-images:
   the real applier, the real verifier, a second apply (which must write nothing) and the Java rollback. It touches
   no AWS. Results in `run/rehearsal/`.
3. **apply** runs `plan.json` as reviewed, never a fresh plan: a merged person who changed since the plan is refused
   ("re-plan"), not overwritten. It saves `report.json` after every phase, then verifies. Re-running finishes a
   stopped run; anything already there is left alone.
4. **verify** re-checks the target against `plan.json` and against totals recomputed from the EXPORT itself.
5. After an apply: press **Clear caches** on the admin Settings page. The CLI runs with the cache off and never
   touches the app's shared cache.

`rollback --out <run> --actor <email> --yes` undoes `report.json` newest phase first (roles, bindings, registrations,
trips, families, memberships; merged people and touched families get their before-images back) and writes
`rollback-deletes.sh`: key-only `aws dynamodb delete-item` calls for the people and ledger rows the import CREATED,
which the DAO deliberately cannot delete. Review it, run it, clear caches. Point-in-time recovery is the backstop.

**Deploy first.** Run the apply only after the `conference` branches are deployed: the imported rows carry the new
fields (a registration's ticket, type and check-in stamps, a trip's kind and typed questions), and an older build that
re-saves such a row silently drops them.

## The owner's rules, in the order they apply

People (the planner, `ConferenceImportPlanner`):

1. **Skipped** by the mapping (test accounts), or **soft-deleted** on the old site (its `deleted` stamp): left out,
   with everything referencing them. A deleted account that a live registration or payment still references is a
   blocker instead.
2. **Same address = same person**, when the first and last names also match (case and spacing aside). The existing
   person is kept: blanks are filled from the old row, an existing value always wins (`people.tsv` lists the filled
   and the kept fields). A matching address with a different name is a blocker. The mapping can merge by a changed
   address (`mergeIntoEmail`, name check waived) or accept a differently written name (`nameMatchOverrides`).
3. Everyone else with an address is **created with their old id**. Two imported people may never share an address.
4. A **sign-in name instead of an address** (the old site allowed `michael driver`, `hana.mckeever`, a date, a phone
   number) is a family member or the same person registering again. The evidence is someone imported WITH an
   address who shares their phone, their home address (street and zip), or their surname with a registration on the
   same trip within `familyWindowHours`:
   - exactly one candidate with the **same name and no conflicting birthdate**: the SAME person; their registrations
     and payments move to that account. (A mother and the baby named after her share a name and a phone, so the
     birthdate must agree.) Several such accounts: kept separate.
   - otherwise one candidate, or the one with the most KINDS of evidence: a **family member** (never a manager),
     without an email. A tie or no candidate: their own person, with the sign-in name kept as their email.
   - the mapping's `familyDecisions` overrides all of it (an anchor's old id, or `none`).
5. The old site's own "managed" links become family links, except where the member already exists in the target:
   the target's family is kept.
6. Duplicates named in the mapping (`sameAs`) become one person; the duplicate fills blanks.

Two old accounts that are one person now keep ONE registration per trip: the most advanced status wins and the other
is noted; two of the same status are a blocker.

Trips become `kind` CONFERENCE of the target organization, `openToPublic` false, rosters remapped. Free-text
questions keep their ids; a hidden `opt<N>` true/false answer makes question N a yes/no one; the legacy
`admissionOptions` become ONE priced pick-one question (`admissionQuestion`), so admission answers show by label. The
legacy pricing fields (`admissionOptions`, `discountCodes`, `childPriceCap`) are not carried as fields; the export
keeps them. Checkout stays off.

Registrations keep their status, created date and answers; `_admission` moves under the admission question,
`_checkedIn` becomes `checkedInAt` (no `checkedInBy`), every CONFIRMED row gets a ticket, and `registrationType` is
the admission option's label (else the mapped legacy `_regType` label). No price breakdown is invented: what a person
paid is their ledger.

Ledger rows keep their ids, amounts, dates, notes and soft-delete stamps, are re-owned by the target person and the
organization, and are bound to their trip through `BindingCommands`; a Shared or Batch group gets `groupPeople`
stamped (the sorted live members, as `group-tx-membership.md`). A row the export holds no binding for can be bound by
the mapping (`transactionTrips`). Trip roles carry over (`roleMap`: `tripCheckin` -> `checkinStaff`).

Everyone imported joins the organization through `OrgCommands.importMembership` (site admins only; the one
membership writer). Families go through `FamilyCommands.adminLink`. This script is the owner-approved fifth way a
`Person` comes into being (`../CLAUDE.md`); it is reachable from no page.

## What the checks prove

Before the apply (`checkPlan`): every field of every created person, every registration answer and every ledger
field of the export is carried or moved on purpose (text compares trimmed: the model trims names). After it
(`checkTarget`): every planned person exists with the planned values and is a member; every family link exists, as a
member; each trip is a conference of the organization with its roster and questions; every registration is stored as
planned and CONFIRMED rows hold tickets; registrations per trip and status, check-ins per trip, ledger rows and live
totals per trip, and every person's trip total all equal what the EXPORT says (computed from its own rows and
bindings, not from the plan); every role is held.

The first production plan (2026-11-01) and its rehearsal: 1726 created, 41 merged, 22 one person with another
record, 20 skipped, 62 family links, 1108 registrations, 972 ledger rows, 9 roles; every check passed, a second
apply wrote nothing, the rollback undid every row.
