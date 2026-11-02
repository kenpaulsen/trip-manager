package org.paulsens.trip.migrate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;

/**
 * Undoes an apply from its {@link ImportReport} (docs/migrations/conference-import.md), newest phase first. What the
 * DAO can delete is deleted here (roles, ledger bindings, registrations, trips, families, memberships) and every
 * before-image is restored (roles, families, merged people). People and ledger rows the import CREATED have no
 * delete in the DAO by design, so {@link #deleteScript} writes them as key-only {@code aws dynamodb delete-item}
 * calls for the operator to review and run. Point-in-time recovery remains the backstop.
 */
@Slf4j
public class ConferenceImportRollback {
    private final ObjectMapper mapper;
    private final AuditActor actor;
    private final List<String> lines = new ArrayList<>();

    public ConferenceImportRollback(final ObjectMapper mapper, final AuditActor actor) {
        this.mapper = mapper;
        this.actor = actor;
    }

    /** What was undone, one line per row, failures marked. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** Undoes everything the DAO can; then run {@link #deleteScript}'s output. */
    public void undo(final ImportReport report) {
        final PrivilegeCommands privileges = new PrivilegeCommands();
        for (final ImportReport.Before role : report.getPrivileges()) {
            done("role " + role.key(), role.before() == null ? DAO.getInstance().deletePrivilege(role.key())
                    : privileges.savePrivilege(read(role.before(), Privilege.class), actor));
        }
        for (final ImportReport.Key tx : report.getTransactions()) {
            if (tx.tripId() != null) {
                final String key = tx.hash() + "," + tx.range();
                done("binding " + key, DAO.getInstance().removeBinding(key, BindingType.TRANSACTION, tx.tripId(),
                        BindingType.TRIP, true));
            }
        }
        for (final ImportReport.Key reg : report.getRegistrations()) {
            done("registration " + reg.hash() + "/" + reg.range(),
                    DAO.getInstance().deleteRegistration(reg.hash(), Person.Id.from(reg.range())));
        }
        for (final String tripId : report.getTrips()) {
            done("trip " + tripId, DAO.getInstance().getTrip(tripId, Cached.NO).map(DAO.getInstance()::deleteTrip)
                    .orElse(Boolean.TRUE));
        }
        undoFamilies(report);
        final Organization.Id org = Organization.Id.from(report.getTargetOrgId());
        for (final String person : report.getMemberships()) {
            done("membership " + person, DAO.getInstance().deleteOrgMember(org, Person.Id.from(person)));
        }
        for (final ImportReport.Before merged : report.getMergedPeople()) {
            done("restore person " + merged.key(), save(read(merged.before(), Person.class)));
        }
    }

    /** Families: a new one is deleted; a joined one gets its old members back; each member's pointer is restored. */
    private void undoFamilies(final ImportReport report) {
        for (final ImportReport.Before family : report.getFamilies()) {
            if (family.before() == null) {
                continue;   // created by the import: deleted below, once its id is known from a member
            }
            final Family before = read(family.before(), Family.class);
            final Family live = DAO.getInstance().getFamily(before.getId(), Cached.NO).orElse(null);
            done("restore family " + family.key(), live != null && saveFamily(new Family(before.getId(),
                    before.getMemberIds(), before.getManagerIds(), before.getCreatedBy(), before.getCreated(),
                    live.getVersion())));
        }
        for (final ImportReport.Before member : report.getFamilyMembers()) {
            final Person before = read(member.before(), Person.class);
            final Person live = DAO.getInstance().getPerson(before.getId(), Cached.NO).orElse(null);
            if (live != null && live.getFamilyId() != null && before.getFamilyId() == null
                    && createdFamily(report, live)) {
                done("family " + live.getFamilyId().getValue(), DAO.getInstance().deleteFamily(live.getFamilyId()));
            }
            if (live != null) {
                live.setFamilyId(before.getFamilyId());
                done("restore family pointer " + before.getId().getValue(), save(live));
            }
        }
    }

    /** Whether the live person's family is one the import created (its anchor had none before). */
    private static boolean createdFamily(final ImportReport report, final Person live) {
        return report.getFamilies().stream()
                .anyMatch(family -> family.before() == null && family.key().equals("new:" + live.getId().getValue()));
    }

    /**
     * The key-only deletes for the people and ledger rows the import created. Review, then run it with the profile
     * and region of the target.
     */
    public static String deleteScript(final ImportReport report) {
        final StringBuilder script = new StringBuilder("#!/bin/bash\n# Rollback deletes for the conference import "
                + "started " + report.getStartedAt() + ".\n# Run AFTER the Java rollback; review first.\n"
                + "set -uo pipefail\n: \"${AWS_PROFILE:?export AWS_PROFILE}\" \"${AWS_REGION:?export AWS_REGION}\"\n");
        for (final ImportReport.Key tx : report.getTransactions()) {
            script.append("aws dynamodb delete-item --table-name transactions --key '{\"userId\":{\"S\":\"")
                    .append(tx.hash()).append("\"},\"txId\":{\"S\":\"").append(tx.range()).append("\"}}'\n");
        }
        for (final String person : report.getCreatedPeople()) {
            script.append("aws dynamodb delete-item --table-name people --key '{\"id\":{\"S\":\"").append(person)
                    .append("\"}}'\n");
        }
        return script.toString();
    }

    private boolean save(final Person person) {
        try {
            return DAO.getInstance().savePerson(person);
        } catch (final IOException ex) {
            log.error("Rollback could not save {}", person.getId(), ex);
            return false;
        }
    }

    private boolean saveFamily(final Family family) {
        try {
            return DAO.getInstance().saveFamily(family);
        } catch (final IOException | RuntimeException ex) {
            log.error("Rollback could not restore family {}", family.getId(), ex);
            return false;
        }
    }

    private <T> T read(final JsonNode tree, final Class<T> type) {
        try {
            return mapper.treeToValue(tree, type);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("Unreadable before-image: " + tree, ex);
        }
    }

    private void done(final String what, final Boolean ok) {
        lines.add((Boolean.TRUE.equals(ok) ? "undone " : "FAILED ") + what);
    }
}
