package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.i18n.Text;
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
        return Text.get("common.registration");
    }

    @Override
    public String getIcon() {
        return "pi-ticket";
    }

    @Override
    public String getDescription() {
        return Text.get("server.types.registration.description");
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
    public String getSampleHtml() {
        return """
                <div class="regStatus">
                    <div class="regStatus-state"><span class="regStatus-open">Registration is open</span>
                        <span class="regStatus-seats"> &#183; 42 of 120 places left</span></div>
                    <div class="regStatus-countdown"><span class="tripCountdown">86</span> days to go</div>
                    <div class="regStatus-prices"><div class="regStatus-question">Registration type</div>
                        <ul><li>Adult: $120.00</li><li>Student: $60.00</li></ul></div>
                    <div class="regStatus-cta"><a class="ui-button regStatus-button">Register</a></div>
                </div>
                """;
    }

    /** Any trip the site lists has a registration block: its state, its places, its prices. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        return EventTripChoices.previewValues(context, trip -> true);
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        return EventTripChoices.property().getName().equals(propertyName)
                ? EventTripChoices.choices() : List.of();
    }
}
