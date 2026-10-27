package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The waiver model types: their normalizing constructors and their JSON round trips. */
public class WaiverModelTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    public void aDocumentKnowsItsVersions() throws Exception {
        final WaiverDocument empty = new WaiverDocument();
        assertNotNull(empty.getId());
        assertNull(empty.getCurrent());
        assertEquals(empty.getCurrentNumber(), 0);
        final WaiverVersion v1 = WaiverVersion.builder().number(0).title(" T ").bodyHtml(null).note(" ").build();
        assertEquals(v1.getNumber(), 1, "numbers start at 1");
        assertEquals(v1.getTitle(), "T");
        assertEquals(v1.getBodyHtml(), "");
        assertNull(v1.getNote());
        final WaiverDocument doc = WaiverDocument.builder().id(" ").title(" Waiver ").versions(List.of(v1,
                WaiverVersion.builder().number(2).note(" changed ").build())).build();
        assertEquals(doc.getTitle(), "Waiver");
        assertEquals(doc.getCurrentNumber(), 2);
        assertEquals(doc.version(2).getNote(), "changed");
        assertNull(doc.version(3));
        final WaiverDocument back = mapper.readValue(mapper.writeValueAsString(doc), WaiverDocument.class);
        assertEquals(back, doc);
    }

    @Test
    public void aPinAcceptsOlderSignaturesUnlessToldOtherwise() throws Exception {
        final TripWaiver pin = new TripWaiver();
        assertTrue(pin.isAcceptOlder());
        assertEquals(pin.getVersion(), 1);
        assertFalse(TripWaiver.builder().acceptOlder(false).build().isAcceptOlder());
        assertTrue(mapper.readValue("{\"waiverId\":\"w\",\"version\":3}", TripWaiver.class).isAcceptOlder(),
                "an absent flag is the owner's default");
        final Trip trip = Trip.builder().waivers(List.of(TripWaiver.builder().waiverId("w").version(2).build()))
                .build();
        assertEquals(trip.waiver("w").getVersion(), 2);
        assertNull(trip.waiver("x"));
        trip.setWaivers(null);
        assertTrue(trip.getWaivers().isEmpty());
        assertTrue(Trip.builder().waivers(null).build().getWaivers().isEmpty());
    }

    @Test
    public void anAcceptanceKnowsWhoSignedForWhom() throws Exception {
        final Person.Id kid = Person.Id.from("kid");
        final WaiverAcceptance self = WaiverAcceptance.builder().personId(kid).signerId(kid).waiverId("w")
                .version(12).userAgent("x".repeat(600)).acceptedAt(Instant.parse("2026-10-27T12:00:00Z")).build();
        assertFalse(self.isOnBehalf());
        assertEquals(self.getClient(), WaiverAcceptance.Client.WEB);
        assertEquals(self.getUserAgent().length(), 512);
        assertEquals(self.getSortKey(), "kid#w#00012");
        assertTrue(WaiverAcceptance.builder().personId(kid).signerId(Person.Id.from("mom")).build().isOnBehalf());
        assertFalse(WaiverAcceptance.builder().personId(kid).build().isOnBehalf());
        assertEquals(mapper.readValue(mapper.writeValueAsString(self), WaiverAcceptance.class), self);
    }

    @Test
    public void theDraftsCheckMapSurvivesAnOldSession() {
        final RegistrationDraft draft = new RegistrationDraft();
        draft.setWaivers(null);
        assertTrue(draft.getWaivers().isEmpty(), "a draft restored without the map gets one");
    }
}
