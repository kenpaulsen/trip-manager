package org.paulsens.trip.action;

import java.util.HashMap;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The daily-digest answer given at registration, from the form to the approved preference.
 *
 * <p>It was being written straight to a chat membership row and silently discarded, for two reasons at once: the
 * trip's channel does not exist until someone first opens the chat, and <b>registering does not put anyone on the
 * trip</b> -- a manager has to approve it -- so the preference write failed its trip-membership check as well.
 * The page ignored the returned {@code false}, so the switch appeared to work and nothing was stored.
 *
 * <p>The answer therefore rides on the registration and becomes a preference at approval. That ordering is the
 * thing these tests pin: registering must NOT put someone in the channel, and approving must.
 */
public class RegistrationDigestChoiceTest {

    private final ChatCommands chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    /** The second fake trip: it holds only some of the fake people, so a genuine non-member exists. */
    private static Trip trip() {
        return FakeData.getFakeTrips().get(1);
    }

    private static Registration registrationFor(final Person.Id who) {
        return new Registration(trip().getId(), who, null, Registration.Status.PENDING, new HashMap<>());
    }

    /** Someone not yet approved onto the trip. */
    private static Person.Id anOutsider() {
        return FakeData.getFakePeople().stream()
                .map(Person::getId)
                .filter(id -> !trip().getPeople().contains(id))
                .findFirst().orElseThrow();
    }

    @Test
    public void theAnswerRidesOnTheRegistration() {
        final Registration reg = registrationFor(anOutsider());
        Assert.assertFalse(chat.digestChoice(reg), "unanswered reads as no");

        chat.setDigestChoice(reg, true);
        Assert.assertEquals(reg.getOptions().get(ChatCommands.DIGEST_REG_OPTION), "true");
        Assert.assertTrue(chat.digestChoice(reg));

        chat.setDigestChoice(reg, false);
        Assert.assertFalse(chat.digestChoice(reg), "an answer must be changeable before approval");
    }

    @Test
    public void registeringDoesNotPutAnyoneInTheChannel() {
        // The whole reason the answer is parked rather than applied: a membership row IS channel membership.
        final Person.Id outsider = anOutsider();
        final Registration reg = registrationFor(outsider);
        chat.setDigestChoice(reg, true);

        Assert.assertFalse(chat.applyRegistrationDigestChoice(trip().getId(), reg),
                "an unapproved registrant is not on the trip, so no preference may be written for them");
        Assert.assertFalse(chat.dailyDigestForTrip(trip().getId(), outsider),
                "and they must not be signed up for the digest");
    }

    @Test
    public void approvingAppliesTheAnswer() {
        final Person.Id joining = anOutsider();
        final Registration reg = registrationFor(joining);
        chat.setDigestChoice(reg, true);

        // What the Approve button does: roster first, then the preference.
        final Trip trip = trip();
        trip.getPeople().add(joining);
        new TripCommands().saveTrip(trip);

        Assert.assertTrue(chat.applyRegistrationDigestChoice(trip.getId(), reg));
        Assert.assertTrue(chat.dailyDigestForTrip(trip.getId(), joining),
                "approval is when the answer given at registration becomes real");
    }

    @Test
    public void anUnansweredRegistrationLeavesTheDefaultsAlone() {
        // A trip with chat off never shows the question, and older registrations predate it. Neither should be
        // treated as "no" -- that would opt someone out of something they never declined.
        final Person.Id joining = anOutsider();
        final Registration reg = registrationFor(joining);
        Assert.assertFalse(chat.applyRegistrationDigestChoice(trip().getId(), reg),
                "no answer means nothing is written");
        Assert.assertFalse(reg.getOptions().containsKey(ChatCommands.DIGEST_REG_OPTION));
    }

    @Test
    public void decliningIsAlsoApplied() {
        final Person.Id joining = anOutsider();
        final Registration reg = registrationFor(joining);
        chat.setDigestChoice(reg, false);

        final Trip trip = trip();
        trip.getPeople().add(joining);
        new TripCommands().saveTrip(trip);

        Assert.assertTrue(chat.applyRegistrationDigestChoice(trip.getId(), reg));
        Assert.assertFalse(chat.dailyDigestForTrip(trip.getId(), joining));
    }

    @Test
    public void nullsAreSafe() {
        chat.setDigestChoice(null, true);
        Assert.assertFalse(chat.digestChoice(null));
        Assert.assertFalse(chat.applyRegistrationDigestChoice(trip().getId(), null));
    }
}
