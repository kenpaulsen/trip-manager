package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import org.paulsens.trip.model.chat.ChatMembership;

/**
 * The two ways a membership row comes into being for someone who is not simply on the trip's roster: redeeming
 * an invite link, and posting for the first time. One class so both write the same row shape, the same reverse
 * index row and the same audit record.
 *
 * <p>Why this exists (2026-09-20): a family member of a roster person, a {@code tripView} holder and a site
 * admin all participate through {@code ChatCommands.isTripMember} with NO row at all, because an absent row
 * means JOINED. Everything that answers "who is in this chat" walks the trip roster unioned with explicit
 * JOINED rows -- the admin roster table, the {@code @all} audience, the daily digest, the mention list -- so
 * those people were invisible in every one of them, and redeeming an invite was a silent no-op that counted no
 * use. The row this class writes for them is bookkeeping, NOT a grant: their access still comes from
 * {@code isTripMember}, and the locked rule that only a GUEST-marked row admits anybody is untouched.
 *
 * <p>Since 2026-10-21 it also owns the one way a LEFT row comes back without a click: a trip member OPENING the
 * chat ({@link #viewDenial}).
 *
 * <p>Its own class rather than more of {@link ChatCommands}, which is at its checkstyle length limit.
 */
@Slf4j
final class ChatJoin {

    private ChatJoin() {
    }

    /**
     * The {@code joinedAt} floor for someone who has been in the channel implicitly: the channel's own creation,
     * which is what {@code ChatCommands.materialize} uses. Writing {@code now} instead would hide every earlier
     * message from them the moment the row appeared, on a channel whose history they could already read.
     */
    static Instant floor(final ChatChannel channel, final Instant now) {
        final Instant created = channel == null ? null : channel.getCreated();
        return created == null ? now : created;
    }

    /**
     * The tail of {@code ChatCommands.redeemInvite}, after the token, expiry, archive and REMOVED refusals.
     *
     * @param participant whether this person already has standing without the link ({@code isTripMember}) -- if
     *                    so the row is NOT guest-marked, because a link must not outlive the family membership
     *                    or privilege it was clicked with.
     * @return the status token the redeem page renders: {@code ok} or {@code error}.
     */
    static String redeem(final ChatChannel channel, final Person.Id me, final ChatMembership existing,
            final boolean participant, final ChatInvite invite, final Instant now, final AuditActor actor) {
        final DAO dao = DAO.getInstance();
        if (existing != null && existing.isJoined() && (existing.isGuest() || participant)) {
            // Already in, by a row that says so: idempotent, and no use counted for a join that did not happen.
            // The reverse-row rewrite is the self-heal for a write lost below on an earlier click.
            dao.addGuestChatChannel(me, channel.getId());
            return "ok";
        }
        final AuditActor who = actor == null ? AuditActor.current() : actor;
        final ChatMembership row = admission(channel, me, existing, participant, invite.getSelector(), now, who);
        // Membership row first, reverse row second, the use count third: losing a later write only hides the
        // chat from that person's own list or under-counts a link, and re-clicking heals both.
        if (!dao.saveChatMembership(row)) {
            return "error";
        }
        dao.addGuestChatChannel(me, channel.getId());
        dao.recordChatInviteUse(invite);
        audit(who, channel, joinMessage(existing, participant, invite.getSelector()));
        return "ok";
    }

    /**
     * A plain JOINED row for someone posting who has none, so the rosters can see them. Roster members are
     * skipped: they are already in every one of those lists through the trip itself, and a row for them would
     * be noise an administrator has to read past. Never throws and never reports failure -- the message it
     * follows is already durable, and a lost row is repaired by their next post.
     */
    static void byPosting(final ChatChannel channel, final Person.Id author, final Trip trip,
            final Instant now, final AuditActor actor) {
        if (trip == null || author == null || trip.getPeople().contains(author)) {
            return;
        }
        try {
            final DAO dao = DAO.getInstance();
            if (!dao.saveChatMembership(ChatMembership.joining(channel.getId(), author, floor(channel, now)))) {
                // The id, not getValue(): a save only refuses when a key is null, which is when it would NPE.
                log.warn("Chat membership row for {} posting in {} was not written", author, channel.getId());
                return;
            }
            dao.addGuestChatChannel(author, channel.getId());
            audit(actor == null ? AuditActor.current() : actor, channel, "joined by posting");
        } catch (final RuntimeException ex) {
            log.warn("Could not record {} joining a chat by posting", author, ex);
        }
    }

    /**
     * The read gate for an actual OPEN -- the phone's feed, the chat page's GET -- as opposed to the digest, the
     * export, the roster and the notification fan-out, which ask {@code ChatCommands.readDenial} and must never
     * change anyone's standing. The one difference: a trip member whose row says LEFT is put back and let in.
     *
     * <p>Coming off a trip marks the row LEFT ({@code ChatCommands.leaveOnTripRemoval}), and being added back to
     * the trip never un-marked it, so a re-added traveler opened the chat to a blank thread and "You left this
     * chat" (2026-10-21: removed in August, re-approved in September, nothing loaded on the phone in October).
     * Someone who left on purpose is treated the same, because opening the chat again IS asking to be back in;
     * Leave still works, since every client stops polling once it has left. A LEFT guest keeps the Rejoin button
     * (no roster standing, the refusal {@code rejoin} gives too), and REMOVED is never reversed here.
     *
     * @return the wire denial code, or {@code null} when they may read, including right after being put back.
     */
    static String viewDenial(final ChatCommands chat, final ChatChannel channel, final Person.Id me) {
        final String denial = chat.readDenial(channel, me);
        if (!"LEFT_CHANNEL".equals(denial) || !chat.isTripMember(channel.getTripId(), me)) {
            return denial;
        }
        final ChatMembership left = DAO.getInstance().getChatMembership(channel.getId(), me, Cached.NO).orElse(null);
        return left != null && byOpening(channel, left, Instant.now(), AuditActor.current()) ? null : denial;
    }

    /**
     * {@code ChatCommands.membershipStateForTrip} for the chat page's open, after the open has had its effect
     * ({@link #viewDenial}), so the page draws the conversation instead of a "You are not in this chat" notice.
     * Only on the GET that opens the page: initPage re-runs on every postback and the Leave button is one, so
     * running this on a postback would undo the leave in the same request.
     */
    static String pageState(final ChatCommands chat, final String tripId, final Person.Id personId) {
        final String state = chat.membershipStateForTrip(tripId, personId);
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (!"LEFT".equals(state) || (ctx != null && ctx.isPostback())) {
            return state;
        }
        final ChatChannel channel = chat.getChannel(tripId);
        return channel != null && viewDenial(chat, channel, personId) == null ? "JOINED" : state;
    }

    /** The write behind {@link #viewDenial}: the LEFT row back to JOINED with its first joinedAt kept, audited. */
    private static boolean byOpening(final ChatChannel channel, final ChatMembership left, final Instant now,
            final AuditActor actor) {
        final AuditActor who = actor == null ? AuditActor.current() : actor;
        if (!DAO.getInstance().saveChatMembership(left.withRejoined(now, who.id()))) {
            return false;
        }
        // The reason is the audit line's point: "removed from the trip" reads apart from "self".
        audit(who, channel, "put back by opening the chat after leaving on " + left.getLeftAt()
                + (left.getLeftReason() == null ? "" : " (" + left.getLeftReason() + ")"));
        return true;
    }

    /** The row a redemption writes: guest-marked only for someone with no standing of their own. */
    private static ChatMembership admission(final ChatChannel channel, final Person.Id me,
            final ChatMembership existing, final boolean participant, final String selector,
            final Instant now, final AuditActor who) {
        if (existing == null) {
            return participant
                    ? ChatMembership.joining(channel.getId(), me, floor(channel, now))
                            .withProvenance(false, selector)
                    : ChatMembership.guestJoining(channel.getId(), me, now, selector);
        }
        // A LEFT row (a guest who left, or someone taken off the roster) comes back with joinedAt untouched --
        // it is the first join and the history floor, and rewriting it would hide their own earlier messages.
        final ChatMembership base = existing.isJoined() ? existing : existing.withRejoined(now, who.id());
        return base.withProvenance(!participant, selector);
    }

    private static String joinMessage(final ChatMembership existing, final boolean participant,
            final String selector) {
        final String how = participant ? "joined via invite " : "joined as guest via invite ";
        if (existing != null && existing.getLeftAt() != null) {
            return "re-" + how + selector + " after leaving on " + existing.getLeftAt();
        }
        return how + selector;
    }

    private static void audit(final AuditActor who, final ChatChannel channel, final String message) {
        Audit.log(Audit.builder(AuditAction.CHAT_JOIN, AuditOutcome.SUCCESS)
                .actor(who)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channel.getId().getValue())
                .message(message)
                .build());
    }
}
