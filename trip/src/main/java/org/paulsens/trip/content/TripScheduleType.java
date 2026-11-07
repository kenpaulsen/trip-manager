package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.Placeholder;

/**
 * A conference's public schedule, as a programmatic content type (docs/conference.md): the trip's EVENT-type events
 * that are not hidden from the agenda, grouped by day, with presenter, room and track. Read from the trip's events
 * at render time, so editing the itinerary updates the page; participants and private notes never appear.
 */
final class TripScheduleType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "trip-schedule";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Event schedule";
    }

    @Override
    public String getIcon() {
        return "pi-calendar";
    }

    @Override
    public String getDescription() {
        return "The event's public agenda by day, straight from its itinerary events (presenter, room, track).";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(ConferenceTripChoices.property());
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/tripSchedule.xhtml";
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        return ConferenceTripChoices.property().getName().equals(propertyName)
                ? ConferenceTripChoices.choices() : List.of();
    }
}
