package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.action.PrivacyView.State;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.model.PrivacySettings.Visibility;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The page-side visibility matrix: who gets SHOW, who gets the admin MASK, and who gets nothing. This class is
 * what every XHTML surface consults, so the matrix here is the page equivalent of PersonDtoRedactionTest.
 */
public class PrivacyViewTest {

    @Test
    public void aStrangerGetsTheSharedFieldsAndNoneOfTheWithheldOnes() {
        final PrivacyView pv = PrivacyView.of(person("viewer"), person("subject"), false);
        // Defaults: email/cell/city shared, street private; fixed fields and notes never reach a stranger.
        Assert.assertEquals(pv.getEmail(), State.SHOW);
        Assert.assertEquals(pv.getCell(), State.SHOW);
        Assert.assertEquals(pv.getCity(), State.SHOW);
        Assert.assertEquals(pv.getStreet(), State.HIDE);
        Assert.assertEquals(pv.getFixed(), State.HIDE);
        Assert.assertEquals(pv.getNotes(), State.HIDE);
    }

    @Test
    public void anAdminSeesWithheldFieldsMaskedNeverHidden() {
        final Person subject = person("subject");
        subject.setPrivacy(new PrivacySettings(
                Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE));
        final PrivacyView pv = PrivacyView.of(person("viewer"), subject, true);
        Assert.assertEquals(pv.getEmail(), State.MASK);
        Assert.assertEquals(pv.getCell(), State.MASK);
        Assert.assertEquals(pv.getCity(), State.MASK);
        Assert.assertEquals(pv.getStreet(), State.MASK);
        Assert.assertEquals(pv.getFixed(), State.MASK);
        Assert.assertEquals(pv.getNotes(), State.MASK);
        // ...but a field the subject shares is simply shown, no reveal-click required.
        subject.setPrivacy(new PrivacySettings());
        Assert.assertEquals(PrivacyView.of(person("viewer"), subject, true).getEmail(), State.SHOW);
    }

    @Test
    public void selfSeesEverythingRegardlessOfKnobsOrAdminView() {
        final Person me = person("me");
        me.setPrivacy(new PrivacySettings(
                Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE));
        final PrivacyView pv = PrivacyView.of(me, me, false);
        Assert.assertEquals(pv.getStreet(), State.SHOW);
        Assert.assertEquals(pv.getFixed(), State.SHOW);
        Assert.assertEquals(pv.getNotes(), State.SHOW);
    }

    @Test
    public void aFamilyManagerIsNeverLockedOutExceptFromStaffNotes() {
        final Person subject = person("kid");
        subject.setPrivacy(new PrivacySettings(
                Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE));
        final Person manager = person("parent");
        manager.setManagedUsers(List.of(subject.getId()));

        final PrivacyView pv = PrivacyView.of(manager, subject, false);
        Assert.assertEquals(pv.getEmail(), State.SHOW, "Privacy never hides a member from their manager");
        Assert.assertEquals(pv.getStreet(), State.SHOW);
        Assert.assertEquals(pv.getFixed(), State.SHOW);
        Assert.assertEquals(pv.getNotes(), State.HIDE, "Staff notes are not family reading");
        Assert.assertEquals(PrivacyView.of(manager, subject, true).getNotes(), State.MASK,
                "An admin who is also the manager keeps the admin's masked notes view");
    }

    @Test
    public void aPrivateCityForcesTheStreetStateDown() {
        final Person subject = person("subject");
        subject.setPrivacy(new PrivacySettings(
                Visibility.LOGGED_IN, Visibility.LOGGED_IN, Visibility.PRIVATE, Visibility.LOGGED_IN));
        Assert.assertEquals(PrivacyView.of(person("viewer"), subject, false).getStreet(), State.HIDE,
                "A shared street knob must not survive a private city");
    }

    @Test
    public void missingPartiesFailClosed() {
        final PrivacyView noSubject = PrivacyView.of(person("viewer"), null, true);
        Assert.assertEquals(noSubject.getEmail(), State.HIDE);
        Assert.assertEquals(noSubject.getFixed(), State.HIDE);
        // A null viewer is a stranger, not an error.
        Assert.assertEquals(PrivacyView.of(null, person("subject"), false).getEmail(), State.SHOW);
        Assert.assertEquals(PrivacyView.of(null, person("subject"), false).getStreet(), State.HIDE);
    }

    private static Person person(final String first) {
        final Person person = new Person();
        person.setFirst(first);
        return person;
    }
}
