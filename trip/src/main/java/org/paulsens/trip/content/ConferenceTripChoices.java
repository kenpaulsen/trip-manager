package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.action.ConferenceCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

/**
 * The shared "Event" property of the conference section types: blank means "the event this page is about" (a
 * conference's own page); a pick names a public conference, so the section can also sit on an organization's home
 * page. The choices are the site's public conferences, from the cached listing.
 */
final class ConferenceTripChoices {
    private ConferenceTripChoices() {
    }

    static Placeholder property() {
        return new Placeholder(ConferenceCommands.PROP_TRIP_ID, Placeholder.Type.CHOICE, "Event",
                "Blank on a conference's own page; pick one to show it elsewhere", false);
    }

    static List<ProgrammaticContentTemplate.Choice> choices() {
        final List<ProgrammaticContentTemplate.Choice> choices = new ArrayList<>();
        choices.add(new ProgrammaticContentTemplate.Choice("", "This page's event"));
        for (final Trip trip : new TripCommands().getPublicTrips()) {
            if (trip.isConference()) {
                choices.add(new ProgrammaticContentTemplate.Choice(trip.getId(), trip.getTitle()));
            }
        }
        return choices;
    }
}
