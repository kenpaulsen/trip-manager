package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
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
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;

/**
 * Writes an {@link ImportPlan} (docs/migrations/conference-import.md). Re-runnable: a row that is already there is
 * left alone, so a stopped run is finished by running it again. Each kind of row goes through the writer the app
 * itself uses for it: memberships through {@code OrgCommands}, families through {@code FamilyCommands.adminLink},
 * bindings through {@code BindingCommands}, roles through {@code PrivilegeCommands}; people, trips, registrations
 * and ledger rows are written as planned. The report is saved after every phase.
 */
@Slf4j
public class ConferenceImportApplier {
    static final String HOW = "conference import";

    private final ObjectMapper mapper;
    private final Supplier<Caller> callerSource;
    private final AuditActor actor;
    private final Consumer<ImportReport> save;

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
            try {
                writePerson(step, report);
            } catch (final IOException | RuntimeException ex) {
                fail(report, "person " + step.oldId(), ex);
            }
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
            try {
                if (DAO.getInstance().getTrip(trip.getId(), Cached.NO).isPresent()) {
                    report.getSkipped().add("trip " + trip.getId() + ": already present");
                } else if (DAO.getInstance().saveTrip(trip)) {
                    report.getTrips().add(trip.getId());
                } else {
                    report.getFailures().add("trip " + trip.getId() + ": save refused");
                }
            } catch (final IOException | RuntimeException ex) {
                fail(report, "trip " + trip.getId(), ex);
            }
        }
    }

    private void writeRegistrations(final ImportPlan plan, final ImportReport report) {
        for (final Registration reg : plan.registrations()) {
            final String key = reg.getTripId() + "/" + reg.getUserId().getValue();
            try {
                if (DAO.getInstance().getRegistration(reg.getTripId(), reg.getUserId(), Cached.NO).isPresent()) {
                    report.getSkipped().add("registration " + key + ": already present");
                } else if (DAO.getInstance().saveRegistration(reg)) {
                    report.getRegistrations().add(new ImportReport.Key(reg.getTripId(), reg.getUserId().getValue(),
                            reg.getTripId()));
                } else {
                    report.getFailures().add("registration " + key + ": save refused");
                }
            } catch (final IOException | RuntimeException ex) {
                fail(report, "registration " + key, ex);
            }
        }
    }

    private void writeTransactions(final ImportPlan plan, final ImportReport report) {
        final BindingCommands bind = new BindingCommands();
        for (final ImportPlan.TxStep step : plan.transactions()) {
            final Person.Id user = step.tx().getUserId();
            final String key = user.getValue() + "/" + step.tx().getTxId();
            try {
                if (alreadyThere(bind, step)) {
                    report.getSkipped().add("transaction " + key + ": already present");
                    bindToTrip(bind, step);   // a rolled-back run removes the binding, never the row
                    continue;
                }
                if (!DAO.getInstance().saveTransaction(step.tx())) {
                    report.getFailures().add("transaction " + key + ": save refused");
                    continue;
                }
                report.getTransactions().add(new ImportReport.Key(user.getValue(), step.tx().getTxId(), step.tripId()));
                bindToTrip(bind, step);
            } catch (final IOException | RuntimeException ex) {
                fail(report, "transaction " + key, ex);
            }
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
            try {
                final Privilege before = privileges.getOrCreate(role.base(), role.tripId(),
                        role.base() + " for trip " + role.tripId());
                Privilege after = before;
                for (final Person.Id person : role.people()) {
                    after = after.withNewPerson(person);
                }
                if (after == before) {
                    continue;
                }
                report.getPrivileges().add(new ImportReport.Before(before.getId(),
                        before.getPeople().isEmpty() ? null : tree(before)));
                if (!privileges.savePrivilege(after, actor)) {
                    report.getFailures().add("role " + before.getId() + ": save refused");
                }
            } catch (final RuntimeException ex) {
                fail(report, "role " + role.base() + "@" + role.tripId(), ex);
            }
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
