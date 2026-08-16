package org.paulsens.trip.action;

import java.time.Instant;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The draft registry behind the trip edit pages. The property that matters most is SAME-INSTANCE: partial
 * ajax submits accumulate only because every postback's {@code get} hands back the one heap object — a copy
 * would silently discard every edit but the last, which is precisely the bug shape the old
 * viewScope-serialization approach never had and this replacement must not introduce.
 */
public class TripEditDraftsTest {

    private static final Person.Id OWNER = Person.Id.from("owner");
    private static final Person.Id INTRUDER = Person.Id.from("intruder");

    private static Trip aTrip(final String id) {
        return Trip.builder().id(id).title("Trip " + id).build();
    }

    @Test
    public void getReturnsTheSameInstanceEveryTime() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final Trip working = aTrip("t1");
        final String token = drafts.start(working, OWNER);
        Assert.assertNotNull(token, "starting a draft should mint a token");
        Assert.assertSame(drafts.get(token, OWNER), working,
                "get must return the SAME instance, or accumulated edits are lost");
        Assert.assertSame(drafts.get(token, OWNER), working, "and again on the next postback");
    }

    @Test
    public void editsAccumulateAcrossGets() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final String token = drafts.start(aTrip("t1"), OWNER);
        drafts.get(token, OWNER).setTitle("First edit");
        drafts.get(token, OWNER).setDescription("Second edit");
        final Trip result = drafts.get(token, OWNER);
        Assert.assertEquals(result.getTitle(), "First edit");
        Assert.assertEquals(result.getDescription(), "Second edit");
    }

    @Test
    public void aDraftIsClaimableOnlyByItsOwner() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final String token = drafts.start(aTrip("t1"), OWNER);
        Assert.assertNull(drafts.get(token, INTRUDER), "another user's id must not claim the draft");
        Assert.assertNotNull(drafts.get(token, OWNER), "a foreign attempt must not damage the draft");
    }

    @Test
    public void nullsNeverStartNorResolveADraft() {
        final TripEditDrafts drafts = new TripEditDrafts();
        Assert.assertNull(drafts.start(null, OWNER), "no working copy, no draft");
        Assert.assertNull(drafts.start(aTrip("t1"), null),
                "no owner means an unauthenticated request; refusing keeps anonymous hammering out");
        final String token = drafts.start(aTrip("t1"), OWNER);
        Assert.assertNull(drafts.get(null, OWNER));
        Assert.assertNull(drafts.get(token, null));
        Assert.assertNull(drafts.get("no-such-token", OWNER));
    }

    @Test
    public void discardEndsTheDraft() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final String token = drafts.start(aTrip("t1"), OWNER);
        drafts.discard(token);
        Assert.assertNull(drafts.get(token, OWNER), "a discarded draft is gone");
        drafts.discard(token);
        drafts.discard(null);   // both no-ops, not errors
        Assert.assertEquals(drafts.size(), 0);
    }

    @Test
    public void anIdleDraftExpires() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final Instant abandoned = Instant.now().minus(TripEditDrafts.IDLE_TTL).minusSeconds(60);
        final String token = drafts.start(aTrip("t1"), OWNER, abandoned);
        Assert.assertNull(drafts.get(token, OWNER), "a draft idle past the TTL is gone");
    }

    @Test
    public void aRecentTouchKeepsADraftAlive() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final Instant nearlyIdle = Instant.now().minus(TripEditDrafts.IDLE_TTL).plusSeconds(60);
        final String token = drafts.start(aTrip("t1"), OWNER, nearlyIdle);
        Assert.assertNotNull(drafts.get(token, OWNER), "one minute short of idle is still alive");
        Assert.assertNotNull(drafts.get(token, OWNER), "and the get refreshed the clock");
    }

    @Test
    public void idleDraftsAreSweptWhenANewOneStarts() {
        final TripEditDrafts drafts = new TripEditDrafts();
        final Instant abandoned = Instant.now().minus(TripEditDrafts.IDLE_TTL).minusSeconds(60);
        drafts.start(aTrip("t1"), OWNER, abandoned);
        drafts.start(aTrip("t2"), OWNER, abandoned);
        drafts.start(aTrip("t3"), OWNER);
        Assert.assertEquals(drafts.size(), 1, "starting a draft sweeps the abandoned ones");
    }

    @Test
    public void overflowEvictsTheLeastRecentlyTouchedDraft() {
        final TripEditDrafts drafts = new TripEditDrafts();
        drafts.maxDraftsForTest(2);
        final String first = drafts.start(aTrip("t1"), OWNER);
        final String second = drafts.start(aTrip("t2"), OWNER);
        Assert.assertNotNull(drafts.get(first, OWNER));    // touch: first is now the fresher of the two
        final String third = drafts.start(aTrip("t3"), OWNER);
        Assert.assertEquals(drafts.size(), 2);
        Assert.assertNull(drafts.get(second, OWNER), "the least-recently-touched draft is evicted");
        Assert.assertNotNull(drafts.get(first, OWNER), "the touched draft survives");
        Assert.assertNotNull(drafts.get(third, OWNER), "the newest draft survives");
    }
}
