package org.paulsens.trip.action;

import java.io.Serializable;
import lombok.Value;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;

/**
 * How one viewer may render one subject's profile fields, resolved to a per-field state the pages can test
 * directly ({@code pv.email eq 'MASK'}). This is the single place the page-side rules live, mirroring what
 * {@code PersonDto.redactedFor} does for the API:
 *
 * <ul>
 *   <li>{@code SHOW} -- render the value plainly (self, family manager, or a field the subject shares);</li>
 *   <li>{@code MASK} -- admin viewing a withheld field: render masked with click-to-reveal. The mask is a UI
 *       affordance only (user-locked decision): the real value may sit in the page for the reveal;</li>
 *   <li>{@code HIDE} -- render nothing at all.</li>
 * </ul>
 *
 * <p>{@code fixed} covers the always-private fields (birthdate, passport, TSA, emergency contacts); {@code notes}
 * is separate because a family manager gets everything EXCEPT notes -- same rule as
 * {@code AccessLevel.seesNotes()}, staff notes are not family reading.
 */
@Value
public class PrivacyView implements Serializable {

    public enum State {
        SHOW, MASK, HIDE
    }

    State email;
    State cell;
    State city;
    State street;
    State fixed;
    State notes;

    /**
     * @param viewer    the signed-in (possibly acting-for) person doing the looking; null renders as a stranger
     * @param subject   whose fields are being rendered
     * @param adminView whether the PAGE grants this viewer an admin view (site admin, tripMgr, tripFinView --
     *                  the page knows which privilege gates it; this class only needs the verdict)
     */
    public static PrivacyView of(final Person viewer, final Person subject, final boolean adminView) {
        if (subject == null) {
            return new PrivacyView(State.HIDE, State.HIDE, State.HIDE, State.HIDE, State.HIDE, State.HIDE);
        }
        final boolean self = viewer != null && subject.getId().equals(viewer.getId());
        if (self) {
            return new PrivacyView(State.SHOW, State.SHOW, State.SHOW, State.SHOW, State.SHOW, State.SHOW);
        }
        final boolean manages = viewer != null && viewer.getManagedUsers().contains(subject.getId());
        if (manages) {
            // Privacy never hides a family member from their manager -- except staff notes, which follow
            // AccessLevel.seesNotes(): an admin who is ALSO the manager still gets the masked admin view.
            return new PrivacyView(State.SHOW, State.SHOW, State.SHOW, State.SHOW, State.SHOW,
                    adminView ? State.MASK : State.HIDE);
        }
        final State withheld = adminView ? State.MASK : State.HIDE;
        final PrivacySettings privacy = subject.getPrivacy();
        return new PrivacyView(
                privacy.isEmailVisible() ? State.SHOW : withheld,
                privacy.isCellVisible() ? State.SHOW : withheld,
                privacy.isCityVisible() ? State.SHOW : withheld,
                privacy.isStreetVisible() ? State.SHOW : withheld,
                withheld,
                withheld);
    }
}
