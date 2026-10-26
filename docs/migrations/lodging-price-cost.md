# Lodging price vs cost: the rolling deploy, the one-release mirror, and the rate-plans table

Companion to `docs/lodging.md` "Price vs cost" and "Rate plans". The 2026-10-25 round split
`ReservationOffer`'s one set of money fields into two `RateSchedule`s (`price`, what a participant pays;
`cost`, what the hotel charges us) and added the hotel-owned `lodging_rate_plans` table. Neither needs a data
migration script: the JSON fold does the row conversion lazily, on read, and the new table starts empty.
What they DO need is the deploy discipline below, and one clean-up a release later.

## 1. Why old rows read correctly: the fold

A pre-split `lodging_offers` row carries four flat keys: `pricingModel`, `nightlyPriceCents`,
`nightlyPriceOverrides`, `singleSupplementCents`. The `ReservationOffer` `@JsonCreator` still accepts all four
and folds them (`foldPrice`, modeled on the older `roomTypeId` -> `roomTypeIds` fold), in this order:

1. a `price` object present -> it wins;
2. else `"priced": false` -> the offer is UNPRICED ("needs participant pricing");
3. else ANY of the four legacy keys present -> `price` is built from them, missing cents defaulting to 0.
   Every old row wrote `nightlyPriceCents`, so every old row -- a $0 one included -- reads as PRICED at its
   old numbers, and its bills are untouched;
4. else (nothing at all: `new ReservationOffer()`, a fresh builder) -> unpriced.

The row is REWRITTEN in the new shape only when something saves it (an option edit, a rate-plan fan-out). An
old row that is never edited keeps its old keys forever, which is fine: rule 3 reads it. There is
deliberately no backfill.

## 2. Why new rows still write the old keys: the mirror

`DAO.createObjectMapper` disables `FAIL_ON_UNKNOWN_PROPERTIES` (instances of different versions share the
same DynamoDB rows and Valkey blobs, so reads must tolerate JSON from a newer or older schema). That tolerance
is also the hazard here. During a rolling deploy -- and in a rollback -- an OLD instance may read a row the
NEW code wrote. If that row carried only `price` and no `nightlyPriceCents`, the old instance would ignore
the object it does not know, read the missing cents as 0, price every stay at $0, and the next recompute
would SOFT-DELETE every lodging bill on the trip ("zero = no row" is the biller's rule). The bills would come
back on the next recompute by a new instance, but in between the ledger would be wrong and audited as such.

So for one release every row writes BOTH shapes: `price` + `"priced": true|false`, and the four legacy keys
as `@JsonProperty` mirror getters on `ReservationOffer` that delegate to the price side (`PER_ROOM` / 0 /
empty / 0 when unpriced -- an unpriced option has no stays, so an old instance pricing one at $0 is
harmless). An old instance therefore sees exactly what it always saw. The `cost` side, `costExtras`,
`ratePlanId` and `ratePlanVersion` are simply unknown properties to it and are ignored; it cannot damage them
unless it SAVES the row, which an admin editing an option on an old instance mid-deploy would do (the fields
would be dropped). The deploy window is minutes; accept it, do not edit options during a deploy.

The four `@Deprecated` setters (`setPricingModel`, `setNightlyPriceCents`, `setNightlyPriceOverrides`,
`setSingleSupplementCents`) write the PRICE side, creating it on first write (a legacy write means
"priced"), and the hand-written builder methods `nightlyPriceCents(long)` / `singleSupplementCents(long)`
keep every existing `ReservationOffer.builder()` call site compiling. They exist so the compiler did not have
to list every writer in the same change as the model.

## 3. One release later: removing the mirror (checklist)

Do this in the release AFTER the one that shipped the split has been running in production, so no instance
that lacks the fold can ever read a row again.

- [ ] `ReservationOffer`: delete the four mirror getters (`getPricingModel`, `getNightlyPriceCents`,
      `getNightlyPriceOverrides`, `getSingleSupplementCents`) and the four `@Deprecated` setters plus
      `priceForWrite()`. KEEP the four `@JsonProperty` constructor parameters and `foldPrice`: rule 3 is
      what reads a never-edited old row, and old rows live as long as their trips.
- [ ] Decide the builder: either keep `ReservationOfferBuilder.nightlyPriceCents(long)` /
      `singleSupplementCents(long)` as a convenience that builds a `price` schedule, or delete them and move
      every test and `FakeData` writer to `.price(RateSchedule.builder()...build())`. The compiler lists the
      call sites; `grep -rn "nightlyPriceCents(" trip/src` is the same list.
- [ ] `@JsonIgnore` helpers `nightlyPriceCents(LocalDate)` and `isPerPerson()` stay: `pricingLabel`, `rateOf`
      and the itinerary read them.
- [ ] Regenerate `trip/src/test/resources/serialized/ReservationOffer.ser` (the Java-serialization fixture
      `SerializationCompatibilityTest` reads; the field set changes shape, so the fixture must be rewritten
      from the new class) and keep `serialVersionUID = 1L` unless that test says otherwise.
- [ ] Tests that change: `ReservationOfferTest.aNewRowCarriesThePriceObjectTheFlagAndTheLegacyMirror`
      (becomes "carries the object and the flag and NOT the legacy keys"),
      `anExplicitlyUnpricedRowStaysUnpricedAndMirrorsZero` (drop the mirror half),
      `legacySettersWriteThePriceSideCreatingItOnFirstWrite` (delete), and any `LodgingCommandsTest` /
      `LodgingResourceTest` / `RoomInvoiceReportTest` assertion that reads `getNightlyPriceCents()` off an
      offer (read `getPrice().getNightlyCents()` instead). `aLegacyRowReadsAsPricedAtItsOldNumbersZeroIncluded`
      and `aPriceObjectOutranksTheLegacyMirror` STAY: they test the fold, which stays.
- [ ] `docs/lodging.md` "Price vs cost": delete the mirror paragraph; `trip/CLAUDE.md` Domain model: drop the
      "mirror on write" clause; this file: mark section 2 done with the date.
- [ ] Deploy check as in section 5: a recompute on a seeded trip still reports `written 0`.

## 4. The `lodging_rate_plans` table (rollout order)

The table is CDK-created (`AppStack.lodgingRatePlansTable`, same builder as `lodging_blocks`:
PAY_PER_REQUEST, RETAIN, deletion protection; `deploy-config.json` key `lodgingRatePlansTable`; added to every
grant list), and `medjugorje/setup-instructions.txt` has its row after `lodging_blocks`. A query against a
table that does not exist is a hard `ResourceNotFoundException`, and the AWS Backup plan picks a new table up
by wildcard but PITR is per table and NOT enabled by CDK, so:

1. `cd medjugorje/infra && cdk deploy TripApp --profile cdk-deploy` (owner-run). Wait for the table to be
   `ACTIVE` (`aws dynamodb describe-table --table-name lodging_rate_plans --profile cdk-deploy`).
2. Enable PITR by hand: `aws dynamodb update-continuous-backups --table-name lodging_rate_plans
   --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true --profile cdk-deploy`, then confirm
   with `describe-continuous-backups`. Every other lodging table has it; a new table is the one that gets
   forgotten (the `DynamoDB backups` memory note exists because of exactly this).
3. Start the pipeline (`/admin/deploy.jsf` or `aws codepipeline start-pipeline-execution --name trip-manager
   --profile cdk-deploy`; each run is billable). The code may ship before any plan exists -- an empty
   partition is a valid answer -- but never before the table does.
4. After the deploy, verify with a STALE session cookie (the `Model field change = mass session invalidation`
   note): `ReservationOffer` and `Reservation` grew fields, both carry a pinned `serialVersionUID = 1L` and a
   `readResolve` that fills the new lists, so a returning visitor's session must still open.

Local mode and the unit suite need nothing: `InMemoryPersistence.TABLES` registers the table and `FakeData`
seeds it.

## 5. Deploy check: the deploy rewrote no bills

The one promise the split makes to the ledger is that an un-concessioned bill note is byte-identical to the
pre-split wording and every amount is unchanged, so a recompute after the deploy leaves every existing row
alone. Check it on a seeded trip after each of the two deploys (the split, and the mirror removal):

1. Open the trip's Lodging page as an internal admin (`user2` in local mode; a `tripMgr` or org
   `lodgingAdmin` in production) and press **Recompute** on the Reservations tab.
2. The growl and the audit line report how many bill rows were written. The expected answer is `written 0`
   (the recompute compares amount AND note; a changed note would rewrite the row).
3. A non-zero count means a note or an amount moved. Diff one rewritten row's note against the
   `LodgingPricingTest` wording before touching anything else:
   `everyUnconcessionedDescriptionIsByteIdenticalToThePreSplitWording` is the unit-level version of this
   check, and if it is green while production rewrote rows, the difference
   is in the DATA (an offer whose legacy keys folded unexpectedly), not the code.

Pre-split baseline for the seeded trip in local mode: the Pansion offer prices Dave's and Matt's stays into
two Bill rows; `AdminReportsPwIT` asserts the Room Invoice total ($720) that those rows sum to, which is why
the seeded offer is NOT linked to the seeded rate plan until the webtests move.
