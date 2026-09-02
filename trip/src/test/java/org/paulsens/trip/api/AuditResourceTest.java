package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditViewCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link AuditResource}, the read-only window onto the trail.
 *
 * <p>Read-only is the design, not an accident of scope: there is intentionally no method here that could write a
 * record, so the tests confirm the read paths and the honesty flags -- {@code complete} and {@code degraded} --
 * that keep a partial answer from presenting as the whole history.
 */
public class AuditResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("audit-admin");

    private AuditViewCommands view;
    private AuditResource resource;

    @BeforeMethod
    public void bindBeans() {
        view = bindMock(AuditViewCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new AuditResource());
    }

    private static AuditPage emptyPage() {
        return new AuditPage(List.of(), LocalDate.of(2026, 8, 1), true);
    }

    @Test
    public void everyEndpointNeedsAuditAdmin() {
        signedInAs(ADMIN);

        assertError(resource.page(null, null, null, null, null, null, 50), 403, ApiErrors.FORBIDDEN);
        assertError(resource.recent(null, 50), 403, ApiErrors.FORBIDDEN);
        assertError(resource.export(null, null, null, null, null, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.vocabularies(), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(view);
    }

    /**
     * {@code org=} scopes a read to one organization's trail, and that organization's OWN auditAdmin
     * holder -- no global grant -- may read it and nothing wider. The gate is the page's
     * ({@code AuditViewCommands.canView}), so the two surfaces cannot drift apart.
     */
    @Test
    public void anOrgsOwnAuditHolderReadsThatOrgsTrailOnly() {
        final String acme = org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID;
        final org.paulsens.trip.action.PrivilegeCommands privs = new org.paulsens.trip.action.PrivilegeCommands();
        privs.savePrivilege(privs.createPrivilege(org.paulsens.trip.action.PrivilegeCommands.AUDIT_ADMIN,
                "acme audit", acme, List.of(ADMIN)));
        signedInAs(ADMIN);
        Mockito.when(view.getPage(ArgumentMatchers.eq(acme), ArgumentMatchers.isNull(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
                .thenReturn(emptyPage());
        Mockito.when(view.getRecent(ArgumentMatchers.eq(acme), ArgumentMatchers.anyInt())).thenReturn(emptyPage());
        Mockito.when(view.toCsv(ArgumentMatchers.eq(acme), ArgumentMatchers.isNull(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn("timestamp\n");

        assertOk(resource.page(acme, null, null, null, null, null, 50));
        assertOk(resource.recent(" " + acme + " ", 50));
        Assert.assertEquals(resource.export(acme, null, null, null, null, null).getStatus(), 200);

        assertError(resource.page(null, null, null, null, null, null, 50), 403, ApiErrors.FORBIDDEN);
        assertError(resource.page("some-other-org", null, null, null, null, null, 50), 403, ApiErrors.FORBIDDEN);
        assertError(resource.vocabularies(), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void aMalformedCursorIs400NotA500() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.page(null, "yesterday-ish", null, null, null, null, 50), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.export(null, "not-a-time", null, null, null, null), 400, ApiErrors.BAD_REQUEST);
        Mockito.verifyNoInteractions(view);
    }

    @Test
    public void aBlankCursorMeansTheNewestPage() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(view.getPage(ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
                .thenReturn(emptyPage());

        assertOk(resource.page(null, "  ", null, null, null, null, 50));
        assertOk(resource.page(null, null, null, null, null, null, 50));
    }

    @Test
    public void aValidCursorIsParsedAndPassedThrough() {
        signedInAsSiteAdmin(ADMIN);
        final Instant cursor = Instant.parse("2026-08-01T12:00:00Z");
        Mockito.when(view.getPage(ArgumentMatchers.isNull(), ArgumentMatchers.eq(cursor), ArgumentMatchers.eq("ken"),
                ArgumentMatchers.eq("LOGIN"), ArgumentMatchers.eq("SUCCESS"), ArgumentMatchers.eq("note"),
                ArgumentMatchers.eq(50))).thenReturn(emptyPage());

        assertOk(resource.page(null, " 2026-08-01T12:00:00Z ", "ken", "LOGIN", "SUCCESS", "note", 50));
    }

    @Test
    public void theLimitIsClampedAtBothEnds() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(view.getPage(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
                .thenReturn(emptyPage());
        Mockito.when(view.getRecent(ArgumentMatchers.any(), ArgumentMatchers.anyInt())).thenReturn(emptyPage());

        resource.page(null, null, null, null, null, null, 100_000);
        Mockito.verify(view).getPage(null, null, null, null, null, null, 500);

        resource.recent(null, -3);
        Mockito.verify(view).getRecent(null, 1);
    }

    /** The honesty flags: a partial answer must say so, or an outage reads as an empty history. */
    @Test
    public void theHonestyFlagsSurviveTheTripToTheWire() {
        signedInAsSiteAdmin(ADMIN);
        final AuditPage partial = new AuditPage(List.of(), LocalDate.of(2026, 7, 1), false, 3);
        Mockito.when(view.getRecent(ArgumentMatchers.any(), ArgumentMatchers.anyInt())).thenReturn(partial);

        final Response response = resource.recent(null, 50);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("complete"), false);
        Assert.assertEquals(body.get("degraded"), true);
        Assert.assertEquals(body.get("searchedBackTo"), LocalDate.of(2026, 7, 1));
    }

    @Test
    public void exportIsCsvWithAFilenameNotJson() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(view.toCsv(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn("time,actor\n");

        final Response response = resource.export(null, null, null, null, null, null);

        Assert.assertEquals(response.getStatus(), 200);
        Assert.assertEquals(response.getMediaType().toString(), "text/csv");
        Assert.assertTrue(response.getHeaderString("Content-Disposition").contains("audit.csv"));
        Assert.assertEquals(response.getEntity(), "time,actor\n");
    }

    /** Dropdowns come from the server's enums; a client that guessed would drift on the next enum addition. */
    @Test
    public void vocabulariesListTheServersEnums() {
        signedInAsSiteAdmin(ADMIN);

        final Response response = resource.vocabularies();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        final List<?> actions = (List<?>) body.get("actions");
        final List<?> outcomes = (List<?>) body.get("outcomes");
        Assert.assertFalse(actions.isEmpty());
        Assert.assertTrue(outcomes.contains("SUCCESS"));
    }

    @Test
    public void theProducedTypeIsTheAuditMediaType() {
        Assert.assertEquals(new AuditResource().versionedType(), ApiMediaTypes.AUDIT_V1);
    }
}
