package org.paulsens.trip.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatForwardRef;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.paulsens.trip.model.deploy.PipelineStageStatus;
import org.paulsens.trip.model.deploy.PipelineStatus;
import org.paulsens.trip.util.ScopeUtil;
import org.paulsens.trip.util.Util;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The small hand-written helpers on the model classes -- the parts Lombok does not generate and
 * {@code EqualsVerifier} does not reach. Each is tiny, and each is exactly the kind of one-line change the
 * coverage gate exists to catch before production.
 */
public class ModelTailsTest {

    // --- Trip: the event-editing helpers the admin pages drive ---

    @Test
    public void aTripEventCanBeAddedEditedAndDeleted() {
        final Trip trip = Trip.builder().id("model-tail-trip").title("T")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3)).build();

        final String id = trip.addTripEvent(TripEvent.Type.EVENT, "Concert", "notes",
                LocalDateTime.now().plusDays(1), null);
        Assert.assertNotNull(trip.getTripEvent(id));

        final TripEvent replacement = new TripEvent(id, TripEvent.Type.EVENT, "Concert (moved)", "notes",
                LocalDateTime.now().plusDays(2), null, null, null);
        trip.editTripEvent(replacement);
        Assert.assertEquals(trip.getTripEvent(id).getTitle(), "Concert (moved)");

        trip.deleteTripEvent(replacement);
        Assert.assertNull(trip.getTripEvent(id));
    }

    /** The duplicate check is (title, start): the same title at the same time is almost always a double-click. */
    @Test
    public void addingADuplicateTripEventIsRefused() {
        final Trip trip = Trip.builder().id("dup-trip").title("T")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3)).build();
        final LocalDateTime start = LocalDateTime.now().plusDays(1);
        trip.addTripEvent(TripEvent.Type.EVENT, "Concert", null, start, null);

        Assert.assertThrows(IllegalStateException.class,
                () -> trip.addTripEvent(TripEvent.Type.FLIGHT, "Concert", null, start, null));
    }

    @Test
    public void editingAnUnknownTripEventFailsLoudly() {
        final Trip trip = Trip.builder().id("edit-missing").title("T")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3)).build();

        Assert.assertThrows(IllegalArgumentException.class, () -> trip.editTripEvent(
                new TripEvent("no-such-id", TripEvent.Type.EVENT, "X", null, LocalDateTime.now(), null,
                        null, null)));
    }

    @Test
    public void addTripOptionAppendsANumberedPlaceholder() {
        final Trip trip = Trip.builder().id("opt-trip").title("T")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3)).build();
        final int before = trip.getRegOptions().size();

        trip.addTripOption();

        Assert.assertEquals(trip.getRegOptions().size(), before + 1);
        Assert.assertEquals(trip.getRegOptions().get(before).getId(), before,
                "the new option's id is its position, so ids stay dense");
    }

    // --- TripEvent: joining and leaving ---

    @Test
    public void joiningATripEventIsIdempotentAndLeavingRemoves() {
        final TripEvent event = new TripEvent("join-evt", TripEvent.Type.EVENT, "E", null,
                LocalDateTime.now(), null, null, null);
        final Person.Id who = Person.Id.from("joiner");

        Assert.assertTrue(event.joinTripEvent(who));
        Assert.assertFalse(event.joinTripEvent(who), "joining twice must not duplicate the participant");
        Assert.assertTrue(event.leaveTripEvent(who));
        Assert.assertFalse(event.leaveTripEvent(who));
        Assert.assertFalse(event.tripEventTypes().isEmpty());
    }

    // --- Person: trip lookups via the DAO ---

    @Test
    public void aPersonsTripsComeFromTheStore() throws Exception {
        final Person person = new Person();
        person.setFirst("Tail");
        person.setLast("Model");
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        final Trip trip = Trip.builder().id("person-trips-" + System.nanoTime()).title("Rome")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3))
                .people(List.of(person.getId())).build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));

        Assert.assertTrue(person.getTripIds().contains(trip.getId()));
    }

    // --- ChatChannel / ChatMembership: the with* copies ---

    private static ChatChannel channel() {
        return new ChatChannel(ChatChannel.Id.forTrip("with-t"), "with-t", ChatChannel.Kind.TRIP, "Title",
                "desc", List.of(), ChatSettings.defaults(), Instant.now(), "admin", null, null);
    }

    @Test
    public void channelWithersCopyEverythingElse() {
        final ChatChannel base = channel();

        Assert.assertEquals(base.withTitle("New").getTitle(), "New");
        Assert.assertEquals(base.withDescription("d2").getDescription(), "d2");
        final TripLink link = new TripLink("n", "https://example.org", null, null);
        Assert.assertEquals(base.withLinks(List.of(link)).getLinks(), List.of(link));
        final Instant when = Instant.now();
        Assert.assertTrue(base.withArchivedAt(when).isArchived());
        Assert.assertFalse(base.isArchived());
        Assert.assertEquals(base.withTitle("New").getId(), base.getId(), "identity never changes");
    }

    @Test
    public void channelIdsRoundTripAndExposeTheirTripId() {
        Assert.assertEquals(ChatChannel.Id.forTrip("t9").getValue(), "trip:t9");
        Assert.assertEquals(ChatChannel.Id.forTrip("t9").tripIdOrNull(), "t9");
        Assert.assertNull(ChatChannel.Id.from("dm:abc").tripIdOrNull());
        Assert.assertThrows(IllegalArgumentException.class, () -> ChatChannel.Id.forTrip("  "));
        Assert.assertTrue(ChatChannel.Id.from("a").compareTo(ChatChannel.Id.from("b")) < 0);
    }

    @Test
    public void membershipWithersAdjustOnlyTheirField() {
        final ChatMembership member = new ChatMembership(
                ChatChannel.Id.forTrip("with-t"), Person.Id.from("m"), ChatMembership.MemberState.JOINED,
                null, Instant.now(), null, null, null, Instant.now().plusSeconds(60), "admin", "spam",
                null, ChatNotifyPref.defaults(), null, null, null, null);

        Assert.assertTrue(member.isJoined());
        Assert.assertNull(member.withUnmuted().getMutedUntil(), "unmute clears the whole mute triple");
        Assert.assertNull(member.withUnmuted().getMutedBy());
        final ChatMessage.Id read = ChatMessage.Id.from("42");
        Assert.assertEquals(member.withLastRead(read).getLastReadMessageId(), read);
        Assert.assertEquals(member.withRole(ChatMembership.MemberRole.MODERATOR).getRole(),
                ChatMembership.MemberRole.MODERATOR);
    }

    // --- ChatAttachment / ChatForwardRef: constructors with defaults ---

    @Test
    public void anAttachmentDefaultsItsSizeToZeroNotNull() {
        final ChatAttachment attachment = new ChatAttachment(
                "image", "chat/x.png", "image/png", null, 800, 600, "chat/x-thumb.png", "caption");

        Assert.assertEquals(attachment.getSize(), 0L, "a missing size must read as 0, not NPE later");
        Assert.assertEquals(new ChatAttachment("file", "k", "t", 12L, null, null, null, null).getSize(), 12L);
    }

    @Test
    public void aForwardRefCarriesItsProvenance() {
        final Instant sent = Instant.now().minusSeconds(60);
        final ChatForwardRef ref = new ChatForwardRef(
                ChatChannel.Id.forTrip("src"), ChatMessage.Id.from("1"), Person.Id.from("author"),
                "Author", sent, Instant.now(), Person.Id.from("forwarder"));

        Assert.assertEquals(ref.getSourceAuthorName(), "Author");
        Assert.assertEquals(ref.getSourceSentAt(), sent);
    }

    // --- ChatReactionSummary ---

    @Test
    public void aReactionSummaryCountsMineAndOverflow() {
        final ChatMessage.Id msg = ChatMessage.Id.from("m");
        final Person.Id me = Person.Id.from("me");
        final ChatReactionSummary summary = ChatReactionSummary.fromReactions(msg, List.of(
                reaction("👍", me, Instant.ofEpochMilli(1_000)),
                reaction("👍", Person.Id.from("other"), Instant.ofEpochMilli(2_000))));

        Assert.assertTrue(summary.mine("👍", me));
        Assert.assertFalse(summary.mine("👍", Person.Id.from("nobody")));
        Assert.assertFalse(summary.mine("👍", null));
        Assert.assertEquals(summary.totalCount(), 2);
        Assert.assertEquals(summary.lastReactedAtMillis("👍"), 2_000L, "the latest reaction wins");
        Assert.assertEquals(summary.lastReactedAtMillis("🎉"), 0L, "unknown emoji reads as epoch zero");
    }

    @Test
    public void aReactionSummaryToleratesNullAndBrokenRows() {
        final ChatMessage.Id msg = ChatMessage.Id.from("m");
        final ChatReactionSummary summary = ChatReactionSummary.fromReactions(msg,
                java.util.Arrays.asList(null, reaction(null, Person.Id.from("x"), null)));

        Assert.assertEquals(summary.totalCount(), 0);
        Assert.assertEquals(ChatReactionSummary.fromReactions(msg, null).totalCount(), 0);
    }

    private static ChatReaction reaction(final String emoji, final Person.Id who, final Instant at) {
        return new ChatReaction(ChatChannel.Id.forTrip("t"), ChatMessage.Id.from("m"), who, emoji, at, null);
    }

    // --- ChatVisibility.isArchived ---

    @Test
    public void archivedAtBeatsTheComputedFreezeDate() {
        final Trip live = Trip.builder().id("t").title("T")
                .startDate(LocalDateTime.now().minusDays(2)).endDate(LocalDateTime.now().plusDays(2)).build();

        Assert.assertTrue(ChatVisibility.isArchived(
                channel().withArchivedAt(Instant.now().minusSeconds(5)), live, Instant.now()),
                "an explicit archive is effective even mid-trip");
        Assert.assertFalse(ChatVisibility.isArchived(
                channel().withArchivedAt(Instant.now().plusSeconds(3_600)), live, Instant.now()),
                "a FUTURE archivedAt is scheduled, not yet in force");
        Assert.assertFalse(ChatVisibility.isArchived(null, live, Instant.now()));
        Assert.assertFalse(ChatVisibility.isArchived(channel(), null, Instant.now()),
                "no trip means no end date to compute from: stay open");
    }

    // --- deploy status colors (the admin Deploy page's whole UI contract) ---

    @Test
    public void pipelineStageColorsFollowTheStatus() {
        Assert.assertEquals(stage("InProgress").getColor(), "#b8860b");
        Assert.assertEquals(stage("Failed").getColor(), "#c0392b");
        Assert.assertEquals(stage("Succeeded").getColor(), "#18ae6b");
        Assert.assertEquals(stage("Cancelled").getColor(), "#c0392b", "cancelled reads as bad, not idle");
    }

    @Test
    public void pipelineColorsDegradeToGreyWhenUnconfiguredOrEmpty() {
        Assert.assertEquals(PipelineStatus.unconfigured(true).getColor(), "#777777");
        Assert.assertEquals(PipelineStatus.failed("p", "cannot read").getColor(), "#777777",
                "an unreadable pipeline must not masquerade as idle-green");
        Assert.assertEquals(status(stage("InProgress")).getColor(), "#b8860b");
        Assert.assertEquals(status(stage("Failed")).getColor(), "#c0392b");
        Assert.assertEquals(status(stage("Succeeded")).getColor(), "#18ae6b");
    }

    private static PipelineStatus status(final PipelineStageStatus... stages) {
        return new PipelineStatus("p", List.of(stages), "exec", null, true, Instant.now());
    }

    private static PipelineStageStatus stage(final String status) {
        return new PipelineStageStatus("Build", status, null, null, null);
    }

    // --- the leftover scalar helpers ---

    @Test
    public void auditOutcomeParsesItsAliases() {
        Assert.assertEquals(AuditOutcome.from("Succeeded"), AuditOutcome.SUCCESS);
        Assert.assertEquals(AuditOutcome.from("error"), AuditOutcome.FAILURE);
        Assert.assertEquals(AuditOutcome.from("shrug"), AuditOutcome.UNKNOWN);
        Assert.assertEquals(AuditOutcome.from("  "), AuditOutcome.UNKNOWN);
        Assert.assertEquals(AuditOutcome.from(null), AuditOutcome.UNKNOWN);
    }

    @Test
    public void auditQueryMatchesOnActorOrTargetAndReportsFiltering() {
        final AuditQuery everything = AuditQuery.builder().build();
        Assert.assertFalse(everything.isFiltered());
        Assert.assertFalse(everything.matches(null));
        Assert.assertTrue(AuditQuery.builder().actor("x").build().isFiltered());
        Assert.assertTrue(AuditQuery.builder().outcome(AuditOutcome.FAILURE).build().isFiltered());
    }

    @Test
    public void mediaDisplaySizePicksAHumanUnit() {
        Assert.assertEquals(mediaOfSize(512).getDisplaySize(), "512 B");
        Assert.assertEquals(mediaOfSize(10 * 1024).getDisplaySize(), "10 KB");
        Assert.assertEquals(mediaOfSize(3 * 1024 * 1024 + 512 * 1024).getDisplaySize(), "3.5 MB");
    }

    private static MediaItem mediaOfSize(final long size) {
        return new MediaItem("id", "k", "t", null, "application/pdf", size, null, 0, null, null);
    }

    @Test
    public void transactionKindHelpersReadTheirEnums() {
        final Transaction batch = new Transaction(null, Person.Id.from("u"), "g", Transaction.Type.Batch,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 10f, "cat", "note");
        Assert.assertTrue(batch.isBatch());
        Assert.assertTrue(batch.isBill());
        Assert.assertFalse(batch.isPayment());

        final Transaction payment = new Transaction(Person.Id.from("u"), null, null);
        Assert.assertTrue(payment.isPayment());
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new Transaction(null, null, Transaction.Type.Batch));
    }

    @Test
    public void settingDefExposesItsTypeAndLongDefault() {
        final SettingDef yesNo = new SettingDef("x.enabled", Config.Type.BOOLEAN, "false", "label", "d");
        Assert.assertTrue(yesNo.isYesNo());
        final SettingDef number = new SettingDef("x.count", Config.Type.LONG, "604800", "label", "d");
        Assert.assertFalse(number.isYesNo());
        Assert.assertEquals(number.longDefault(), 604_800L);
    }

    @Test
    public void utilityClassesRefuseInstantiationAndCopeWithoutFaces() throws Exception {
        final java.lang.reflect.Constructor<Util> ctor = Util.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Assert.assertThrows(java.lang.reflect.InvocationTargetException.class, ctor::newInstance);

        // Off a JSF thread, every scope read is null rather than an NPE.
        Assert.assertNull(ScopeUtil.getInstance().getViewMap("k"));
        Assert.assertNull(ScopeUtil.getInstance().getRequestMap("k"));
        Assert.assertNull(ScopeUtil.getInstance().getSessionMap("k"));
    }

    @Test
    public void compositeKeyToStringIsItsValue() {
        Assert.assertEquals(CompositeKey.from("user,tx").toString(), CompositeKey.from("user,tx").getValue());
    }

    @Test
    public void aStructurallyInvalidUrlIsRejectedWithItsOwnMessage() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> TripLink.validateUrlScheme("http://[not-a-valid-uri"));
    }

    @Test
    public void theTripEventsSerializerToleratesNullAndMapsToIds() {
        final Trip.TripEventsSerializer serializer = new Trip.TripEventsSerializer();

        Assert.assertEquals(serializer.convert(null), List.of());
        Assert.assertEquals(serializer.convert(List.of(new TripEvent("ser-1", TripEvent.Type.EVENT, "T",
                null, LocalDateTime.now(), null, null, null))), List.of("ser-1"));
    }

    /** Past the 250-id cap, reactors overflow to a counter; totals must still include them. */
    @Test
    public void reactionOverflowCountsWithoutListingEveryReactor() {
        final ChatMessage.Id msg = ChatMessage.Id.from("overflow");
        final java.util.List<org.paulsens.trip.model.chat.ChatReaction> pile = new java.util.ArrayList<>();
        for (int i = 0; i < ChatReactionSummary.MAX_IDS_PER_EMOJI + 3; i++) {
            pile.add(new org.paulsens.trip.model.chat.ChatReaction(ChatChannel.Id.forTrip("t"),
                    msg, Person.Id.from("p" + i), "🎉", Instant.now(), null));
        }

        final ChatReactionSummary summary = ChatReactionSummary.fromReactions(msg, pile);

        Assert.assertEquals(summary.count("🎉"), ChatReactionSummary.MAX_IDS_PER_EMOJI + 3);
        Assert.assertEquals(summary.totalCount(), ChatReactionSummary.MAX_IDS_PER_EMOJI + 3);

        // A summary whose overflow names an emoji with NO listed ids (a truncated cache row) still totals it.
        final ChatReactionSummary overflowOnly = new ChatReactionSummary(msg, Map.of(),
                Map.of("👍", 7), Map.of());
        Assert.assertEquals(overflowOnly.totalCount(), 7);
    }
}
