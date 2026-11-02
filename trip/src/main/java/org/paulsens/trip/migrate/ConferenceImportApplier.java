package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.BindingCommands;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.FamilyCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;

/**
 * Writes an {@link ImportPlan} (docs/migrations/conference-import.md). Re-runnable: a row that is already there is
 * left alone, so a stopped run is finished by running it again. Each kind of row goes through the writer the app
 * itself uses for it: memberships through {@code OrgCommands}, families through {@code FamilyCommands.adminLink},
 * bindings through {@code BindingCommands}, roles through {@code PrivilegeCommands}; people, trips, registrations
 * and ledger rows are written as planned. The report is saved after every phase.
 *
 * <p>{@link #patchLegacyPricing} is the one exception to "already there is left alone": it carries the legacy
 * pricing onto trips and registrations an earlier run imported without it (the 2026-11-01 production run).
 */
@Slf4j
public class ConferenceImportApplier {
    static final String HOW = "conference import";
    static final String CHANGED = "changed since it was read; re-run";

    /** The answer keys the legacy-pricing patch moves off a registration, as the planner does on a fresh import. */
    private static final List<String> LEGACY_ANSWERS = List.of(ConferenceImportPlanner.REGISTRANT_TYPE_KEY,
            ConferenceImportPlanner.DISCOUNT_KEY, ConferenceImportPlanner.DISCOUNT_CODE_KEY);

    private final ObjectMapper mapper;
    private final Supplier<Caller> callerSource;
    private final AuditActor actor;
    private final Consumer<ImportReport> save;
    private final List<String> patchLines = new ArrayList<>();

    /**
     * @param caller a SITE ADMIN caller (the operator): the membership and family writers check for one
     * @param save   called with the report after every phase
     */
    public ConferenceImportApplier(final ObjectMapper mapper, final Caller caller, final Consumer<ImportReport> save) {
        this.mapper = mapper;
        this.callerSource = () -> caller;
        this.actor = caller.auditActor();
        this.save = save;
    }

    /** Writes the plan; refuses one with blockers. */
    public ImportReport apply(final ImportPlan plan, final String targetOrgId) {
        if (!plan.blockers().isEmpty()) {
            throw new IllegalStateException("The plan has " + plan.blockers().size()
                    + " blockers; resolve them first.");
        }
        if (DAO.getInstance().getOrganization(Organization.Id.from(targetOrgId), Cached.NO).isEmpty()) {
            throw new IllegalStateException("No organization " + targetOrgId + " in the target.");
        }
        final ImportReport report = new ImportReport();
        report.setStartedAt(LocalDateTime.now().toString());
        report.setTargetOrgId(targetOrgId);
        writePeople(plan, report);
        save.accept(report);
        writeMemberships(plan, targetOrgId, report);
        save.accept(report);
        writeFamilies(plan, report);
        save.accept(report);
        writeTrips(plan, report);
        writeRegistrations(plan, report);
        save.accept(report);
        writeTransactions(plan, report);
        save.accept(report);
        writeRoles(plan, report);
        report.setFinishedAt(LocalDateTime.now().toString());
        save.accept(report);
        return report;
    }

    // ------------------------------------------------------------------ people

    private void writePeople(final ImportPlan plan, final ImportReport report) {
        for (final ImportPlan.PersonStep step : plan.people()) {
            attempt(report, "person " + step.oldId(), () -> writePerson(step, report));
        }
    }

    private void writePerson(final ImportPlan.PersonStep step, final ImportReport report) throws IOException {
        if (step.action() != ImportPlan.Action.CREATE && step.action() != ImportPlan.Action.MERGE) {
            return;
        }
        final Person live = DAO.getInstance().getPerson(step.targetId(), Cached.NO).orElse(null);
        if (step.action() == ImportPlan.Action.CREATE) {
            if (live != null) {
                report.getSkipped().add("person " + step.oldId() + ": already present");
            } else if (DAO.getInstance().savePerson(step.person())) {
                report.getCreatedPeople().add(step.targetId().getValue());
            } else {
                report.getFailures().add("person " + step.oldId() + ": save refused");
            }
            return;
        }
        if (live == null) {
            report.getFailures().add("person " + step.oldId() + ": the person to merge into is gone");
        } else if (sameRow(live, step.person())) {
            report.getSkipped().add("person " + step.oldId() + ": merge already applied");
        } else if (!sameRow(live, step.before())) {
            report.getFailures().add("person " + step.oldId() + ": " + step.targetId().getValue()
                    + " changed since the plan; re-plan");
        } else if (DAO.getInstance().savePerson(step.person())) {
            report.getMergedPeople().add(new ImportReport.Before(step.targetId().getValue(), tree(live)));
        } else {
            report.getFailures().add("person " + step.oldId() + ": merge save refused");
        }
    }

    /** Equal as stored, ignoring what later phases write (orgs, family). */
    private boolean sameRow(final Person one, final Person two) {
        final JsonNode a = tree(one);
        final JsonNode b = tree(two);
        for (final String ignored : List.of("orgIds", "familyId", "managedUsers")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) a).remove(ignored);
            ((com.fasterxml.jackson.databind.node.ObjectNode) b).remove(ignored);
        }
        return a.equals(b);
    }

    private void writeMemberships(final ImportPlan plan, final String orgId, final ImportReport report) {
        final OrgCommands orgs = new OrgCommands(callerSource);
        final Organization.Id org = Organization.Id.from(orgId);
        for (final Person.Id person : new java.util.LinkedHashSet<>(plan.idMap().values())) {
            if (DAO.getInstance().getOrgMember(org, person, Cached.NO).isPresent()) {
                continue;
            }
            if (orgs.importMembership(orgId, person, HOW)) {
                report.getMemberships().add(person.getValue());
            } else {
                report.getFailures().add("membership " + person.getValue() + ": refused");
            }
        }
    }

    // ------------------------------------------------------------------ families

    private void writeFamilies(final ImportPlan plan, final ImportReport report) {
        final FamilyCommands families = new FamilyCommands(new ConfigCommands(), new AuditCommands(), callerSource);
        for (final ImportPlan.FamilyLink link : plan.families()) {
            final Person anchor = DAO.getInstance().getPerson(link.anchor(), Cached.NO).orElse(null);
            final Person member = DAO.getInstance().getPerson(link.member(), Cached.NO).orElse(null);
            if (anchor == null || member == null) {
                report.getFailures().add("family " + link.member().getValue() + ": a person is missing");
                continue;
            }
            final Family.Id familyBefore = anchor.getFamilyId();
            if (linked(familyBefore, member)) {
                continue;
            }
            recordFamily(report, familyBefore, anchor);
            report.getFamilyMembers().add(new ImportReport.Before(member.getId().getValue(), tree(member)));
            if (!families.adminLink(link.anchor(), link.member(), false)) {
                report.getFailures().add("family " + link.member().getValue() + " -> " + link.anchor().getValue()
                        + ": refused (" + link.evidence() + "); see the log");
            }
        }
    }

    /** Already in the anchor's family: the row exists and lists them (a pointer alone can dangle after a rollback). */
    private static boolean linked(final Family.Id family, final Person member) {
        return family != null && family.equals(member.getFamilyId()) && DAO.getInstance().getFamily(family, Cached.NO)
                .map(row -> row.getMemberIds().contains(member.getId())).orElse(false);
    }

    /** The anchor's family as it was, once per family: {@code null} before-image for one the import creates. */
    private void recordFamily(final ImportReport report, final Family.Id familyId, final Person anchor) {
        final String key = familyId == null ? "new:" + anchor.getId().getValue() : familyId.getValue();
        if (report.getFamilies().stream().anyMatch(seen -> seen.key().equals(key))) {
            return;
        }
        final JsonNode before = familyId == null ? null
                : DAO.getInstance().getFamily(familyId, Cached.NO).map(this::tree).orElse(null);
        report.getFamilies().add(new ImportReport.Before(key, before));
        if (familyId == null) {
            report.getFamilyMembers().add(new ImportReport.Before(anchor.getId().getValue(), tree(anchor)));
        }
    }

    // ------------------------------------------------------------------ trips, registrations, ledger, roles

    private void writeTrips(final ImportPlan plan, final ImportReport report) {
        for (final Trip trip : plan.trips()) {
            attempt(report, "trip " + trip.getId(), () -> writeTrip(trip, report));
        }
    }

    private void writeTrip(final Trip trip, final ImportReport report) throws IOException {
        if (DAO.getInstance().getTrip(trip.getId(), Cached.NO).isPresent()) {
            report.getSkipped().add("trip " + trip.getId() + ": already present");
        } else if (DAO.getInstance().saveTrip(trip)) {
            report.getTrips().add(trip.getId());
        } else {
            report.getFailures().add("trip " + trip.getId() + ": save refused");
        }
    }

    private void writeRegistrations(final ImportPlan plan, final ImportReport report) {
        for (final Registration reg : plan.registrations()) {
            attempt(report, "registration " + reg.getTripId() + "/" + reg.getUserId().getValue(),
                    () -> writeRegistration(reg, report));
        }
    }

    private void writeRegistration(final Registration reg, final ImportReport report) throws IOException {
        final String key = reg.getTripId() + "/" + reg.getUserId().getValue();
        if (DAO.getInstance().getRegistration(reg.getTripId(), reg.getUserId(), Cached.NO).isPresent()) {
            report.getSkipped().add("registration " + key + ": already present");
        } else if (DAO.getInstance().saveRegistration(reg)) {
            report.getRegistrations().add(new ImportReport.Key(reg.getTripId(), reg.getUserId().getValue(),
                    reg.getTripId()));
        } else {
            report.getFailures().add("registration " + key + ": save refused");
        }
    }

    private void writeTransactions(final ImportPlan plan, final ImportReport report) {
        final BindingCommands bind = new BindingCommands();
        for (final ImportPlan.TxStep step : plan.transactions()) {
            attempt(report, "transaction " + step.tx().getUserId().getValue() + "/" + step.tx().getTxId(),
                    () -> writeTransaction(bind, step, report));
        }
    }

    private static void writeTransaction(final BindingCommands bind, final ImportPlan.TxStep step,
            final ImportReport report) throws IOException {
        final Person.Id user = step.tx().getUserId();
        final String key = user.getValue() + "/" + step.tx().getTxId();
        if (alreadyThere(bind, step)) {
            report.getSkipped().add("transaction " + key + ": already present");
            bindToTrip(bind, step);   // a rolled-back run removes the binding, never the row
        } else if (!DAO.getInstance().saveTransaction(step.tx())) {
            report.getFailures().add("transaction " + key + ": save refused");
        } else {
            report.getTransactions().add(new ImportReport.Key(user.getValue(), step.tx().getTxId(), step.tripId()));
            bindToTrip(bind, step);
        }
    }

    private static void bindToTrip(final BindingCommands bind, final ImportPlan.TxStep step) {
        if (step.tripId() != null) {
            bind.setBindings(bind.key(step.tx().getUserId().getValue(), step.tx().getTxId()), BindingType.TRANSACTION,
                    BindingType.TRIP, List.of(step.tripId()), true);
        }
    }

    /**
     * Whether an earlier run wrote this row. The DAO never returns a soft-deleted ledger row (by design: nothing in
     * the app may see one), so a deleted row's trip binding, written right after the row, is its evidence.
     */
    private static boolean alreadyThere(final BindingCommands bind, final ImportPlan.TxStep step) {
        final Person.Id user = step.tx().getUserId();
        if (step.tx().getDeleted() == null || step.tripId() == null) {
            return DAO.getInstance().getTransaction(user, step.tx().getTxId(), Cached.NO).isPresent();
        }
        return DAO.getInstance().getBindings(bind.key(user.getValue(), step.tx().getTxId()), BindingType.TRANSACTION,
                BindingType.TRIP, Cached.NO).contains(step.tripId());
    }

    private void writeRoles(final ImportPlan plan, final ImportReport report) {
        final PrivilegeCommands privileges = new PrivilegeCommands();
        for (final ImportPlan.RoleStep role : plan.roles()) {
            attempt(report, "role " + role.base() + "@" + role.tripId(), () -> writeRole(privileges, role, report));
        }
    }

    private void writeRole(final PrivilegeCommands privileges, final ImportPlan.RoleStep role,
            final ImportReport report) {
        final Privilege before = privileges.getOrCreate(role.base(), role.tripId(),
                role.base() + " for trip " + role.tripId());
        Privilege after = before;
        for (final Person.Id person : role.people()) {
            after = after.withNewPerson(person);
        }
        if (after == before) {
            return;
        }
        report.getPrivileges().add(new ImportReport.Before(before.getId(),
                before.getPeople().isEmpty() ? null : tree(before)));
        if (!privileges.savePrivilege(after, actor)) {
            report.getFailures().add("role " + before.getId() + ": save refused");
        }
    }

    // ------------------------------------------------------------------ the legacy-pricing patch

    /**
     * Carries the legacy pricing onto trips and registrations an earlier run imported without it: each present
     * trip gets the planned checkout policy's bands and codes (only while its own policy holds none) and its
     * admission question becomes required with its choices' days (only while that question still reads exactly as
     * the import wrote it); each present registration gets its planned breakdown and loses its legacy pricing
     * answers (only while it has no breakdown). Every write happens only if the row is still what was read a
     * moment before. {@code write} false is a dry run: every decision is in {@link #patchLines()}, nothing is
     * saved. Refuses a plan with blockers.
     */
    public ImportReport patchLegacyPricing(final ImportPlan plan, final String targetOrgId, final boolean write) {
        if (!plan.blockers().isEmpty()) {
            throw new IllegalStateException("The plan has " + plan.blockers().size()
                    + " blockers; resolve them first.");
        }
        patchLines.clear();
        final ImportReport report = new ImportReport();
        report.setStartedAt(LocalDateTime.now().toString());
        report.setTargetOrgId(targetOrgId);
        for (final Trip trip : plan.trips()) {
            attempt(report, "patch trip " + trip.getId(), () -> patchTrip(trip, report, write));
        }
        if (write) {
            save.accept(report);
        }
        for (final Registration reg : plan.registrations()) {
            attempt(report, "patch registration " + reg.getTripId(), () -> patchRegistration(reg, report, write));
        }
        report.setFinishedAt(LocalDateTime.now().toString());
        if (write) {
            save.accept(report);
        }
        return report;
    }

    /** One line per row of the last {@link #patchLegacyPricing}: what was (or would be) patched, skipped, refused. */
    public List<String> patchLines() {
        return List.copyOf(patchLines);
    }

    private void patchTrip(final Trip want, final ImportReport report, final boolean write) throws IOException {
        final Trip live = DAO.getInstance().getTrip(want.getId(), Cached.NO).orElse(null);
        if (live == null) {
            skip(report, "trip " + want.getId() + ": not in the target (apply the import first)");
            return;
        }
        final JsonNode before = tree(live);
        final List<String> changes = new ArrayList<>();
        patchPolicy(want, live, changes, report);
        patchAdmissionQuestion(want, live, changes, report);
        if (changes.isEmpty()) {
            patchLines.add("unchanged trip " + want.getId() + ": nothing to patch");
            return;
        }
        patchLines.add((write ? "patched " : "would patch ") + "trip " + want.getId() + ": "
                + String.join("; ", changes));
        final JsonNode current = DAO.getInstance().getTrip(want.getId(), Cached.NO).map(this::tree).orElse(null);
        if (write && saveIfUnchanged(report, "trip " + want.getId(), before, current,
                () -> DAO.getInstance().saveTrip(live))) {
            report.getPatchedTrips().add(new ImportReport.Before(want.getId(), before));
        }
    }

    /**
     * The patch's conditional write: saves only while the row still reads as {@code before} did a moment ago (the
     * read-compare-write a merge gets, since neither row carries a version), reporting a changed row or a refused
     * save as a failure to re-run. Answers whether the row was saved.
     */
    private static boolean saveIfUnchanged(final ImportReport report, final String what, final JsonNode before,
            final JsonNode current, final Write write) throws IOException {
        if (!before.equals(current)) {
            report.getFailures().add(what + ": " + CHANGED);
            return false;
        }
        if (!Boolean.TRUE.equals(write.run())) {
            report.getFailures().add(what + ": save refused");
            return false;
        }
        return true;
    }

    /** One DAO save. */
    private interface Write {
        Boolean run() throws IOException;
    }

    /** One step of a phase, whose exception is reported, never thrown: the run goes on. */
    private interface Step {
        void run() throws IOException;
    }

    private static void attempt(final ImportReport report, final String what, final Step step) {
        try {
            step.run();
        } catch (final IOException | RuntimeException ex) {
            fail(report, what, ex);
        }
    }

    /** The planned bands and codes onto a policy holding none; one holding any is the admins' and is kept. */
    private void patchPolicy(final Trip want, final Trip live, final List<String> changes, final ImportReport report) {
        final CheckoutConfig planned = want.getCheckout();
        if (planned.getAgeBands().isEmpty() && planned.getCodes().isEmpty()) {
            return;
        }
        final CheckoutConfig policy = live.getCheckout();
        if (policy.getAgeBands().equals(planned.getAgeBands()) && policy.getCodes().equals(planned.getCodes())) {
            return;   // patched already
        }
        if (!policy.getAgeBands().isEmpty() || !policy.getCodes().isEmpty()) {
            skip(report, "trip " + want.getId() + ": its checkout policy already holds pricing (kept)");
            return;
        }
        policy.setAgeBands(planned.getAgeBands());
        policy.setCodes(planned.getCodes());
        changes.add(planned.getAgeBands().size() + " age bands, " + planned.getCodes().size() + " codes");
    }

    /** Required and days onto the admission question, while it still reads exactly as the import wrote it. */
    private void patchAdmissionQuestion(final Trip want, final Trip live, final List<String> changes,
            final ImportReport report) {
        final RegistrationOption planned = RegistrationOption.typeQuestion(want.getRegOptions());
        if (planned == null) {
            return;
        }
        final List<RegistrationOption> questions = live.getRegOptions();
        final int at = indexOf(questions, planned.getId());
        if (at < 0) {
            skip(report, "trip " + want.getId() + ": the admission question is gone (kept as is)");
        } else if (questions.get(at).equals(planned)) {
            return;   // patched already
        } else if (asImported(questions.get(at)).equals(asImported(planned))) {
            questions.set(at, planned);
            changes.add("admission question required, choice days");
        } else {
            skip(report, "trip " + want.getId() + ": the admission question was edited since the import (kept)");
        }
    }

    private static int indexOf(final List<RegistrationOption> questions, final int id) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    /** The question without what the patch adds: equal before and after, so a patched row is recognised too. */
    private RegistrationOption asImported(final RegistrationOption question) {
        final RegistrationOption copy = read(tree(question), RegistrationOption.class);
        copy.setRequired(null);
        copy.getChoices().forEach(choice -> choice.setDays(new ArrayList<>()));
        return copy;
    }

    private void patchRegistration(final Registration want, final ImportReport report, final boolean write)
            throws IOException {
        if (want.getPriceBreakdown() == null) {
            return;   // an unpriced trip: nothing to carry
        }
        final String key = want.getTripId() + "/" + want.getUserId().getValue();
        final Registration live = DAO.getInstance().getRegistration(want.getTripId(), want.getUserId(), Cached.NO)
                .orElse(null);
        if (live == null) {
            skip(report, "registration " + key + ": not in the target");
            return;
        }
        if (live.getPriceBreakdown() != null) {
            skip(report, "registration " + key + ": priced already (kept)");
            return;
        }
        final JsonNode before = tree(live);
        final Map<String, String> options = new HashMap<>(live.getOptions());
        LEGACY_ANSWERS.forEach(options::remove);
        final Registration patched = live.withPriceBreakdown(want.getPriceBreakdown()).withOptions(options);
        patchLines.add((write ? "patched " : "would patch ") + "registration " + key + ": "
                + want.getPriceBreakdown().summary());
        final JsonNode current = DAO.getInstance().getRegistration(want.getTripId(), want.getUserId(), Cached.NO)
                .map(this::tree).orElse(null);
        if (write && saveIfUnchanged(report, "registration " + key, before, current,
                () -> DAO.getInstance().saveRegistration(patched))) {
            report.getPatchedRegistrations().add(new ImportReport.Before(key, before));
        }
    }

    private void skip(final ImportReport report, final String what) {
        report.getSkipped().add(what);
        patchLines.add("skipped " + what);
    }

    private <T> T read(final JsonNode tree, final Class<T> type) {
        try {
            return mapper.treeToValue(tree, type);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("Unreadable row: " + tree, ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode tree(final Object row) {
        return mapper.valueToTree(row);
    }

    private static void fail(final ImportReport report, final String what, final Exception ex) {
        log.error("Import write failed: {}", what, ex);
        report.getFailures().add(what + ": " + ex);
    }
}
