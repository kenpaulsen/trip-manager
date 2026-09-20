package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatNotifications;
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

        Assert.assertEquals(inviteUses(selectorOf(token)), 1L, "a real join counts one use");
        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "ok",
                "redeeming twice is an idempotent success");
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L,
                "a second redeem by the same guest counts nothing: there was no join to count");
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

    // ------------------------------------------------------------- redeeming with standing of one's own

    /**
     * The gap this closed (2026-09-20): a family member clicking an invite used to be a silent no-op, so the
     * link's Uses stayed 0 and they never appeared on the roster, in {@code @all} or in the mention list.
     */
    @Test
    public void aFamilyMemberRedeemingAnInviteJoinsAsAMemberAndCountsAUse() throws IOException {
        final Person relative = savedRelative("family-redeemer");
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final String token = tokenOf(chat.createInvite(tripId, rosterMember, actor));

        Assert.assertEquals(chat.redeemInvite(tripId, token, relative.getId(), actor), "ok");
        final ChatMembership row = rowFor(relative.getId());
        Assert.assertNotNull(row, "a participant who redeems is recorded like anybody else");
        Assert.assertTrue(row.isJoined());
        Assert.assertFalse(row.isGuest(),
                "their access comes from the family, so the link must not become a grant that outlives it");
        Assert.assertEquals(row.getInvitedVia(), selectorOf(token), "the link that recorded them is kept");
        Assert.assertEquals(row.getJoinedAt(), channel.getCreated(),
                "an implicit member has been here since the channel existed: history must not shrink");
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L);
        Assert.assertTrue(chat.roster(tripId).stream()
                .anyMatch(m -> m.getPersonId().equals(relative.getId())), "the admin roster lists them");
        Assert.assertTrue(ChatNotifications.everyoneIn(
                        DAO.getInstance().getTrip(tripId, Cached.NO).orElseThrow(), rosterMember)
                .contains(relative.getId()), "@all now reaches them");
        Assert.assertTrue(chat.rosterJsonForTrip(tripId).contains(relative.getId().getValue()),
                "and they can be mentioned");
        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(relative.getId(), Cached.NO)
                .contains(channel.getId()), "the reverse row feeds My Chats");

        Assert.assertEquals(chat.redeemInvite(tripId, token, relative.getId(), actor), "ok");
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L, "re-clicking counts nothing");
    }

    @Test
    public void aLeftMemberRedeemingRejoinsWithoutTheGuestMarker() throws IOException {
        final Person relative = savedRelative("left-member");
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.leave(tripId, relative.getId(), actor));
        final Instant firstJoin = rowFor(relative.getId()).getJoinedAt();
        final String token = tokenOf(chat.createInvite(tripId, rosterMember, actor));

        Assert.assertEquals(chat.redeemInvite(tripId, token, relative.getId(), actor), "ok");
        final ChatMembership row = rowFor(relative.getId());
        Assert.assertTrue(row.isJoined());
        Assert.assertFalse(row.isGuest());
        Assert.assertNotNull(row.getAddedBackAt(), "coming back is recorded as a rejoin");
        Assert.assertEquals(row.getJoinedAt(), firstJoin, "joinedAt is the FIRST join, immutable");
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L, "a rejoin is a join and counts");
        Assert.assertNotNull(channel);
    }

    @Test
    public void aLeftGuestRedeemingAgainRejoinsAsAGuest() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id guest = outsider("returning-guest");
        Assert.assertTrue(DAO.getInstance().saveChatMembership(ChatMembership
                .guestJoining(channel.getId(), guest, Instant.now(), "sel-old")
                .withLeft(Instant.now(), "left on their own")));
        final String token = tokenOf(chat.createInvite(tripId, rosterMember, actor));

        Assert.assertEquals(chat.redeemInvite(tripId, token, guest, actor), "ok");
        final ChatMembership row = rowFor(guest);
        Assert.assertTrue(row.isJoined());
        Assert.assertTrue(row.isGuest(), "an outsider's row IS their access, so it stays guest-marked");
        Assert.assertEquals(row.getInvitedVia(), selectorOf(token), "the link that let them back in");
        Assert.assertTrue(chat.canParticipate(tripId, guest));
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L);
    }

    @Test
    public void aPlainJoinedRowHeldByAnOutsiderIsGuestMarkedOnRedeem() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id outsider = outsider("plain-row");
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channel.getId(), outsider, Instant.now())));
        Assert.assertFalse(chat.canParticipate(tripId, outsider), "a plain row grants nothing (locked rule)");
        final String token = tokenOf(chat.createInvite(tripId, rosterMember, actor));

        Assert.assertEquals(chat.redeemInvite(tripId, token, outsider, actor), "ok");
        Assert.assertTrue(rowFor(outsider).isGuest(), "the invite is what admits them, so it marks them");
        Assert.assertTrue(chat.canParticipate(tripId, outsider));
        Assert.assertEquals(inviteUses(selectorOf(token)), 1L);
    }

    @Test
    public void aRemovedFamilyMemberIsStillRefusedByAnInvite() throws IOException {
        final Person relative = savedRelative("removed-relative");
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channel.getId(), relative.getId(), Instant.now())
                        .withRemoved(Instant.now(), "moderation", "admin")));
        final String token = tokenOf(chat.createInvite(tripId, rosterMember, actor));

        Assert.assertEquals(chat.redeemInvite(tripId, token, relative.getId(), actor), "removed",
                "an invite must not undo a moderator's removal, family or not");
        Assert.assertEquals(rowFor(relative.getId()).getState(), ChatMembership.MemberState.REMOVED);
        Assert.assertEquals(inviteUses(selectorOf(token)), 0L, "a refusal counts no use");
    }

    // ------------------------------------------------------------------ joining by posting

    @Test
    public void postingAsAFamilyMemberWritesAPlainJoinedRow() throws IOException {
        final Person relative = savedRelative("poster");
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        Assert.assertNull(rowFor(relative.getId()), "no row before they say anything");

        Assert.assertTrue(chat.send(tripId, relative.getId(), "Hello everyone", null, null, actor).isOk());
        final ChatMembership row = rowFor(relative.getId());
        Assert.assertNotNull(row, "posting is joining: the rosters have to be able to see them");
        Assert.assertTrue(row.isJoined());
        Assert.assertFalse(row.isGuest());
        Assert.assertNull(row.getInvitedVia(), "no invite was involved");
        Assert.assertEquals(row.getJoinedAt(), channel.getCreated());
        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(relative.getId(), Cached.NO)
                .contains(channel.getId()));
        Assert.assertTrue(chat.roster(tripId).stream()
                .anyMatch(m -> m.getPersonId().equals(relative.getId())));

        final int before = chat.roster(tripId).size();
        Assert.assertTrue(chat.send(tripId, relative.getId(), "And again", null, null, actor).isOk());
        Assert.assertEquals(rowFor(relative.getId()), row, "a second post changes nothing");
        Assert.assertEquals(chat.roster(tripId).size(), before);
    }

    @Test
    public void postingAsARosterMemberWritesNoRow() {
        chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.send(tripId, rosterMember, "From the roster", null, null, actor).isOk());
        Assert.assertNull(rowFor(rosterMember),
                "roster members are in every list through the trip itself; a row would be noise");
    }

    // ------------------------------------------------------------------ helpers

    /** A saved person in a family with the roster member, so isTripMember admits them without any row. */
    private Person savedRelative(final String label) throws IOException {
        final Person relative = savedPerson(label);
        final Family family = new Family();
        family.getMemberIds().addAll(List.of(rosterMember, relative.getId()));
        family.getManagerIds().add(rosterMember);
        Assert.assertTrue(DAO.getInstance().saveFamily(family));
        relative.setFamilyId(family.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(relative));
        return relative;
    }

    private long inviteUses(final String selector) {
        return DAO.getInstance().getChatInvite(ChatChannel.Id.forTrip(tripId), selector, Cached.NO)
                .orElseThrow().getUses();
    }

    private ChatMembership rowFor(final Person.Id personId) {
        return DAO.getInstance().getChatMembership(ChatChannel.Id.forTrip(tripId), personId, Cached.NO)
                .orElse(null);
    }

    private static String selectorOf(final String token) {
        return token.substring(0, token.indexOf('.'));
    }

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
