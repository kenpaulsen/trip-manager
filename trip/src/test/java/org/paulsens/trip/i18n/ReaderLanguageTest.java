package org.paulsens.trip.i18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.push.PushNotifications;
import org.paulsens.trip.push.PushPayload;
import org.paulsens.trip.push.PushSender;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link ReaderLanguage}: what something SENT to a person is written in, and that a push really uses it. Beta
 * Corp's seeded site offers English, Spanish and Croatian; every other organization offers English alone.
 */
public class ReaderLanguageTest {

    private static final Locale ES = Language.Spanish.getLocale();
    private static final Locale HR = Language.Croatian.getLocale();

    @BeforeClass
    public void seed() {
        // The DAO first: it is what seeds the fixtures the first time, and addFakeData reads them.
        DAO.getInstance();
        FakeData.addFakeData();
    }

    @Test
    public void theReadersOwnChoiceLeadsThenTheTripsLanguageThenTheOrganizationsDefault() {
        Assert.assertEquals(ReaderLanguage.of(reader("hr"), betaTrip(Language.Spanish)), HR);
        Assert.assertEquals(ReaderLanguage.of(reader(null), betaTrip(Language.Spanish)), ES,
                "no choice of their own: the language the trip is held in");
        Assert.assertEquals(ReaderLanguage.of(reader(null), betaTrip(null)), Locale.ENGLISH,
                "nothing says otherwise: the organization's default");
        Assert.assertEquals(ReaderLanguage.of((Person) null, betaTrip(Language.Croatian)), HR);
        Assert.assertEquals(ReaderLanguage.of(reader("klingon"), betaTrip(null)), Locale.ENGLISH);
    }

    @Test
    public void aLanguageTheSendingOrganizationHasNotSwitchedOnIsNeverUsed() {
        final Trip elsewhere = Trip.builder().id("rl-" + System.nanoTime()).title("Elsewhere")
                .language(Language.Spanish).people(new ArrayList<>()).build();
        Assert.assertEquals(ReaderLanguage.of(reader("es"), elsewhere), Locale.ENGLISH,
                "the shared site offers English alone, so an unreviewed translation cannot go out in mail");
        Assert.assertEquals(ReaderLanguage.of(reader("es"), null), Locale.ENGLISH, "a site-level message");
        Assert.assertEquals(ReaderLanguage.of(Person.Id.from("nobody-at-all"), elsewhere), Locale.ENGLISH);
        Assert.assertEquals(ReaderLanguage.of((Person.Id) null, betaTrip(Language.Spanish)), ES);
    }

    /**
     * Staff read their own saved language when the sending site offers it, else English: never the trip's,
     * which they did not choose.
     */
    @Test
    public void staffReadTheirOwnLanguageElseEnglish() throws IOException {
        final Organization beta = betaTrip(null).getOrganization();
        Assert.assertEquals(ReaderLanguage.ofStaff(reader("hr"), beta), HR);
        Assert.assertEquals(ReaderLanguage.ofStaff(reader(null), beta), Locale.ENGLISH);
        Assert.assertEquals(ReaderLanguage.ofStaff(null, beta), Locale.ENGLISH);
        Assert.assertEquals(ReaderLanguage.ofStaff(reader("es"), null), Locale.ENGLISH,
                "the shared site offers English only");

        final Person office = Person.builder().first("Office").last("Staff")
                .email("rl-office-" + System.nanoTime() + "@example.org").build();
        office.setLanguage("es");
        Assert.assertTrue(DAO.getInstance().savePerson(office));
        Assert.assertEquals(ReaderLanguage.ofStaffAddress(office.getEmail().toUpperCase(Locale.ROOT),
                betaTrip(Language.Croatian)), ES, "the address's own account, not the trip's language");
        Assert.assertEquals(ReaderLanguage.ofStaffAddress("nobody-" + System.nanoTime() + "@example.org",
                betaTrip(Language.Croatian)), Locale.ENGLISH, "a mailbox no account holds");
        Assert.assertEquals(ReaderLanguage.ofStaffAddress(office.getEmail() + ",x@example.org",
                betaTrip(null)), Locale.ENGLISH, "a list of addresses");
        Assert.assertEquals(ReaderLanguage.ofStaffAddress(" ", (Trip) null), Locale.ENGLISH);
    }

    @Test
    public void aPushIsWrittenInItsRecipientsLanguageNotTheSenders() {
        final PushSender sender = Mockito.mock(PushSender.class);
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.CHAT_MAIL_BASE_URL)).thenReturn("https://site.example");
        Mockito.when(config.getString(KnownSettings.REG_MAIL_BASE_URL)).thenReturn("https://reg.example");
        final PushNotifications push = new PushNotifications(sender, config, Runnable::run);
        final Person traveler = Person.builder().first("Trin").last("Traveler").email("t@example.org").build();
        traveler.setId(Person.Id.from("rl-traveler"));
        final Person.Id manager = Person.Id.from("rl-manager-unknown");

        // The trip is held in Spanish; neither recipient has a stored choice, so both read Spanish.
        push.registrationApproved(traveler, betaTrip(Language.Spanish), List.of(traveler.getId(), manager));

        final ArgumentCaptor<PushPayload> own = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(traveler.getId()), own.capture(), ArgumentMatchers.any());
        Assert.assertEquals(own.getValue().getSubtitle(), "Inscripción aprobada");
        Assert.assertEquals(own.getValue().getBody(), "Tu lugar está confirmado: Roma 2027");
        final ArgumentCaptor<PushPayload> other = ArgumentCaptor.forClass(PushPayload.class);
        Mockito.verify(sender).sendAlert(ArgumentMatchers.eq(manager), other.capture(), ArgumentMatchers.any());
        Assert.assertEquals(other.getValue().getBody(), "Trin tiene su lugar confirmado: Roma 2027");
    }

    private static Person reader(final String language) {
        final Person person = new Person();
        person.setLanguage(language);
        return person;
    }

    /** An unsaved trip of Beta Corp's: only its organization and language matter here. */
    private static Trip betaTrip(final Language language) {
        final Trip trip = Trip.builder().id("rl-" + System.nanoTime()).title("Roma 2027").language(language)
                .people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.BETA_ORG_ID);
        return trip;
    }
}
