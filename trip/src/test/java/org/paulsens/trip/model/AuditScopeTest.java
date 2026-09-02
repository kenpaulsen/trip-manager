package org.paulsens.trip.model;

import java.time.Instant;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The tenancy half of an audit read: which organizations' records a scope admits. Every branch here decides
 * what a viewer SEES, and the wrong answer is silent -- a record missing from a trail or, worse, another
 * tenant's record present in it.
 */
public class AuditScopeTest {

    private static AuditEvent owned(final String orgId) {
        return new AuditEvent(Instant.parse("2026-09-02T12:00:00Z"), AuditAction.LOGIN, AuditOutcome.SUCCESS,
                "a@example.com", "id-1", null, null, null, "msg", null, orgId);
    }

    @Test
    public void anOrgScopeAdmitsThatOrgsRecordsAndNothingElse() {
        final AuditScope acme = AuditScope.org("acme");
        Assert.assertTrue(acme.isOrg());
        Assert.assertTrue(acme.admits("acme"));
        Assert.assertFalse(acme.admits("beta"), "another tenant's record");
        Assert.assertFalse(acme.admits(null), "a site-level record is not the org's");
    }

    @Test
    public void aSharedScopeHidesTheHostedOrgsAndKeepsTheRest() {
        final AuditScope shared = AuditScope.shared(Set.of("acme", "beta"));
        Assert.assertFalse(shared.isOrg());
        Assert.assertTrue(shared.admits(null), "site-level records");
        Assert.assertTrue(shared.admits("cfpw"), "a sharing tenant's records");
        Assert.assertFalse(shared.admits("acme"), "a hosted org's records stay off the shared trail");
        Assert.assertFalse(shared.admits("beta"));
    }

    @Test
    public void anUnknownHostedListNarrowsToSiteLevelRecords() {
        // The failure mode that must never widen: if the org list cannot be read, the shared host shows
        // records owned by nobody rather than everybody's.
        final AuditScope narrowed = AuditScope.shared(null);
        Assert.assertTrue(narrowed.siteLevelOnly());
        Assert.assertTrue(narrowed.admits(null));
        Assert.assertFalse(narrowed.admits("cfpw"));
        Assert.assertFalse(narrowed.admits("acme"));
    }

    @Test
    public void allAdmitsEverything() {
        Assert.assertTrue(AuditScope.all().admits(null));
        Assert.assertTrue(AuditScope.all().admits("anyone"));
        Assert.assertFalse(AuditScope.all().isOrg());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void anOrgScopeNeedsAnOrg() {
        AuditScope.org(" ");
    }

    @Test
    public void aQueryAppliesItsScopeBeforeAnyOtherFilter() {
        final AuditQuery acmeOnly = AuditQuery.builder().scope(AuditScope.org("acme")).build();
        Assert.assertTrue(acmeOnly.matches(owned("acme")));
        Assert.assertFalse(acmeOnly.matches(owned("beta")));
        Assert.assertFalse(acmeOnly.matches(owned(null)));
        Assert.assertFalse(acmeOnly.isFiltered(), "the scope is the viewer's boundary, not a filter they set");

        final AuditQuery everything = AuditQuery.builder().build();
        Assert.assertTrue(everything.matches(owned("acme")), "the default scope is unbounded");
        Assert.assertTrue(everything.matches(owned(null)));
    }
}
