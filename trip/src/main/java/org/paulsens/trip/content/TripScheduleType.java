package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

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
        return List.of(EventTripChoices.property());
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/tripSchedule.xhtml";
    }

    @Override
    public String getSampleHtml() {
        return """
                <div class="agenda">
                    <div class="agenda-day"><h3 class="agenda-date">Friday, March 5</h3>
                        <div class="agenda-item"><div class="agenda-time">9:00 AM</div>
                            <div class="agenda-body"><div class="agenda-title">Welcome and opening talk</div>
                                <div class="agenda-meta">Maria Sample &#183; Main hall</div></div></div>
                        <div class="agenda-item"><div class="agenda-time">11:00 AM</div>
                            <div class="agenda-body"><div class="agenda-title">Workshops</div>
                                <div class="agenda-meta">Rooms A to D</div></div></div>
                    </div>
                </div>
                """;
    }

    /** A trip with at least one public session; an empty agenda says nothing an author can judge the look by. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        return EventTripChoices.previewValues(context, TripScheduleType::hasAgenda);
    }

    private static boolean hasAgenda(final Trip trip) {
        return !new TripPageCommands().publicAgenda(trip).isEmpty();
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        return EventTripChoices.property().getName().equals(propertyName)
                ? EventTripChoices.choices() : List.of();
    }
}
