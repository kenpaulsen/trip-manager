package org.paulsens.trip.migrate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.paulsens.trip.migrate.LedgerFixture.ORG;
import static org.paulsens.trip.migrate.LedgerFixture.TODAY;
import static org.paulsens.trip.migrate.LedgerFixture.uuid;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The ledger migration's command line over files (docs/migrations/ledger-trip-binding.md): a synthetic dump
 * snapshotted, planned, reviewed, rehearsed and verified offline, then the same plan applied to and rolled back
 * from the in-memory store through the DAO. Also the CSV the owner's review travels in.
 */
public class LedgerBindingCliTest {
    private LedgerFixture ledger;
    private Path dir;
    private String ann;
    private String trip;
    private String bill;
    private String pay;
    private String old;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    /** Ann on one ended trip: a bill and a payment the plan binds, and one row from years before for the owner. */
    @BeforeMethod
    public void newRun() throws IOException {
        ledger = new LedgerFixture();
        dir = Files.createTempDirectory("ledger-bind-test");
        ann = ledger.person("Ann \"Annie\"", "=Alpha, Jr");
        trip = ledger.endedTrip("The, trip", ann);
        bill = ledger.row(ann, -100, LocalDate.of(2024, 2, 1));
        pay = ledger.row(ann, 100, LocalDate.of(2024, 2, 2));
        old = ledger.row(ann, 7.5, LocalDate.of(2019, 1, 1));
        console.reset();
    }

    private int run(final String... args) {
        final List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--today", TODAY.toString()));
        return new LedgerBindingCli(new PrintStream(console, true, StandardCharsets.UTF_8), DAO::getInstance)
                .run(all.toArray(String[]::new));
    }

    private String said() {
        return console.toString(StandardCharsets.UTF_8);
    }

    private String dump(final String name) throws IOException {
        return ledger.dump(dir.resolve(name)).toString();
    }

    private String out() {
        return dir.resolve("run").toString();
    }

    private Path file(final String name) {
        return dir.resolve("run").resolve(name);
    }

    /** The review file with the owner's answer for the old row filled in. */
    private String approve(final String tripId) throws IOException {
        final List<String> lines = new ArrayList<>();
        for (final List<String> record : LedgerCsv.parse(Files.readString(file("uncertain.csv")))) {
            final List<String> cells = new ArrayList<>(record);
            if (cells.get(3).equals(old.substring(old.indexOf(',') + 1))) {
                cells.set(cells.size() - 1, " " + tripId + " ");
            }
            lines.add(LedgerCsv.line(cells));
        }
        final Path approved = dir.resolve("approved.csv");
        Files.writeString(approved, "﻿" + String.join("\r\n", lines) + "\r\n");
        return approved.toString();
    }

    // ------------------------------------------------------------------ offline: snapshot, plan, rehearse, verify

    @Test
    public void aDumpIsSnapshottedPlannedReviewedAndRehearsedOffline() throws IOException {
        final String dump = dump("dump");
        assertEquals(run("snapshot", "--dump", dump, "--out", out()), 0, said());
        assertTrue(said().contains("rows: 3; live: 3") && said().contains("bound to no trip: 3"), said());
        assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(file("snapshot.json"))), "rw-------");
        assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("run"))), "rwx------");
        final LedgerSnapshot snapshot = LedgerData.mapper().readValue(file("snapshot.json").toFile(),
                LedgerSnapshot.class);
        assertEquals(snapshot, LedgerSnapshot.of(ledger.data(), TODAY), "a snapshot reads back as it was written");

        assertEquals(run("plan", "--dump", dump, "--out", out()), 0, said());
        assertTrue(said().contains("DETERMINISTIC: 2") && said().contains("NO_WINDOW: 1"), said());
        assertEquals(Files.readString(file("plan.txt")).lines().filter(line -> line.contains("to bind: 2")).count(), 1);
        final List<List<String>> review = LedgerCsv.parse(Files.readString(file("uncertain.csv")));
        assertEquals(review.get(0), LedgerPlanFiles.UNCERTAIN_HEADER);
        assertEquals(review.size(), 2);
        final List<String> row = review.get(1);
        assertEquals(row.subList(0, 7), List.of("Ann \"Annie\"", "'=Alpha, Jr", ann,
                old.substring(old.indexOf(',') + 1), "2019-01-01", "7.50", "7.50"),
                "names survive their quotes and commas; one that would run as a formula is disarmed");
        assertEquals(row.subList(7, 11), List.of("Tx", "Payment", "Synthetic", "invented note"));
        assertEquals(row.get(13), "The, trip | 2024-05-01 to 2024-05-10 | ended | net now 0.00 | " + trip);
        assertEquals(row.get(15), trip, "the nearest trip is the best guess");
        assertTrue(row.get(18).startsWith("Binding puts this row on the trip's books"), row.get(18));
        assertEquals(row.get(19), "", "the approval is the owner's to fill");
        final List<List<String>> summary = LedgerCsv.parse(Files.readString(file("per-trip-summary.csv")));
        assertEquals(summary.get(0), LedgerPlanFiles.TRIP_HEADER);
        assertEquals(summary.get(1), List.of(trip, "The, trip", "2024-05-01", "2024-05-10", "true", "0", "0", "2",
                "0", "2", "0.00", "-100.00", "100.00", "1"));

        console.reset();
        assertEquals(run("rehearse", "--dump", dump, "--out", out()), 0, said());
        assertTrue(said().contains("REHEARSAL PASSED SO FAR"), said());
        assertTrue(said().contains("Applied: 2 rows bound") && said().contains("exactly restored"), said());
        assertTrue(Files.readString(file("rehearsal/verify.txt")).contains("+ " + bill + " INVOICE -100.0"));

        console.reset();
        assertEquals(run("rehearse", "--dump", dump, "--out", out(), "--plan", file("plan.json").toString(),
                "--approved", approve(trip)), 0, said());
        assertTrue(said().contains("REHEARSAL PASSED: all 7 checks"), said());
        assertTrue(said().contains("Applied: 3 rows bound"), said());

        console.reset();
        assertEquals(run("rehearse", "--dump", dump, "--out", out(), "--approved", approve(uuid())), 2, said());
        assertTrue(said().contains("no such trip") && said().contains("REHEARSAL FAILED"), said());
    }

    @Test
    public void aVerifyComparesALaterDumpWithTheSnapshot() throws IOException {
        final String dump = dump("before");
        assertEquals(run("snapshot", "--dump", dump, "--out", out()), 0, said());
        assertEquals(run("plan", "--dump", dump, "--out", out()), 0, said());
        final String before = file("snapshot.json").toString();
        ledger.bind(bill, trip);
        ledger.bind(pay, trip);
        final String partly = dump("partly");
        console.reset();
        assertEquals(run("verify", "--dump", partly, "--before", before, "--out", out()), 2, said());
        assertTrue(said().contains("VERIFY FAILED: failed checks [7]"), said());
        console.reset();
        assertEquals(run("verify", "--dump", partly, "--before", before, "--out", out(), "--plan",
                file("plan.json").toString()), 2, "a deployment with a trip-less row is not verified: " + said());
        assertTrue(said().contains("VERIFY INCOMPLETE"), said());

        ledger.bind(old, trip);
        console.reset();
        assertEquals(run("verify", "--dump", dump("after"), "--before", before, "--out", out()), 0, said());
        assertTrue(said().contains("VERIFY PASSED: all 7 checks"), said());
        assertTrue(Files.readString(file("verify.txt")).contains("3 newly bound row(s)"));
    }

    // ------------------------------------------------------------------ against the store: apply, rollback

    @Test
    public void anApplyBindsThroughTheDaoAndARollbackTakesItBack() throws IOException {
        final DAO dao = DAO.getInstance();
        final Trip stored = Trip.builder().id(trip).title("The, trip").people(List.of()).build();
        stored.setOrgId(ORG);
        assertTrue(dao.saveTrip(stored));
        for (final String key : List.of(bill, pay, old)) {
            assertTrue(dao.saveTransaction(ledger.tx(key)));
        }
        assertEquals(run("plan", "--dump", dump("dump"), "--out", out()), 0, said());
        final String plan = file("plan.json").toString();
        final String approved = approve(trip);
        assertEquals(run("apply", "--plan", plan, "--approved", approved, "--out", out()), 0, said());
        assertTrue(said().contains("DRY RUN: 3 rows would be bound, 0 skipped, 0 failures; nothing written"), said());
        assertTrue(Files.readString(file("apply-dry-run.txt")).contains("WOULD BIND " + bill + " -> " + trip));
        assertFalse(Files.exists(file("rollback.json")), "a dry run records nothing, having written nothing");
        assertEquals(run("apply", "--approved", approved, "--out", out(), "--yes"), 1, "the plan must be named");
        assertEquals(run("apply", "--plan", plan, "--out", out(), "--yes"), 1, "so must the review file");
        assertTrue(dao.getBindings(trip, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO).isEmpty());

        console.reset();
        assertEquals(run("apply", "--plan", plan, "--approved", approved, "--out", out(), "--yes"), 0, said());
        assertTrue(said().contains("Applied: 3 rows bound, 0 skipped, 0 failures"), said());
        assertTrue(said().contains("Clear caches"), said());
        assertEquals(dao.getBindings(trip, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO).size(), 3);
        assertEquals(dao.getBindings(old, BindingType.TRANSACTION, BindingType.TRIP, Cached.NO), List.of(trip));
        assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(file("rollback.json"))), "rw-------");

        console.reset();
        assertEquals(run("apply", "--plan", plan, "--approved", approved, "--out", out(), "--yes"), 0, said());
        assertTrue(said().contains("Applied: 3 rows bound, 3 skipped"), "the first run's record is kept: " + said());

        console.reset();
        assertEquals(run("rollback", "--out", out()), 0, said());
        assertTrue(said().contains("DRY RUN: 6 bindings would be removed"), said());
        assertEquals(dao.getBindings(trip, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO).size(), 3,
                "no --yes, no write");
        console.reset();
        assertEquals(run("rollback", "--out", out(), "--rollback", file("rollback.json").toString(), "--yes"), 0,
                said());
        assertTrue(said().contains("6 bindings handled, 0 FAILED"), said());
        assertTrue(dao.getBindings(trip, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO).isEmpty());
        assertTrue(dao.getBindings(bill, BindingType.TRANSACTION, BindingType.TRIP, Cached.NO).isEmpty());
        assertTrue(Files.readString(file("rollback.txt")).contains("REMOVED " + bill + " -> " + trip));
        final Transaction untouched = dao.getTransaction(ledger.tx(bill).getUserId(), ledger.tx(bill).getTxId(),
                Cached.NO).orElseThrow();
        assertEquals(LedgerData.fingerprint(untouched), LedgerData.fingerprint(ledger.tx(bill)));
    }

    @Test
    public void anApplyReportsARefusedApprovalAsAFailedRun() throws IOException {
        assertEquals(run("plan", "--dump", dump("dump"), "--out", out()), 0, said());
        final Path approved = dir.resolve("stray.csv");
        Files.writeString(approved, "personId,txId,approvedTripId\n" + ann + ",no-such-row," + trip + "\n");
        console.reset();
        assertEquals(run("apply", "--plan", file("plan.json").toString(), "--approved", approved.toString(), "--out",
                out(), "--yes"), 2, said());
        assertTrue(said().contains("REFUSED " + ann + ",no-such-row: not a row this plan left for approval"), said());
        assertTrue(said().contains("2 skipped"), "the planned rows are not in this store: " + said());
    }

    @Test
    public void theLiveTablesAreReadWhenNoDumpIsNamed() {
        assertEquals(run("snapshot", "--out", out()), 0, said());
        assertTrue(said().contains("rows: 0; live: 0"), "local mode keeps no ledger rows in a scannable table");
        assertEquals(run("plan", "--out", out()), 0, said());
        assertTrue(said().contains("to bind: 0"), said());
    }

    @Test
    public void badCommandLinesAreRefused() throws IOException {
        assertEquals(run(), 1);
        assertEquals(new LedgerBindingCli(new PrintStream(console, true, StandardCharsets.UTF_8), DAO::getInstance)
                .run(new String[0]), 1);
        assertTrue(said().contains("usage: snapshot|plan|rehearse|apply|verify|rollback"), said());
        assertEquals(run("explode", "--out", out()), 1);
        assertTrue(said().contains("Unknown command: explode"), said());
        assertEquals(run("plan", "--dump", dump("dump")), 1, "--out is required");
        Files.delete(dir.resolve("dump").resolve("bindings.json"));
        console.reset();
        assertEquals(run("plan", "--dump", dir.resolve("dump").toString(), "--out", out()), 1);
        assertTrue(said().contains("ERROR: The dump has no bindings.json"), said());
        assertEquals(run("rehearse", "--dump", dump("dump"), "--out", out()), 1, "no plan.json to rehearse");
    }

    @Test
    public void aRollbackRecordThatCannotBeSavedStopsTheRun() throws IOException {
        assertEquals(run("plan", "--dump", dump("dump"), "--out", out()), 0, said());
        Files.createDirectories(file("rehearsal").resolve("rollback.json"));
        console.reset();
        assertEquals(run("rehearse", "--dump", dir.resolve("dump").toString(), "--out", out()), 1, said());
        assertTrue(said().contains("ERROR"), said());
    }

    // ------------------------------------------------------------------ the review file's CSV

    @Test
    public void theToolReadsAndWritesWithTheDaosOwnMapperConfiguration() {
        // A plan made with one configuration and applied with another reads every row as "edited since the plan".
        final com.fasterxml.jackson.databind.ObjectMapper tool = LedgerData.mapper();
        final com.fasterxml.jackson.databind.ObjectMapper dao = org.paulsens.trip.dynamo.DAO.createObjectMapper();
        assertEquals(tool.getRegisteredModuleIds(), dao.getRegisteredModuleIds());
        assertEquals(tool.getSerializationConfig().getSerializationFeatures(),
                dao.getSerializationConfig().getSerializationFeatures());
        assertEquals(tool.getDeserializationConfig().getDeserializationFeatures(),
                dao.getDeserializationConfig().getDeserializationFeatures());
        assertEquals(tool.getSerializationConfig().getDefaultPropertyInclusion(),
                dao.getSerializationConfig().getDefaultPropertyInclusion());
    }

    @Test
    public void csvCellsRoundTrip() {
        final List<String> cells = List.of("plain", "a,b", "say \"hi\"", "two\nlines", " padded ", "", "end");
        final String line = LedgerCsv.line(cells);
        assertEquals(line, "plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",\" padded \",,end");
        assertEquals(LedgerCsv.parse(line + "\r\n\r\n" + line), List.of(cells, cells));
        assertEquals(LedgerCsv.parse("a,b,\n"), List.of(List.of("a", "b", "")));
        // A spreadsheet's export starts with a byte-order mark: it is dropped, and ONLY it. Without one the
        // first character stays (a parser whose BOM literal had been emptied dropped it from every file).
        assertEquals(LedgerCsv.parse("\uFEFFtxId,trip\nA1,T1\n"),
                List.of(List.of("txId", "trip"), List.of("A1", "T1")));
        assertEquals(LedgerCsv.parse("txId,trip\n").get(0).get(0), "txId");
        assertEquals(LedgerCsv.parse("\uFEFF\uFEFFx\n").get(0).get(0), "\uFEFFx", "one mark, not every one");
        assertEquals(LedgerCsv.parse("\"\"\n"), List.of(List.of("")));
        assertEquals(LedgerCsv.parse(""), List.of());
        assertEquals(LedgerCsv.line(Arrays.asList("x", null)), "x,");
    }

    @Test
    public void typedTextCannotRunAsAFormula() {
        assertEquals(LedgerCsv.typed("=SUM(A1)"), "'=SUM(A1)");
        assertEquals(LedgerCsv.typed("+1"), "'+1");
        assertEquals(LedgerCsv.typed("-1"), "'-1");
        assertEquals(LedgerCsv.typed("@cmd"), "'@cmd");
        assertEquals(LedgerCsv.typed("Deposit"), "Deposit");
        assertEquals(LedgerCsv.typed(""), "");
        assertEquals(LedgerCsv.typed(null), "");
    }

    @Test
    public void anApprovedFileNeedsItsColumns() {
        assertThrows(IllegalArgumentException.class, () -> LedgerPlanFiles.approvals(""));
        assertThrows(IllegalArgumentException.class, () -> LedgerPlanFiles.approvals("personId,txId\nx,y\n"));
        assertEquals(LedgerPlanFiles.approvals("txId,approvedTripId,personId\nt1,,p1\nt2,trip,p2\nshort\n").size(), 1);
        assertFalse(LedgerPlanFiles.approvals("txId,approvedTripId,personId\nt2, trip ,p2\n").get(0).tripId()
                .contains(" "));
    }

    @Test
    public void theReviewFileDescribesGroupsTripRowsAndRunningTrips() {
        final String bob = ledger.person("Bob", "Beta");
        final String running = ledger.runningTrip("Running", ann, bob);
        final String group = uuid();
        ledger.groupRow(ann, Transaction.Type.Shared, group, 90, LocalDate.of(2025, 12, 1), List.of(ann, bob));
        ledger.groupRow(bob, Transaction.Type.Shared, group, 90, LocalDate.of(2025, 12, 1), List.of(ann, bob));
        final String ownRow = ledger.row(running, -300, LocalDate.of(2025, 12, 1));
        final Transaction blank = ledger.tx(ledger.row(uuid(), 1, LocalDate.of(2025, 12, 1)));
        blank.setAmount(null);
        ledger.put(blank);
        final LedgerData data = ledger.data();
        final LedgerPlanFiles files = new LedgerPlanFiles(data, new LedgerBindingPlanner(data, TODAY).plan());
        final List<List<String>> review = LedgerCsv.parse(String.join("\n", files.uncertainCsv()));
        final List<String> shared = review.stream().filter(row -> row.get(11).equals(group)).findFirst().orElseThrow();
        assertEquals(shared.get(5), "45.00", "this person's share");
        assertEquals(shared.get(6), "90.00", "the whole amount");
        assertEquals(shared.get(7), "Shared");
        assertEquals(shared.get(12), "Ann \"Annie\" =Alpha, Jr; Bob Beta");
        assertTrue(shared.get(13).contains("NOT ENDED"), shared.get(13));
        assertTrue(shared.get(18).contains("has NOT ended"), shared.get(18));
        final List<String> own = review.stream().filter(row -> row.get(2).equals(running)).findFirst().orElseThrow();
        assertEquals(own.subList(0, 2), List.of("", "(trip) Running"), "a trip's own row says whose it is");
        assertEquals(own.get(3), ownRow.substring(ownRow.indexOf(',') + 1));
        final List<String> nobody = review.stream().filter(row -> row.get(5).isEmpty()).findFirst().orElseThrow();
        assertEquals(nobody.subList(0, 2), List.of("", ""), "an unknown person has no name to show");
        assertEquals(nobody.get(15), "", "and no guess");
        assertTrue(files.perTripCsv().get(files.perTripCsv().size() - 1).startsWith(running), "guessed onto it");
    }
}
