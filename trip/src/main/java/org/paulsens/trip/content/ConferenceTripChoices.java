package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.action.ConferenceCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

/**
 * The shared "Event" property of the trip section types: blank means "the trip this page is about" (a trip's own
 * page); a pick names a public trip of either kind, so the section can also sit on an organization's home page. The
 * choices are the site's public trips, from the cached listing.
 */
final class ConferenceTripChoices {
    private ConferenceTripChoices() {
    }

    static Placeholder property() {
        return new Placeholder(ConferenceCommands.PROP_TRIP_ID, Placeholder.Type.CHOICE, "Event",
                "Blank on a trip's own page; pick one to show it elsewhere", false);
    }

    static List<ProgrammaticContentTemplate.Choice> choices() {
        return choices("This page's event");
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
