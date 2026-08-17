package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.security.Digests;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * Non-member chat access: whole-family membership, invite-link guests, and the hardening that came with
 * making a membership row mean something (rejoin's outsider refusal, removed-stays-removed).
 */
public class ChatGuestAccessTest {

    private ChatCommands chat;
    private AuditActor actor;
    private String tripId;
    private Person.Id rosterMember;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = java.util.UUID.randomUUID().toString();
        rosterMember = Person.Id.from("roster-" + RandomData.genAlpha(8));
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Guest access dedicated trip")
                .openToPublic(false)
                .description("Owned by ChatGuestAccessTest.")
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(rosterMember)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        actor = new AuditActor("guesttest@test", rosterMember.getValue());
    }

    // ------------------------------------------------------------------ family access

    /** ANY family member — not just a manager — participates when someone in the family is on the roster. */
    @Test
    public void anyFamilyMemberOfARosterPersonIsATripMember() throws IOException {
        final Person relative = savedPerson("relative");
        final Family family = new Family();
        family.getMemberIds().addAll(List.of(rosterMember, relative.getId()));
        family.getManagerIds().add(rosterMember);
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        relative.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(relative));

        Assert.assertTrue(chat.isTripMember(tripId, relative.getId()),
                "a non-manager family member of a roster person gets full chat membership");
        Assert.assertTrue(chat.canParticipate(tripId, relative.getId()));
    }

    @Test
    public void anUnrelatedFamilyGrantsNothing() throws IOException {
        final Person stranger = savedPerson("stranger");
        final Person otherRelative = savedPerson("other-relative");
        final Family family = new Family();
        family.getMemberIds().addAll(List.of(stranger.getId(), otherRelative.getId()));
        family.getManagerIds().add(stranger.getId());
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        stranger.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(stranger));

        Assert.assertFalse(chat.isTripMember(tripId, stranger.getId()),
                "a family with nobody on the roster grants nothing");
        Assert.assertFalse(chat.canParticipate(tripId, stranger.getId()));
    }

    // ------------------------------------------------------------------ guest rows

    @Test
    public void aGuestRowGrantsReadAndPostAndOnlyAGuestRow() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id guest = outsider("guest");
        Assert.assertFalse(chat.canParticipate(tripId, guest), "no row yet: an outsider has nothing");

        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.guestJoining(channel.getId(), guest, Instant.now(), "sel-test")));
        Assert.assertTrue(chat.canParticipate(tripId, guest));
        Assert.assertNull(chat.readDenial(channel, guest));
        Assert.assertTrue(chat.send(tripId, guest, "hello from a guest", null, null, actor).isOk());

        // A PLAIN JOINED row (no guest marker) must grant nothing to an outsider — this is the property
        // that keeps rejoin/backfillRoster from ever becoming a back door.
        final Person.Id plain = outsider("plain-row");
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channel.getId(), plain, Instant.now())));
        Assert.assertFalse(chat.canParticipate(tripId, plain));
        Assert.assertEquals(chat.readDenial(channel, plain), "NOT_A_TRIP_MEMBER");
    }

    @Test
    public void leftAndRemovedGuestsStayOut() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id guest = outsider("ousted-guest");
        final ChatMembership row = ChatMembership.guestJoining(
                channel.getId(), guest, Instant.now(), "sel-x");

        Assert.assertTrue(DAO.getInstance().saveChatMembership(row.withLeft(Instant.now(), "self")));
        Assert.assertEquals(chat.readDenial(channel, guest), "LEFT_CHANNEL");
        Assert.assertFalse(chat.send(tripId, guest, "still here?", null, null, actor).isOk());

        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                row.withRemoved(Instant.now(), "spam", "an-admin")));
        Assert.assertEquals(chat.readDenial(channel, guest), "REMOVED_FROM_CHANNEL");
        Assert.assertFalse(chat.canParticipate(tripId, guest));
    }

    @Test
    public void rejoinRefusesOutsidersButAllowsALeftGuestBack() {
        chat.ensureChannel(tripId, actor);
        final Person.Id outsider = outsider("rejoin-outsider");
        Assert.assertFalse(chat.rejoin(tripId, outsider, actor),
                "an outsider must not be able to write themselves a JOINED row");
        Assert.assertTrue(DAO.getInstance()
                .getChatMembership(ChatChannel.Id.forTrip(tripId), outsider, Cached.NO).isEmpty(),
                "the refused rejoin must not have written a row");

        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id guest = outsider("rejoin-guest");
        Assert.assertTrue(DAO.getInstance().saveChatMembership(ChatMembership
                .guestJoining(channel.getId(), guest, Instant.now(), "sel-r")
                .withLeft(Instant.now(), "self")));
        Assert.assertTrue(chat.rejoin(tripId, guest, actor), "a departed guest may come back on their own");
        final ChatMembership after = DAO.getInstance()
                .getChatMembership(channel.getId(), guest, Cached.NO).orElseThrow();
        Assert.assertTrue(after.isGuest(), "withRejoined must preserve the guest marker");
        Assert.assertTrue(after.isJoined());
    }

    // ------------------------------------------------------------------ invite mint + redeem

    @Test
    public void mintAndRedeemAdmitsAGuestEndToEnd() throws IOException {
        grantTripView(rosterMember);
        final String url = chat.createInvite(tripId, rosterMember, actor);
        Assert.assertNotNull(url, "a member who can post may mint an invite");
        final String token = tokenOf(url);
        Assert.assertTrue(url.contains("/trip/chatInvite.jsf?trip=" + tripId));

        // A real Person row: guests must have an account, and the mention-roster pin below resolves them.
        final Person.Id guest = savedPerson("redeemer").getId();
        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "ok");
        Assert.assertTrue(chat.canParticipate(tripId, guest));
        final ChatMembership row = DAO.getInstance()
                .getChatMembership(ChatChannel.Id.forTrip(tripId), guest, Cached.NO).orElseThrow();
        Assert.assertTrue(row.isGuest());
        Assert.assertNotNull(row.getInvitedVia());
        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(guest, Cached.NO)
                .contains(ChatChannel.Id.forTrip(tripId)), "the reverse row feeds My Chats");
        Assert.assertTrue(chat.rosterJsonForTrip(tripId).contains(guest.getValue()),
                "a guest joins the mention roster (the same JOINED-row union @all and the digest read)");

        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "ok",
                "redeeming twice is an idempotent success");
        Assert.assertTrue(chat.canInvite(tripId, guest),
                "a guest is a participant, so they may invite too");

        // The guest's chat shows up in their list, and disappears when an admin removes them.
        Assert.assertTrue(chat.myChats(guest).stream()
                .anyMatch(s -> s.channel().getId().equals(ChatChannel.Id.forTrip(tripId))));
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(rosterMember))));
        Assert.assertTrue(chat.removeMember(tripId, guest, "test removal",
                Caller.forActor(actor)));
        Assert.assertFalse(chat.canParticipate(tripId, guest));
        Assert.assertTrue(chat.myChats(guest).stream()
                .noneMatch(s -> s.channel().getId().equals(ChatChannel.Id.forTrip(tripId))),
                "a removed guest's list drops the chat");
        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "removed",
                "an invite must not bypass moderation");
    }

    @Test
    public void redeemRefusesBadExpiredAndRevokedTokens() {
        grantTripView(rosterMember);
        final String url = chat.createInvite(tripId, rosterMember, actor);
        final String token = tokenOf(url);
        final String selector = token.substring(0, token.indexOf('.'));
        final Person.Id guest = outsider("refused-redeemer");

        Assert.assertEquals(chat.redeemInvite(tripId, null, guest, actor), "invalid");
        Assert.assertEquals(chat.redeemInvite(tripId, "no-dot", guest, actor), "invalid");
        Assert.assertEquals(chat.redeemInvite(tripId, selector + ".wrong-validator", guest, actor),
                "invalid");
        Assert.assertEquals(chat.redeemInvite(null, token, guest, actor), "invalid");
        Assert.assertEquals(chat.redeemInvite(tripId, token, null, actor), "not-signed-in");

        // Expired: a row whose expires stamp has passed must refuse even though TTL has not reaped it.
        final ChatChannel.Id channelId = ChatChannel.Id.forTrip(tripId);
        Assert.assertTrue(DAO.getInstance().saveChatInvite(new ChatInvite(
                channelId, "sel-old", Digests.sha256Base64("old-validator"), rosterMember,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(60).getEpochSecond(), 0L)));
        Assert.assertEquals(chat.redeemInvite(tripId, "sel-old.old-validator", guest, actor), "invalid");

        // Revoked: the row is gone, the link dies immediately.
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(rosterMember))));
        Assert.assertTrue(chat.revokeInvite(tripId, selector, Caller.forActor(actor)));
        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "invalid");
        Assert.assertFalse(chat.canParticipate(tripId, guest), "nothing above may have admitted anyone");
    }

    @Test
    public void mintingIsGatedAndCapped() {
        final Person.Id outsider = outsider("mint-outsider");
        Assert.assertNull(chat.createInvite(tripId, outsider, actor),
                "an outsider may not mint invites");
        Assert.assertNull(chat.createInvite(tripId, null, actor));
        Assert.assertNull(chat.createInvite(null, rosterMember, actor));

        grantTripView(rosterMember);
        final int cap = new ConfigCommands().getInt(KnownSettings.CHAT_INVITE_MAX_OUTSTANDING);
        for (int count = 0; count < cap; count++) {
            Assert.assertNotNull(chat.createInvite(tripId, rosterMember, actor),
                    "mint " + count + " of " + cap + " should succeed");
        }
        Assert.assertNull(chat.createInvite(tripId, rosterMember, actor),
                "the outstanding-links cap must refuse mint " + (cap + 1));
        Assert.assertEquals(chat.listInvites(tripId, Caller.forActor(actor)).size(), 0,
                "listInvites is admin-only; a non-admin caller sees nothing");
    }

    @Test
    public void listInvitesShowsAdminsUnexpiredLinksNewestFirst() {
        grantTripView(rosterMember);
        Assert.assertNotNull(chat.createInvite(tripId, rosterMember, actor));
        // An expired row sits in the table until TTL reaps it; the listing must hide it.
        final ChatChannel.Id channelId = ChatChannel.Id.forTrip(tripId);
        Assert.assertTrue(DAO.getInstance().saveChatInvite(new ChatInvite(
                channelId, "sel-stale", "hash", rosterMember,
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(60).getEpochSecond(), 0L)));

        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(rosterMember))));
        final List<ChatInvite> listed = chat.listInvites(tripId, Caller.forActor(actor));
        Assert.assertEquals(listed.size(), 1, "one live link; the expired row is hidden");
        Assert.assertTrue(listed.stream().noneMatch(i -> i.getSelector().equals("sel-stale")));
    }

    /**
     * The chat page's trip resolution: the requested trip wins for anyone who may participate in its chat —
     * getTripForUser's "any trip you can see" fallback once showed a guest a DIFFERENT trip than the URL named.
     */
    @Test
    public void tripForChatPagePrefersTheRequestedTripForParticipants() throws IOException {
        final Trip other = Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Some other trip")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3))
                .people(new ArrayList<>())
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(other));

        // A participant of the requested trip: the fallback trip is overridden.
        Assert.assertEquals(chat.tripForChatPage(other, tripId, rosterMember).getId(), tripId);
        // A participant with nothing resolved: the requested trip is loaded.
        Assert.assertEquals(chat.tripForChatPage(null, tripId, rosterMember).getId(), tripId);
        // Resolved already matches: passed straight through.
        Assert.assertEquals(chat.tripForChatPage(other, other.getId(),
                Person.Id.from("whoever")).getId(), other.getId());
        // A non-participant keeps whatever page-level resolution said (fallback or null).
        final Person.Id outsider = outsider("resolution-outsider");
        Assert.assertEquals(chat.tripForChatPage(other, tripId, outsider).getId(), other.getId());
        Assert.assertNull(chat.tripForChatPage(null, tripId, outsider));
    }

    // ------------------------------------------------------------------ helpers

    private Person savedPerson(final String label) throws IOException {
        final Person person = Person.builder()
                .first(label).last(RandomData.genAlpha(8))
                .email(label + "." + RandomData.genAlpha(8) + "@example.com")
                .build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static Person.Id outsider(final String label) {
        return Person.Id.from(label + "-" + System.nanoTime());
    }

    private void grantTripView(final Person.Id personId) {
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("tripView", "Trip viewer", tripId, List.of(personId))));
    }

    private static String tokenOf(final String url) {
        final int at = url.indexOf("token=");
        Assert.assertTrue(at > 0, "invite URL must carry a token: " + url);
        return url.substring(at + "token=".length());
    }
}
