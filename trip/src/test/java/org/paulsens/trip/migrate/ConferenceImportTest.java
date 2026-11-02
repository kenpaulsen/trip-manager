package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The conference import (docs/migrations/conference-import.md) end to end on the in-memory store: a synthetic
 * legacy export exercising every one of the owner's rules, planned, checked, applied, verified, re-applied (nothing
 * new), rolled back; then the command line over the same files. Every id is fresh per test, so the shared DAO's
 * other rows never interfere.
 */
public class ConferenceImportTest {
    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private String trip;
    private String org;
    private Ids ids;
    private Person existing;
    private Person overridden;
    private Person operator;

    /** The old ids of the fixture's people, each named for the rule it exercises. */
    private record Ids(String a, String e, String o, String kid, String kid2, String same, String baby, String tie,
            String p1, String p2, String strong, String p3, String p4, String lone, String del, String skip,
            String dup) {
    }

    @BeforeMethod
    public void seedTarget() throws IOException {
        trip = uuid();
        org = uuid();
        ids = new Ids(uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(),
                uuid(), uuid(), uuid(), uuid(), uuid());
        final Organization organization = new Organization(Organization.Id.from(org), "Import Org " + org, null,
                null, null, null, null, 0L);
        assertTrue(DAO.getInstance().saveOrganization(organization));
        existing = person("Eve", "Existing", "eve." + org + "@example.com", LocalDate.of(1970, 1, 1), null);
        overridden = person("Other", "Name", "ox." + org + "@example.com", null, null);
        operator = person("Op", "Erator", "op." + org + "@example.com", null, null);
    }

    // ------------------------------------------------------------------ the planner

    @Test
    public void thePlanFollowsEveryRule() throws IOException {
        final ImportPlan plan = planner(mapping()).plan(export());
        assertEquals(plan.blockers(), List.of(), String.join("\n", plan.blockers()));
        assertEquals(action(plan, ids.a()), ImportPlan.Action.CREATE);
        final ImportPlan.PersonStep merged = plan.stepFor(ids.e());
        assertEquals(merged.action(), ImportPlan.Action.MERGE);
        assertEquals(merged.targetId(), existing.getId(), "same address, same name: the existing person");
        assertTrue(merged.filled().contains("cell"), "a blank is filled from the old row");
        assertTrue(merged.kept().contains("birthdate"), "an existing value is kept");
        assertEquals(plan.stepFor(ids.o()).targetId(), overridden.getId(), "the mapping's merge, name waived");
        assertNull(plan.stepFor(ids.kid()).person().getEmail(), "a linked family member has no email");
        assertEquals(action(plan, ids.same()), ImportPlan.Action.SAME_AS, "same name and birthdate: one person");
        assertEquals(plan.idMap().get(ids.same()), Person.Id.from(ids.a()));
        assertEquals(action(plan, ids.baby()), ImportPlan.Action.CREATE, "same name, other birthdate: a child");
        assertEquals(plan.stepFor(ids.tie()).person().getEmail(), signin("tie-signin"),
                "a tie keeps the sign-in name");
        assertEquals(plan.review().size(), 1);
        assertEquals(plan.stepFor(ids.lone()).person().getEmail(), signin("lone"),
                "no candidate keeps the sign-in name");
        assertEquals(action(plan, ids.del()), ImportPlan.Action.SKIP, "deleted on the old site");
        assertEquals(action(plan, ids.skip()), ImportPlan.Action.SKIP);
        assertEquals(plan.idMap().get(ids.dup()), Person.Id.from(ids.p1()));
        assertEquals(anchorOf(plan, ids.kid()), ids.a());
        assertEquals(anchorOf(plan, ids.kid2()), ids.a(), "the old site's own managed link");
        assertEquals(anchorOf(plan, ids.baby()), ids.a());
        assertEquals(anchorOf(plan, ids.strong()), ids.p3(), "two kinds of evidence beat one");
        assertNull(anchorOf(plan, ids.tie()));

        final Trip planned = plan.trips().get(0);
        assertEquals(planned.getKind(), TripKind.CONFERENCE);
        assertEquals(planned.getOrgId(), org);
        assertEquals(planned.getOpenToPublic(), Boolean.FALSE);
        assertEquals(planned.getRegOptions().size(), 3, "two questions and the admission question");
        final Registration a = registration(plan, ids.a());
        assertEquals(a.getOptions().get("10"), "full", "the admission answer under the new question");
        assertEquals(a.getOptions().get("9"), "Yes", "a hidden opt9 true becomes a yes/no answer");
        assertEquals(a.getRegistrationType(), "Full weekend");
        assertNotNull(a.getCheckedInAt());
        assertNotNull(a.getTicketCode(), "every CONFIRMED registration has a ticket");
        assertEquals(a.getStatus(), Registration.Status.CONFIRMED, "the merged duplicate's PENDING row lost");
        assertEquals(registration(plan, ids.e()).getRegistrationType(), "Legacy X", "a legacy type's label");
        assertTrue(plan.notes().stream().anyMatch(note -> note.contains("kept the Confirmed one")));

        final List<String> group = plan.transactions().stream().filter(step -> "G".equals(step.tx().getGroupId()))
                .findFirst().orElseThrow().tx().getGroupPeople().stream().map(Person.Id::getValue).toList();
        assertEquals(group, List.of(ids.a(), ids.kid()).stream().sorted().toList(), "the group, stamped");
        assertEquals(plan.transactions().stream().filter(step -> "tx3".equals(step.tx().getTxId())).findFirst()
                .orElseThrow().tripId(), trip, "the mapping's binding repair");
        assertTrue(plan.transactions().stream().noneMatch(step -> "tx4".equals(step.tx().getTxId())));
        assertEquals(plan.roles().size(), 2, "the two trip roles; the unscoped one is noted");
        assertTrue(plan.roles().stream().anyMatch(role -> "checkinStaff".equals(role.base())));

        final ConferenceImportVerifier verifier = new ConferenceImportVerifier(MAPPER, mapping());
        verifier.checkPlan(export(), plan);
        assertEquals(verifier.failures(), 0, String.join("\n", verifier.lines()));
    }

    @Test
    public void anythingUnclearIsABlocker() throws IOException {
        final ObjectNode root = exportTree();
        final ArrayNode people = (ArrayNode) root.get("people");
        people.add(oldPerson(uuid(), "Nora", "Mismatch", existing.getEmail(), null, null));   // name differs
        final String c1 = uuid();
        people.add(oldPerson(c1, "Cee", "One", "same@" + org + ".example", null, null));
        people.add(oldPerson(uuid(), "Cee", "Two", "same@" + org + ".example", null, null));  // shared address
        person("Tak", "En", "taken-" + org, null, null);
        people.add(oldPerson(uuid(), "Taken", "Name", "taken-" + org, null, null));   // sign-in name held
        final ObjectNode deleted = oldPerson(uuid(), "Del", "Used", "del-used", null, null);
        deleted.put("deleted", "2026-07-14T10:00:00");
        people.add(deleted);
        ((ArrayNode) root.get("registrations")).add(oldRegistration(text(deleted, "id"), "Confirmed",
                "2027-01-02T10:00:00", Map.of()));
        people.add(oldPerson(existing.getId().getValue(), "Id", "Clash", "clash@" + org + ".example", null, null));
        final String mo = uuid();
        people.add(oldPerson(mo, "Mo", "Merge", "mo@" + org + ".example", null, null));
        final ImportMapping mapping = new ImportMapping(org, null, Map.of(mo, "nobody@example.com"), null,
                Map.of(uuid(), uuid()), Map.of(ids.lone(), uuid()), 2, null, null, null, null);
        final ImportPlan plan = planner(mapping).plan(export(root));
        final String all = String.join("\n", plan.blockers());
        assertTrue(all.contains("different name"), all);
        assertTrue(all.contains("share the email"), all);
        assertTrue(all.contains("deleted on the old site but still has a registration"), all);
        assertTrue(all.contains("already used by a different person"), all);
        assertTrue(all.contains("which no person in the target holds"), all);
        assertTrue(all.contains("links them under"), all);
        assertTrue(all.contains("which an existing person already holds"), all);
        assertTrue(plan.notes().stream().anyMatch(note -> note.contains("not in the export")), "an unknown sameAs");
        assertThrowsState(() -> applier().apply(plan, org));
    }

    @Test
    public void twoEqualRegistrationsForOnePersonAreTheOwnersCall() throws IOException {
        final ObjectNode root = exportTree();
        ((ArrayNode) root.get("registrations")).add(oldRegistration(ids.dup(), "Confirmed", "2027-01-05T10:00:00",
                Map.of()));
        ((ArrayNode) root.get("registrations")).add(oldRegistration(ids.p1(), "Confirmed", "2027-01-05T11:00:00",
                Map.of()));
        final ImportPlan plan = planner(mapping()).plan(export(root));
        assertTrue(plan.blockers().stream().anyMatch(blocker -> blocker.contains("decide which stands")),
                plan.blockers().toString());
    }

    // ------------------------------------------------------------------ apply, verify, re-apply, roll back

    @Test
    public void theApplyWritesThePlanAndTheExportsOwnTotalsMatch() throws IOException {
        final ImportMapping mapping = mapping();
        final LegacyExport export = export();
        final ImportPlan plan = planner(mapping).plan(export);
        final List<ImportReport> saved = new ArrayList<>();
        final ImportReport report = new ConferenceImportApplier(MAPPER, admin(), saved::add).apply(plan, org);
        assertEquals(report.getFailures(), List.of());
        assertFalse(saved.isEmpty(), "the report is saved as the phases go");
        final ConferenceImportVerifier verifier = new ConferenceImportVerifier(MAPPER, mapping);
        verifier.checkTarget(export, plan);
        assertEquals(verifier.failures(), 0, String.join("\n", verifier.lines()));
        assertEquals(DAO.getInstance().getPerson(existing.getId(), Cached.NO).orElseThrow().getCell(), "5551112222");
        assertEquals(DAO.getInstance().getPerson(existing.getId(), Cached.NO).orElseThrow().getBirthdate(),
                LocalDate.of(1970, 1, 1), "the existing value won");
        assertTrue(DAO.getInstance().getPrivilege(Privilege.idFor("checkinStaff", trip), Cached.NO).orElseThrow()
                .getPeople().contains(existing.getId()));

        final ImportReport again = new ConferenceImportApplier(MAPPER, admin(), done -> { }).apply(plan, org);
        assertEquals(again.getCreatedPeople().size() + again.getMergedPeople().size() + again.getMemberships().size()
                + again.getTrips().size() + again.getRegistrations().size() + again.getTransactions().size()
                + again.getPrivileges().size(), 0, "a second run writes nothing new: " + again);

        final ConferenceImportRollback rollback = new ConferenceImportRollback(MAPPER, AuditActor.system());
        rollback.undo(report);
        assertTrue(rollback.lines().stream().noneMatch(line -> line.startsWith("FAILED")), rollback.lines().toString());
        assertTrue(DAO.getInstance().getTrip(trip, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getRegistrations(trip, Cached.NO).isEmpty());
        assertNull(DAO.getInstance().getPerson(existing.getId(), Cached.NO).orElseThrow().getCell(),
                "the merged person restored");
        assertTrue(DAO.getInstance().getOrgMember(Organization.Id.from(org), existing.getId(), Cached.NO).isEmpty());
        final String script = ConferenceImportRollback.deleteScript(report);
        assertTrue(script.contains("--table-name people") && script.contains(ids.a()), script);
        assertTrue(script.contains("--table-name transactions") && script.contains("tx1"), script);
    }

    @Test
    public void aMergedPersonChangedSinceThePlanIsNotOverwritten() throws IOException {
        final ImportPlan plan = planner(mapping()).plan(export());
        existing.setNotes("edited after the plan");
        assertTrue(DAO.getInstance().savePerson(existing));
        final ImportReport report = new ConferenceImportApplier(MAPPER, admin(), done -> { }).apply(plan, org);
        assertTrue(report.getFailures().stream().anyMatch(failure -> failure.contains("changed since the plan")),
                report.getFailures().toString());
        assertEquals(DAO.getInstance().getPerson(existing.getId(), Cached.NO).orElseThrow().getNotes(),
                "edited after the plan");
    }

    @Test
    public void theApplyNeedsTheOrganization() throws IOException {
        final ImportPlan plan = planner(mapping()).plan(export());
        assertThrowsState(() -> applier().apply(plan, uuid()));
    }

    /** Every write that cannot happen is reported, never thrown: the run goes on and the report says what failed. */
    @Test
    public void everyRefusedWriteIsReported() throws IOException {
        final ObjectNode deleted = MAPPER.valueToTree(person("Gone", "Away", "gone." + org + "@example.com", null,
                null));
        deleted.put("deleted", "2026-07-14T10:00:00");
        final Person gone = MAPPER.treeToValue(deleted, Person.class);
        assertTrue(DAO.getInstance().savePerson(gone));
        final Person broken = Person.builder().first("No").last("Id").build();
        broken.setId(null);
        final ImportPlan.PersonStep merge = new ImportPlan.PersonStep("m", gone.getId(), ImportPlan.Action.MERGE,
                gone, gone, List.of(), List.of(), "test");
        final ImportPlan.PersonStep create = new ImportPlan.PersonStep("c", Person.Id.from(uuid()),
                ImportPlan.Action.CREATE, broken, null, List.of(), List.of(), "test");
        final ImportPlan plan = new ImportPlan(Map.of("m", gone.getId()), List.of(merge, create),
                List.of(new ImportPlan.FamilyLink(gone.getId(), Person.Id.from(uuid()), "test")),
                List.of(), List.of(), List.of(),
                List.of(new ImportPlan.RoleStep("tripMgr", "not-a-uuid", List.of(gone.getId()))), List.of(), List.of(),
                List.of());
        final ImportReport report = applier().apply(plan, org);
        final String all = String.join("\n", report.getFailures());
        assertTrue(all.contains("the person to merge into is gone"), all);
        assertTrue(all.contains("person c"), all);
        assertTrue(all.contains("a person is missing"), all);
        assertTrue(all.contains("membership") && all.contains("refused"), all);
        assertTrue(all.contains("role tripMgr@not-a-uuid"), all);
    }

    /** The membership and family writers refuse a caller who is not a site admin; the report says so. */
    @Test
    public void onlyASiteAdminMayApply() throws IOException {
        final ImportPlan plan = planner(mapping()).plan(export());
        final Caller ordinary = new Caller(operator.getId(), false, new AuditActor(operator.getEmail(),
                operator.getId().getValue()), new PrivilegeCommands());
        final ImportReport report = new ConferenceImportApplier(MAPPER, ordinary, done -> { }).apply(plan, org);
        final String all = String.join("\n", report.getFailures());
        assertTrue(all.contains("membership") && all.contains("refused"), all);
        assertTrue(all.contains("family") && all.contains("refused"), all);
    }

    /** The verifier is only worth running if it fails: every kind of drift it watches is caught. */
    @Test
    public void theVerifierCatchesDrift() throws IOException {
        final ImportMapping mapping = mapping();
        final LegacyExport export = export();
        final ImportPlan plan = planner(mapping).plan(export);
        applier().apply(plan, org);

        plan.stepFor(ids.a()).person().setCell("0000000000");
        plan.stepFor(ids.p3()).person().setAddress(null);
        plan.transactions().get(0).tx().setAmount(1f);
        final List<Registration> regs = new ArrayList<>(plan.registrations());
        regs.set(0, regs.get(0).withOptions(Map.of()));
        final ImportPlan drifted = new ImportPlan(plan.idMap(), plan.people(), plan.families(), plan.trips(), regs,
                plan.transactions(), plan.roles(), plan.review(), plan.notes(), List.of("pending"));
        final ConferenceImportVerifier planCheck = new ConferenceImportVerifier(MAPPER, mapping);
        planCheck.checkPlan(export, drifted);
        assertEquals(planCheck.failures(), 4, String.join("\n", planCheck.lines()));

        final Person.Id kid = plan.idMap().get(ids.kid());
        final Person kidRow = DAO.getInstance().getPerson(kid, Cached.NO).orElseThrow();
        kidRow.setFamilyId(null);
        assertTrue(DAO.getInstance().savePerson(kidRow));
        assertTrue(DAO.getInstance().deleteOrgMember(Organization.Id.from(org), kid));
        assertTrue(DAO.getInstance().deleteRegistration(trip, kid));
        assertTrue(DAO.getInstance().deletePrivilege(Privilege.idFor("tripMgr", trip)));
        final Transaction tx1 = DAO.getInstance().getTransaction(Person.Id.from(ids.a()), "tx1", Cached.NO)
                .orElseThrow();
        tx1.setAmount(999f);
        assertTrue(DAO.getInstance().saveTransaction(tx1));
        final ConferenceImportVerifier target = new ConferenceImportVerifier(MAPPER, mapping);
        target.checkTarget(export, drifted);
        final String all = String.join("\n", target.lines());
        for (final String caught : List.of("FAIL every person holds", "FAIL every imported person is a member",
                "FAIL every planned family link", "FAIL every registration is stored", "registrations by status",
                "FAIL ledger totals per trip", "FAIL every person's trip ledger", "FAIL every migrated trip role")) {
            assertTrue(all.contains(caught), caught + " in:\n" + all);
        }
    }

    /** The snapshot carries the merged people's families and memberships, and seeds what the store lacks. */
    @Test
    public void theSnapshotCarriesWhatTheMergesTouch() throws IOException {
        final org.paulsens.trip.model.Family family = new org.paulsens.trip.model.Family(null,
                List.of(existing.getId()), List.of(existing.getId()), existing.getId(), null, 0L);
        assertTrue(DAO.getInstance().saveFamily(family));
        existing.setFamilyId(family.getId());
        assertTrue(DAO.getInstance().savePerson(existing));
        assertTrue(DAO.getInstance().saveOrgMember(new org.paulsens.trip.model.OrgMember(Organization.Id.from(org),
                existing.getId(), java.time.LocalDateTime.now())));
        final ImportPlan plan = planner(mapping()).plan(export());
        final ImportSnapshot snapshot = ImportSnapshot.of(plan, org, operator);
        assertEquals(snapshot.families().size(), 1);
        assertEquals(snapshot.members(), List.of(existing.getId().getValue()));
        final org.paulsens.trip.model.Family absent = new org.paulsens.trip.model.Family(null,
                List.of(operator.getId()), List.of(operator.getId()), operator.getId(), null, 4L);
        final Person newcomer = Person.builder().first("New").last("Comer").build();
        new ImportSnapshot(snapshot.organization(), List.of(absent), snapshot.members(), newcomer).seed(plan);
        assertTrue(DAO.getInstance().getFamily(absent.getId(), Cached.NO).isPresent(), "seeded as new");
        assertTrue(DAO.getInstance().getPerson(newcomer.getId(), Cached.NO).isPresent());
    }

    /** A role that existed before the import gets its old holders back on rollback. */
    @Test
    public void aRoleThatExistedIsRestoredOnRollback() throws IOException {
        final PrivilegeCommands privileges = new PrivilegeCommands();
        assertTrue(privileges.savePrivilege(privileges.getOrCreate("tripMgr", trip, "before")
                .withNewPerson(operator.getId()), AuditActor.system()));
        final ImportPlan plan = planner(mapping()).plan(export());
        final ImportReport report = applier().apply(plan, org);
        new ConferenceImportRollback(MAPPER, AuditActor.system()).undo(report);
        assertEquals(DAO.getInstance().getPrivilege(Privilege.idFor("tripMgr", trip), Cached.NO).orElseThrow()
                .getPeople(), List.of(operator.getId()));
    }

    // ------------------------------------------------------------------ the command line

    @Test
    public void theCommandLineRunsTheWholeProcedure() throws IOException {
        final Path dir = Files.createTempDirectory("conf-import");
        final Path exportDir = writeExport(dir.resolve("export"), exportTree());
        final Path mappingFile = dir.resolve("mapping.json");
        MAPPER.writeValue(mappingFile.toFile(), mapping());
        final Path out = dir.resolve("out");
        final String[] base = {"--export", exportDir.toString(), "--mapping", mappingFile.toString(), "--out",
                out.toString(), "--actor", operator.getEmail()};
        assertEquals(cli("plan", base), 0, output);
        assertTrue(Files.exists(out.resolve("plan.json")) && Files.exists(out.resolve("snapshot.json")));
        assertTrue(Files.readString(out.resolve("people.tsv")).contains(ids.a()));
        assertEquals(cli("rehearse", base), 0, output);
        assertTrue(output.contains("REHEARSAL PASSED"), output);
        assertEquals(cli("apply", base), 1, "apply needs --yes");
        assertEquals(cli("apply", concat(base, "--yes")), 0, output);
        assertTrue(Files.exists(out.resolve("report.json")) && Files.exists(out.resolve("rollback-deletes.sh")));
        assertEquals(cli("verify", base), 0, output);
        assertEquals(cli("rollback", concat(base, "--yes")), 0, output);
        assertTrue(DAO.getInstance().getTrip(trip, Cached.NO).isEmpty());
    }

    @Test
    public void theCommandLineRefusesWhatItCannotDo() {
        final ConferenceImportCli cli = new ConferenceImportCli(MAPPER, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(cli.run(new String[0]), 1);
        assertEquals(cli.run(new String[] {"frobnicate"}), 1);
        assertEquals(cli.run(new String[] {"plan", "--export", "/nonexistent"}), 1, "no --mapping");
        assertEquals(cli.run(new String[] {"rollback", "--out", "/nonexistent", "--yes"}), 1, "no report");
        assertEquals(cli.run(new String[] {"apply", "--yes", "--out", "x", "--mapping", "/nonexistent"}), 1);
    }

    // ------------------------------------------------------------------ the fixture

    private String output = "";

    private int cli(final String command, final String... args) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int code = new ConferenceImportCli(MAPPER, new PrintStream(bytes, true, StandardCharsets.UTF_8))
                .run(concat(new String[] {command}, args));
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    private ImportMapping mapping() {
        return new ImportMapping(org, java.util.Set.of(ids.skip()), Map.of(ids.o(), overridden.getEmail()), null,
                Map.of(ids.dup(), ids.p1()), null, 2, Map.of("x", "Legacy X"), Map.of("tripCheckin", "checkinStaff"),
                null, Map.of(ids.p1() + ",tx3", trip));
    }

    private ConferenceImportPlanner planner(final ImportMapping mapping) {
        return new ConferenceImportPlanner(MAPPER, mapping, new ConferenceImportCli.DaoTarget());
    }

    private ConferenceImportApplier applier() {
        return new ConferenceImportApplier(MAPPER, admin(), done -> { });
    }

    private Caller admin() {
        return new Caller(operator.getId(), true, new AuditActor(operator.getEmail(), operator.getId().getValue()),
                new PrivilegeCommands());
    }

    private LegacyExport export() throws IOException {
        return export(exportTree());
    }

    private LegacyExport export(final ObjectNode root) throws IOException {
        return LegacyExport.read(writeExport(Files.createTempDirectory("export"), root), MAPPER);
    }

    /** The synthetic legacy tables: one conference, people for every rule, their registrations, ledger and roles. */
    private ObjectNode exportTree() {
        final ObjectNode root = MAPPER.createObjectNode();
        final ArrayNode people = root.putArray("people");
        final ObjectNode a = oldPerson(ids.a(), "Ann", "Anchor", "ann." + org + "@example.com", "(555) 000-0001",
                "1980-05-05");
        a.putArray("managedUsers").add(ids.kid2());
        people.add(a);
        people.add(oldPerson(ids.e(), "Eve", "Existing", existing.getEmail().toUpperCase(), "5551112222",
                "1971-01-01"));
        people.add(oldPerson(ids.o(), "Olga", "Override", "olga." + org + "@example.com", null, null));
        people.add(oldPerson(ids.kid(), "Kit", "Child", signin("kit"), "5550000001", "2015-01-01"));
        people.add(oldPerson(ids.kid2(), "Kai", "Managed", signin("kai"), null, null));
        people.add(oldPerson(ids.same(), "ann", " anchor ", signin("ann.signin"), "5550000001", "1980-05-05"));
        people.add(oldPerson(ids.baby(), "Ann", "Anchor", signin("baby.ann"), "5550000001", "2025-04-01"));
        people.add(oldPerson(ids.tie(), "Tia", "Tie", signin("tie-signin"), "5550000002", null));
        people.add(oldPerson(ids.p1(), "Pam", "One", "pam." + org + "@example.com", "5550000002", null));
        people.add(oldPerson(ids.p2(), "Pat", "Two", "pat." + org + "@example.com", "5550000002", null));
        final ObjectNode strong = oldPerson(ids.strong(), "Sid", "Strongfam", signin("sid"), "5550000003", null);
        strong.putObject("address").put("street", "1 Main").put("zip", "11111");
        people.add(strong);
        final ObjectNode p3 = oldPerson(ids.p3(), "Phil", "Three", "phil." + org + "@example.com", "5550000003", null);
        p3.putObject("address").put("street", "1 main ").put("zip", "11111");
        people.add(p3);
        people.add(oldPerson(ids.p4(), "Sue", "Strongfam", "sue." + org + "@example.com", null, null));
        people.add(oldPerson(ids.lone(), "Lou", "Lone", signin("lone"), null, null));
        final ObjectNode del = oldPerson(ids.del(), "Del", "Gone", "del." + org + "@example.com", null, null);
        del.put("deleted", "2026-07-14T10:00:00");
        people.add(del);
        people.add(oldPerson(ids.skip(), "Skip", "Me", "skip." + org + "@example.com", null, null));
        people.add(oldPerson(ids.dup(), "Pam", "One", "pam2." + org + "@example.com", "5559999999", null));

        final ObjectNode t = MAPPER.createObjectNode();
        t.put("id", trip).put("title", "Fest " + trip).put("description", "<p>old</p>")
                .put("startDate", "2027-03-12T00:00:00").put("endDate", "2027-03-14T00:00:00");
        t.putArray("people").add(ids.a()).add(ids.kid()).add(ids.same()).add(ids.skip());
        final ArrayNode questions = t.putArray("regOptions");
        questions.addObject().put("id", 0).put("shortDesc", "Diet").put("show", true);
        questions.addObject().put("id", 9).put("shortDesc", "Hotel?").put("show", false);
        final ArrayNode admissions = t.putArray("admissionOptions");
        admissions.addObject().put("id", "full").put("label", "Full weekend").put("price", 150).put("show", true);
        admissions.addObject().put("id", "fri").put("label", "Friday").put("price", 60.5).put("show", false);
        t.putArray("discountCodes").addObject().put("id", "code0");
        t.put("childPriceCap", 50);
        root.putArray("trips").add(t);

        final ArrayNode regs = root.putArray("registrations");
        regs.add(oldRegistration(ids.a(), "Confirmed", "2027-01-01T10:00:00", Map.of("0", "veg", "_admission",
                "full", "_checkedIn", "2027-03-12T09:14:00", "opt9", "true")));
        regs.add(oldRegistration(ids.same(), "Pending", "2027-01-01T10:05:00", Map.of("0", "none")));
        regs.add(oldRegistration(ids.kid(), "Confirmed", "2027-01-01T10:06:00", Map.of("_admission", "fri")));
        regs.add(oldRegistration(ids.strong(), "Confirmed", "2027-01-03T08:00:00", Map.of()));
        regs.add(oldRegistration(ids.p4(), "Confirmed", "2027-01-03T08:30:00", Map.of()));
        regs.add(oldRegistration(ids.e(), "Pending", "2027-01-04T08:30:00", Map.of("_regType", "x")));
        regs.add(oldRegistration(ids.skip(), "Confirmed", "2027-01-04T09:30:00", Map.of()));
        regs.add(oldRegistration(uuid(), "Confirmed", "2027-01-04T09:30:00", Map.of()));   // nobody: noted

        final ArrayNode txs = root.putArray("transactions");
        final ArrayNode bindings = root.putArray("bindings");
        tx(txs, bindings, ids.a(), "tx1", 150.0, null, null, true);
        tx(txs, bindings, ids.a(), "tx2", 10.0, null, "2027-01-02T00:00:00", true);
        tx(txs, bindings, ids.a(), "txS1", 100.0, "G", null, true);
        tx(txs, bindings, ids.kid(), "txS2", 100.0, "G", null, true);
        tx(txs, bindings, ids.p1(), "tx3", 221.25, null, null, false);
        tx(txs, bindings, ids.skip(), "tx4", 99.0, null, null, true);

        final ArrayNode privs = root.putArray("privs");
        privs.addObject().put("name", "tripMgr" + trip).putArray("people").add(ids.a());
        privs.addObject().put("name", "tripCheckin" + trip).putArray("people").add(ids.e()).add(uuid());
        privs.addObject().put("name", "orgAdmin").putArray("people").add(ids.a());
        return root;
    }

    private void tx(final ArrayNode txs, final ArrayNode bindings, final String user, final String txId,
            final double amount, final String group, final String deleted, final boolean bound) {
        final ObjectNode tx = MAPPER.createObjectNode().put("txId", txId).put("userId", user).put("amount", amount)
                .put("category", "Payment").put("txType", "Payment").put("txDate", "2027-01-01T12:00:00")
                .put("note", "note " + txId).put("type", group == null ? "Tx" : "Shared");
        if (group != null) {
            tx.put("groupId", group);
        }
        if (deleted != null) {
            tx.put("deleted", deleted);
        }
        txs.add(tx);
        if (bound) {
            bindings.add(binding("4_" + user + "," + txId, "TRANSACTION", "5_" + trip, "TRIP"));
            bindings.add(binding("5_" + trip, "TRIP", "4_" + user + "," + txId, "TRANSACTION"));
        }
    }

    private static ObjectNode binding(final String id1, final String type1, final String id2, final String type2) {
        final ObjectNode item = MAPPER.createObjectNode();
        item.putObject("id1").put("S", id1);
        item.putObject("id1_type").put("S", type1);
        item.putObject("id2").put("S", id2);
        item.putObject("id2_type").put("S", type2);
        return item;
    }

    private ObjectNode oldRegistration(final String user, final String status, final String created,
            final Map<String, String> options) {
        final ObjectNode reg = MAPPER.createObjectNode().put("tripId", trip).put("userId", user)
                .put("status", status).put("created", created);
        reg.set("options", MAPPER.valueToTree(options));
        return reg;
    }

    private static ObjectNode oldPerson(final String id, final String first, final String last, final String email,
            final String cell, final String birthdate) {
        final ObjectNode person = MAPPER.createObjectNode().put("id", id).put("first", first).put("last", last)
                .put("email", email);
        if (cell != null) {
            person.put("cell", cell);
        }
        if (birthdate != null) {
            person.put("birthdate", birthdate);
        }
        person.putObject("address");
        person.putArray("managedUsers");
        return person;
    }

    /** Writes the tables the way {@code aws dynamodb scan} does: {@code {"Items":[{"content":{"S":"..."}}]}}. */
    private static Path writeExport(final Path dir, final ObjectNode root) throws IOException {
        Files.createDirectories(dir);
        for (final String table : List.of("trips", "people", "registrations", "transactions", "privs")) {
            final ObjectNode file = MAPPER.createObjectNode();
            final ArrayNode items = file.putArray("Items");
            for (final com.fasterxml.jackson.databind.JsonNode row : root.withArray(table)) {
                items.addObject().putObject("content").put("S", MAPPER.writeValueAsString(row));
            }
            MAPPER.writeValue(dir.resolve(table + ".json").toFile(), file);
        }
        final ObjectNode bindings = MAPPER.createObjectNode();
        bindings.set("Items", root.withArray("bindings"));
        MAPPER.writeValue(dir.resolve("bindings.json").toFile(), bindings);
        return dir;
    }

    private Person person(final String first, final String last, final String email, final LocalDate born,
            final String cell) throws IOException {
        final Person person = Person.builder().first(first).last(last).email(email).birthdate(born).cell(cell)
                .build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static ImportPlan.Action action(final ImportPlan plan, final String oldId) {
        return Optional.ofNullable(plan.stepFor(oldId)).map(ImportPlan.PersonStep::action).orElse(null);
    }

    private static String anchorOf(final ImportPlan plan, final String member) {
        return plan.families().stream().filter(link -> link.member().getValue().equals(member))
                .map(link -> link.anchor().getValue()).findFirst().orElse(null);
    }

    private static Registration registration(final ImportPlan plan, final String oldId) {
        final Person.Id target = plan.idMap().get(oldId);
        return plan.registrations().stream().filter(reg -> reg.getUserId().equals(target)).findFirst().orElseThrow();
    }

    private static String text(final ObjectNode node, final String field) {
        return node.get(field).asText();
    }

    /** A sign-in name unique to this test (the store is shared by every test in the run). */
    private String signin(final String name) {
        return name + "-" + org.substring(0, 8);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String[] concat(final String[] first, final String... rest) {
        final String[] all = new String[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

    private static void assertThrowsState(final Runnable call) {
        try {
            call.run();
        } catch (final IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalStateException");
    }
}
