package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

/**
 * The shared "Event" property of the trip section types: blank means "the trip this page is about" (a trip's own
 * page); a pick names a public trip of either kind, so the section can also sit on an organization's home page. The
 * choices are the site's public trips, from the cached listing.
 */
final class EventTripChoices {
    private EventTripChoices() {
    }

    static Placeholder property() {
        return new Placeholder(TripPageCommands.PROP_TRIP_ID, Placeholder.Type.CHOICE, "Event",
                "Blank on a trip's own page; pick one to show it elsewhere", false);
    }

    static List<ProgrammaticContentTemplate.Choice> choices() {
        return choices("This page's event");
    }

    /**
     * The values a PREVIEW of a trip section shows real data with: the trip whose page the section would go on,
     * else the site's next public trip (the first of the dated listing the choices come from) that
     * {@code hasContent} says has something to show. Null when there is none, and the preview is the sample.
     */
    static Map<String, String> previewValues(final ProgrammaticContentTemplate.PreviewContext context,
            final Predicate<Trip> hasContent) {
        final TripPageCommands pages = new TripPageCommands();
        if (context.tripId() != null) {
            final Trip own = pages.tripFor(null, context.tripId());
            return own != null && hasContent.test(own) ? Map.of(TripPageCommands.PROP_TRIP_ID, own.getId()) : null;
        }
        return new TripCommands().getPublicTrips().stream()
                .filter(hasContent)
                .findFirst()
                .map(trip -> Map.of(TripPageCommands.PROP_TRIP_ID, trip.getId()))
                .orElse(null);
    }

    /** The same list with the blank choice's label chosen by the type (the albums type means something wider). */
    static List<ProgrammaticContentTemplate.Choice> choices(final String blankLabel) {
        final List<ProgrammaticContentTemplate.Choice> choices = new ArrayList<>();
        choices.add(new ProgrammaticContentTemplate.Choice("", blankLabel));
        for (final Trip trip : new TripCommands().getPublicTrips()) {
            choices.add(new ProgrammaticContentTemplate.Choice(trip.getId(), trip.getTitle()));
        }
        return choices;
    }
}
