package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.paulsens.trip.dynamo.FakeData.FAKE2_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.FAKE_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_EN_1_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_EN_2_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_ES_1_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_EXT_1_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_HIDDEN_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_PAST_30D_TRIP_ID;
import static org.paulsens.trip.dynamo.FakeData.PUB_PAST_3D_TRIP_ID;

/**
 * The landing-page listing rules, against the FakeData seeds. Assertions are by membership, not exact
 * counts: the suite shares one local store and other tests seed trips of their own.
 */
public class TripPublicListingTest {

    private final TripCommands trip = new TripCommands();

    @BeforeClass
    public void warmUp() {
        DAO.getInstance();
        // Other tests in the shared suite clear caches (which, locally, IS the store) -- re-seed.
        FakeData.addFakeData();
    }

    private static List<String> ids(final List<Trip> trips) {
        return trips.stream().map(Trip::getId).toList();
    }

    @Test
    public void publicTripsFollowTheListingRules() {
        final List<Trip> listed = trip.getPublicTrips();
        final List<String> ids = ids(listed);
        Assert.assertTrue(ids.containsAll(List.of(PUB_EN_1_TRIP_ID, PUB_EN_2_TRIP_ID, PUB_ES_1_TRIP_ID,
                PUB_EXT_1_TRIP_ID, PUB_PAST_3D_TRIP_ID, FAKE2_TRIP_ID)),
                "all public in-window seeds should list: " + ids);
        Assert.assertFalse(ids.contains(FAKE_TRIP_ID), "not open to the public");
        Assert.assertFalse(ids.contains(PUB_HIDDEN_TRIP_ID), "not open to the public");
        Assert.assertFalse(ids.contains(PUB_PAST_30D_TRIP_ID), "ended past the 7-day window");
        for (final Trip listedTrip : listed) {
            Assert.assertTrue(Boolean.TRUE.equals(listedTrip.getOpenToPublic()));
        }
        // Sorted by start date.
        Assert.assertTrue(ids.indexOf(PUB_PAST_3D_TRIP_ID) < ids.indexOf(PUB_EN_1_TRIP_ID));
        Assert.assertTrue(ids.indexOf(PUB_EN_1_TRIP_ID) < ids.indexOf(PUB_EN_2_TRIP_ID));
    }

    @Test
    public void languageGroupingFoldsNullIntoEnglish() {
        final List<String> english = ids(trip.getPublicTrips("English"));
        Assert.assertTrue(
                english.containsAll(List.of(PUB_EN_1_TRIP_ID, PUB_EN_2_TRIP_ID, PUB_EXT_1_TRIP_ID, FAKE2_TRIP_ID)),
                "the Summer Demo seed has no language and must fold into English: " + english);
        Assert.assertFalse(english.contains(PUB_ES_1_TRIP_ID));

        final List<String> spanish = ids(trip.getPublicTrips("Spanish"));
        Assert.assertTrue(spanish.contains(PUB_ES_1_TRIP_ID));
        Assert.assertFalse(spanish.contains(PUB_EN_1_TRIP_ID));

        // Garbage input folds to English rather than erroring a public page.
        Assert.assertEquals(ids(trip.getPublicTrips("Klingon")), english);
        Assert.assertEquals(ids(trip.getPublicTrips(null)), english);
    }

    @Test
    public void languagesPresentDriveTheSections() {
        final List<Language> languages = trip.getPublicTripLanguages();
        Assert.assertTrue(languages.containsAll(List.of(Language.English, Language.Spanish)));
        Assert.assertEquals(languages.get(0), Language.English, "declaration order is display order");
    }

    @Test
    public void sidebarListsOnlyCfpwTrips() {
        final List<String> english = ids(trip.getPublicCfpwTrips("English"));
        Assert.assertTrue(english.containsAll(List.of(PUB_EN_1_TRIP_ID, PUB_EN_2_TRIP_ID, PUB_PAST_3D_TRIP_ID)),
                "" + english);
        Assert.assertFalse(english.contains(PUB_EXT_1_TRIP_ID), "external provider");
        // Fake2 has the CFPW orgId and NO provider string -- the shape of every legacy production row.
        // Recognition keys on the org, so it lists; only org-less rows fall back to the provider compare.
        Assert.assertTrue(english.contains(FAKE2_TRIP_ID), "org-owned trip lists regardless of provider");
    }

    @Test
    public void countdownsAreNextPerLanguagePlusSoon() {
        final List<String> countdown = ids(trip.getCountdownTrips(60));
        Assert.assertTrue(countdown.contains(PUB_EN_1_TRIP_ID), "next English AND within 60 days: " + countdown);
        Assert.assertTrue(countdown.contains(PUB_ES_1_TRIP_ID),
                "next Spanish even though it starts beyond 60 days: " + countdown);
        Assert.assertFalse(countdown.contains(PUB_EN_2_TRIP_ID),
                "neither next-of-language nor within 60 days: " + countdown);
        Assert.assertFalse(countdown.contains(PUB_PAST_3D_TRIP_ID), "already started");
    }

    @Test
    public void languagesForTheEditorMenu() {
        Assert.assertEquals(trip.getLanguages(), List.of(Language.values()));
    }

    @Test
    public void registrationChipHelpers() {
        final RegistrationCommands reg = new RegistrationCommands();
        // Anonymous and unregistered viewers see the call to action; nulls never throw on a public page.
        Assert.assertEquals(reg.getChipLabel(PUB_EN_1_TRIP_ID, null), "Register");
        Assert.assertEquals(reg.getChipLabel(null, Person.Id.from("u1")), "Register");
        Assert.assertFalse(reg.isChipRegistered(PUB_EN_1_TRIP_ID, null));
        final Person.Id visitor = Person.Id.from("chip-test");
        Assert.assertEquals(reg.getChipLabel(PUB_EN_1_TRIP_ID, visitor), "Register");
        Assert.assertFalse(reg.isChipRegistered(PUB_EN_1_TRIP_ID, visitor));

        // A confirmed registration shows its own status and flips the styling flag.
        final Registration confirmed =
                reg.createRegistration(PUB_EN_1_TRIP_ID, visitor).withStatus(Registration.Status.CONFIRMED);
        Assert.assertTrue(reg.saveRegistration(confirmed));
        Assert.assertEquals(reg.getChipLabel(PUB_EN_1_TRIP_ID, visitor), "Confirmed");
        Assert.assertTrue(reg.isChipRegistered(PUB_EN_1_TRIP_ID, visitor));
    }

    @Test
    public void languageSectionHeadingsAreDeclared() {
        Assert.assertEquals(Language.English.getSectionHeading(), "English Medjugorje Pilgrimages");
        Assert.assertEquals(Language.Spanish.getSectionHeading(), "Peregrinaciones españolas a Medjugorje");
    }
}
