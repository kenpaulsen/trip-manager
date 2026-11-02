package org.paulsens.trip.migrate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;

/**
 * The target rows a plan touches besides its own (docs/migrations/conference-import.md): the organization, the
 * families of the people it merges into or links under, and which of those people are members already. Written by
 * {@code plan} next to {@code plan.json}; {@code rehearse} seeds an IN-MEMORY store with it plus the plan's
 * before-images, so the real applier and verifier run on the real plan without one production write.
 *
 * @param organization the target organization as read
 * @param families     every family a merged or anchoring person belongs to
 * @param members      existing people of the plan already in the organization
 * @param operator     the operator (the apply's site-admin caller), seeded so the rehearsal can resolve them
 */
public record ImportSnapshot(Organization organization, List<Family> families, List<String> members,
        Person operator) {

    /** Reads the snapshot for a plan from the target. */
    public static ImportSnapshot of(final ImportPlan plan, final String orgId, final Person operator) {
        final DAO dao = DAO.getInstance();
        final Organization.Id org = Organization.Id.from(orgId);
        final Map<String, Family> families = new LinkedHashMap<>();
        final List<String> members = new ArrayList<>();
        for (final ImportPlan.PersonStep step : plan.people()) {
            if (step.action() != ImportPlan.Action.MERGE) {
                continue;
            }
            final Person existing = step.before();
            if (existing.getFamilyId() != null) {
                dao.getFamily(existing.getFamilyId(), Cached.NO)
                        .ifPresent(family -> families.put(family.getId().getValue(), family));
            }
            if (dao.getOrgMember(org, existing.getId(), Cached.NO).isPresent()) {
                members.add(existing.getId().getValue());
            }
        }
        return new ImportSnapshot(dao.getOrganization(org, Cached.NO).orElse(null), List.copyOf(families.values()),
                members, operator);
    }

    /** Seeds the LOCAL in-memory store: refuses to run anywhere else. */
    public void seed(final ImportPlan plan) throws IOException {
        if (!LocalMode.isLocal()) {
            throw new IllegalStateException("A rehearsal seeds the store: it runs in local mode only.");
        }
        final DAO dao = DAO.getInstance();
        if (dao.getOrganization(organization.getId(), Cached.NO).isEmpty()) {
            organization.setVersion(0L);   // versioned rows seed as new
            dao.saveOrganization(organization);
        }
        for (final ImportPlan.PersonStep step : plan.people()) {
            if (step.action() == ImportPlan.Action.MERGE) {
                dao.savePerson(step.before());
            }
        }
        for (final Family family : families) {
            if (dao.getFamily(family.getId(), Cached.NO).isEmpty()) {
                family.setVersion(0L);
                dao.saveFamily(family);
            }
        }
        for (final String member : members) {
            dao.saveOrgMember(new OrgMember(organization.getId(), Person.Id.from(member), LocalDateTime.now()));
        }
        if (operator != null && dao.getPerson(operator.getId(), Cached.NO).isEmpty()) {
            dao.savePerson(operator);
        }
    }
}
