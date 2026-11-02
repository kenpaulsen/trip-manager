package org.paulsens.trip.api;

import java.util.List;
import org.paulsens.trip.api.dto.TripRosterDto.RosterContactDto;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.model.PrivacySettings.Visibility;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The roster's contact block follows the web contacts page's rule: city carries state, street carries zip. */
public class RosterContactTest {

    @Test
    public void aFellowTravelerGetsWhatIsSharedAndNothingElse() {
        final RosterContactDto contact = RosterContact.of(new Person(), subject(), false);
        Assert.assertEquals(contact.email(), "s@example.com");
        Assert.assertEquals(contact.cell(), "555-0100");
        Assert.assertEquals(contact.city(), "Medford");
        Assert.assertEquals(contact.state(), "OR", "state follows city");
        Assert.assertNull(contact.street(), "street defaults private");
        Assert.assertNull(contact.zip(), "zip follows street");
        Assert.assertNull(contact.masked());
    }

    @Test
    public void anAdminGetsTheWithheldFieldsNamedAsMasked() {
        final Person subject = subject();
        subject.setPrivacy(new PrivacySettings(Visibility.PRIVATE, Visibility.LOGGED_IN, Visibility.LOGGED_IN,
                Visibility.PRIVATE));
        final RosterContactDto contact = RosterContact.of(new Person(), subject, true);
        Assert.assertEquals(contact.street(), "1 Main St");
        Assert.assertEquals(contact.zip(), "97501");
        Assert.assertEquals(contact.masked(), List.of(RosterContact.EMAIL, RosterContact.STREET, RosterContact.ZIP));
    }

    @Test
    public void aManagerSeesEverythingUnmasked() {
        final Person subject = subject();
        subject.setPrivacy(new PrivacySettings(Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE,
                Visibility.PRIVATE));
        final Person manager = new Person();
        manager.getManagedUsers().add(subject.getId());
        final RosterContactDto contact = RosterContact.of(manager, subject, false);
        Assert.assertEquals(contact.email(), "s@example.com");
        Assert.assertNull(contact.masked());
    }

    @Test
    public void nothingSharedMeansNoBlockAtAll() {
        final Person subject = subject();
        subject.setPrivacy(new PrivacySettings(Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE,
                Visibility.PRIVATE));
        Assert.assertNull(RosterContact.of(new Person(), subject, false));
        final Person noAddress = new Person();
        noAddress.setAddress(null);
        Assert.assertNull(RosterContact.of(new Person(), noAddress, false), "no values is no block either");
    }

    private static Person subject() {
        final Person subject = new Person();
        subject.setEmail("s@example.com");
        subject.setCell("555-0100");
        final Address address = new Address();
        address.setStreet("1 Main St");
        address.setCity("Medford");
        address.setState("OR");
        address.setZip("97501");
        subject.setAddress(address);
        return subject;
    }
}
