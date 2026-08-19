package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** {@code org_members} rows through the DAO facade against the in-memory fake. */
public class OrgMemberDAOTest {

    @Test
    public void saveGetDeleteRoundTrip() throws IOException {
        final Organization.Id org = Organization.Id.newInstance();
        final Person.Id person = Person.Id.newInstance();
        final OrgMember member = new OrgMember(org, person, LocalDateTime.of(2026, 8, 17, 8, 0));
        assertTrue(DAO.getInstance().saveOrgMember(member));

        assertEquals(DAO.getInstance().getOrgMember(org, person, Cached.NO).orElseThrow(), member);

        assertTrue(DAO.getInstance().deleteOrgMember(org, person));
        assertTrue(DAO.getInstance().getOrgMember(org, person, Cached.NO).isEmpty());
    }

    @Test
    public void listReturnsOnlyThisOrgsMembers() throws IOException {
        final Organization.Id mine = Organization.Id.newInstance();
        final Organization.Id other = Organization.Id.newInstance();
        assertTrue(DAO.getInstance().saveOrgMember(member(mine)));
        assertTrue(DAO.getInstance().saveOrgMember(member(mine)));
        assertTrue(DAO.getInstance().saveOrgMember(member(other)));

        final List<OrgMember> listed = DAO.getInstance().getOrgMembers(mine, Cached.NO);
        assertEquals(listed.size(), 2);
        assertTrue(listed.stream().allMatch(m -> m.getOrgId().equals(mine)),
                "The org partition is the tenancy boundary -- no cross-org rows");
    }

    @Test
    public void missingLookupsAnswerEmpty() {
        assertTrue(DAO.getInstance().getOrgMember(
                Organization.Id.newInstance(), Person.Id.newInstance(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getOrgMember(null, null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getOrgMembers(Organization.Id.newInstance(), Cached.NO).isEmpty());
    }

    private static OrgMember member(final Organization.Id orgId) {
        return new OrgMember(orgId, Person.Id.newInstance(), LocalDateTime.now());
    }
}
