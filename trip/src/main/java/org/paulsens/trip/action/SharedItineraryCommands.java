package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import lombok.Value;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * The website's side of shared itineraries ({@code #{sharedItinerary}}): which itinerary page a viewer may open
 * for a person, and the rows of the read-only one (docs/shared-itineraries.md). Every answer comes from
 * {@link ItineraryAccess}, the rule the REST roster and endpoint use too.
 */
@Named("sharedItinerary")
@ApplicationScoped
public class SharedItineraryCommands {

    private final Supplier<Caller> callerSource;
    private final Supplier<PersonCommands> peopleSource;
    private final Supplier<OrgCommands> orgSource;

    public SharedItineraryCommands() {
        this(Caller::current, PersonCommands::getPersonCommands,
                () -> org.paulsens.trip.api.Beans.get(OrgCommands.class));
    }

    /** Test seam: {@code Caller.current()} and the bean lookups need a FacesContext and a CDI container. */
    SharedItineraryCommands(final Supplier<Caller> callerSource, final Supplier<PersonCommands> peopleSource,
            final Supplier<OrgCommands> orgSource) {
        this.callerSource = callerSource;
        this.peopleSource = peopleSource;
        this.orgSource = orgSource;
    }

    /** {@code FULL}, {@code SHARED} or {@code NONE}, as a string a page can compare. */
    public String access(final Trip trip, final Person.Id subjectId) {
        return level(trip, subjectId).name();
    }

    /**
     * The itinerary page this viewer may open for the person -- their full page or the shared one -- or null
     * when neither, so a link to it is simply not rendered.
     */
    public String itineraryUrl(final Trip trip, final Person.Id subjectId) {
        return switch (level(trip, subjectId)) {
            case FULL -> "/trip/itinerary.jsf?trip=" + trip.getId() + "&id=" + subjectId.getValue();
            case SHARED -> "/trip/sharedItinerary.jsf?trip=" + trip.getId() + "&id=" + subjectId.getValue();
            case NONE -> null;
        };
    }

    /**
     * The read-only page's rows: the events the person is on, in date order, as the TRIP describes them. The
     * row type has no field for a private note, a reservation or a room, so nothing person-specific can reach
     * the page by a forgotten {@code rendered=}. Empty unless the viewer may see the itinerary at all.
     */
    public List<SharedItineraryRow> rows(final Trip trip, final Person.Id subjectId) {
        if (level(trip, subjectId) == ItineraryAccess.Level.NONE) {
            return List.of();
        }
        return trip.getTripEventsForUser(subjectId).stream()
                .map(SharedItineraryRow::of)
                .sorted(Comparator.comparing(SharedItineraryRow::getStart,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private ItineraryAccess.Level level(final Trip trip, final Person.Id subjectId) {
        if (trip == null || subjectId == null) {
            return ItineraryAccess.Level.NONE;
        }
        final Caller caller = callerSource.get();
        final PersonCommands people = peopleSource.get();
        final Person subject = people.getPerson(subjectId);
        if (!subjectId.equals(subject.getId())) {
            // getPerson answers a miss with a blank person under a FRESH id; that is nobody's itinerary.
            return ItineraryAccess.Level.NONE;
        }
        final Person viewer = caller.personId() == null ? null : people.getPerson(caller.personId());
        return ItineraryAccess.of(caller, viewer, trip, subject,
                () -> orgSource.get().canManageOrg(trip.getOrgId()));
    }

    /** One event of a shared itinerary, holding only what the trip says about it. */
    @Value
    public static class SharedItineraryRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        String id;
        String type;
        String title;
        String notes;
        LocalDateTime start;
        LocalDateTime end;

        static SharedItineraryRow of(final TripEvent event) {
            return new SharedItineraryRow(event.getId(), event.getType() == null ? "EVENT" : event.getType().name(),
                    event.getTitle(), event.getNotes(), event.getStart(), event.getEnd());
        }
    }
}
