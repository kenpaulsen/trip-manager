package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The person-modeled trip staff (2026-08-24): {@code facilitatorIds}/{@code directorIds} resolve to
 * comma-joined names through the hand-written {@code getFacilitators()}/{@code getDirector()}, and the
 * deprecated free-form strings only serve rows without id lists. The stored legacy STRING must survive
 * JSON round trips untouched -- Jackson reads the field, never the resolving getter.
 */
public class TripStaffNamesTest {

    @Test
    public void idListsResolveToNamesAndWinOverTheLegacyStrings() throws IOException {
        final Person ann = savedPerson("Ann", "Alpha");
        final Person bob = savedPerson("Bob", "Beta");
        final Trip trip = Trip.builder().title("staff").build();
        trip.setFacilitators("Legacy Organizers");
        trip.setDirector("Fr. Legacy");
        trip.setFacilitatorIds(List.of(ann.getId(), bob.getId()));
        trip.setDirectorIds(List.of(bob.getId()));

        Assert.assertEquals(trip.getFacilitators(), "Ann Alpha, Bob Beta");
        Assert.assertEquals(trip.getDirector(), "Bob Beta");
    }

    @Test
    public void withoutIdListsTheLegacyStringsStillAnswer() {
        final Trip trip = Trip.builder().title("legacy").build();
        trip.setFacilitators("The Old Crew");
        trip.setDirector("Fr. Somebody");
        Assert.assertEquals(trip.getFacilitators(), "The Old Crew", "null id list folds to legacy");
        Assert.assertEquals(trip.getDirector(), "Fr. Somebody");

        trip.setFacilitatorIds(List.of());
        Assert.assertEquals(trip.getFacilitators(), "The Old Crew", "EMPTY id list folds to legacy too");
        Assert.assertNull(Trip.builder().title("bare").build().getFacilitators(), "nothing set: null");
    }

    @Test
    public void unresolvableIdsAreSkippedNotRendered() throws IOException {
        final Person ann = savedPerson("Ann", "Alpha");
        final Trip trip = Trip.builder().title("ghosts").build();
        trip.setFacilitatorIds(List.of(ann.getId(), Person.Id.newInstance()));
        Assert.assertEquals(trip.getFacilitators(), "Ann Alpha");
    }

    @Test
    public void theLegacyStringRoundTripsUntouchedBesideTheIdLists() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person ann = savedPerson("Ann", "Alpha");
        final Trip trip = Trip.builder().id("staff-json").title("json").build();
        trip.setFacilitators("Legacy Organizers");
        trip.setFacilitatorIds(List.of(ann.getId()));

        final String json = mapper.writeValueAsString(trip);
        Assert.assertTrue(json.contains("\"facilitators\":\"Legacy Organizers\""),
                "Jackson must serialize the stored FIELD, not the resolving getter: " + json);
        Assert.assertTrue(json.contains("\"facilitatorIds\""), json);

        final Trip reread = mapper.readValue(json, Trip.class);
        Assert.assertEquals(reread.getFacilitatorIds(), trip.getFacilitatorIds());
        Assert.assertEquals(reread.getFacilitators(), "Ann Alpha", "the reread trip resolves too");
    }

    private static Person savedPerson(final String first, final String last) throws IOException {
        final Person person = Person.builder()
                .first(first).last(last)
                .email("staff." + RandomData.genAlpha(8) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
