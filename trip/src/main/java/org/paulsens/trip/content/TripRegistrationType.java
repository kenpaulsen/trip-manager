package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.Placeholder;

/**
 * A conference's registration block, as a programmatic content type (docs/conference.md): whether registration is
 * open, the places left, the prices as the trip's priced questions configure them, a countdown to the first day and
 * the Register button. Computed at render time from the trip and its registrations, so it never drifts from what
 * the registration page offers.
 */
final class TripRegistrationType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "trip-registration";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Registration";
    }

    @Override
    public String getIcon() {
        return "pi-ticket";
    }

    @Override
    public String getDescription() {
        return "Registration status, places left, prices, a countdown and the Register button, from the event itself.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(EventTripChoices.property());
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/tripRegistration.xhtml";
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        return EventTripChoices.property().getName().equals(propertyName)
                ? EventTripChoices.choices() : List.of();
    }
}
