package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * Membership in a channel. Absent row ⇒ JOINED for people on the trip (default opt-in).
 *
 * <p>{@link #joinedAt} is the <em>first</em> join and is immutable — rejoin updates {@link #addedBackAt} only.
 * Every state change must materialise a row (especially admin REMOVE of an implicit member).
 */
@Value
public class ChatMembership implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public static final int CURRENT_SCHEMA = 1;

    public enum MemberState {
        JOINED,
        LEFT,
        REMOVED,
        INVITED
    }

    public enum MemberRole {
        MEMBER,
        MODERATOR,
        OWNER
    }

    ChatChannel.Id channelId;
    Person.Id personId;
    MemberState state;
    MemberRole role;
    /** First join ever; written once, never updated. Visibility floor when fullHistory is off. */
    Instant joinedAt;
    Instant leftAt;
    String leftReason;
    /**
     * Who removed them, for an admin REMOVE. Distinct from {@link #leftReason}: the add-back warning has to name
     * the administrator AND the reason, and rendering the reason where a name belongs produced text like
     * "was removed by spamming the channel".
     */
    String removedBy;
    Instant mutedUntil;
    String mutedBy;
    String muteReason;
    ChatMessage.Id lastReadMessageId;
    ChatNotifyPref notify;
    Instant addedBackAt;
    String addedBackBy;
    int schemaVersion;
    /**
     * This member's own look for this channel, overriding the channel default per field. Never null; NONE means
     * "use the channel's". Per channel on purpose, so someone can style each trip differently.
     */
    ChatAppearance appearance;
    /**
     * Provenance, not a role: whether this row was created by redeeming an invite link rather than by trip
     * membership. Load-bearing for authorization — only a guest-marked JOINED row grants channel access to
     * someone {@code isTripMember} refuses, so a plain JOINED row (rejoin, roster backfill) can never become
     * a back door. Deliberately not a {@link MemberRole}: a guest could later be made MODERATOR.
     */
    boolean guest;
    /** The invite selector that admitted this guest, for the audit trail and the admin UI; null for members. */
    String invitedVia;

    @JsonCreator
    public ChatMembership(
            @JsonProperty("channelId") final ChatChannel.Id channelId,
            @JsonProperty("personId") final Person.Id personId,
            @JsonProperty("state") final MemberState state,
            @JsonProperty("role") final MemberRole role,
            @JsonProperty("joinedAt") final Instant joinedAt,
            @JsonProperty("leftAt") final Instant leftAt,
            @JsonProperty("leftReason") final String leftReason,
            @JsonProperty("removedBy") final String removedBy,
            @JsonProperty("mutedUntil") final Instant mutedUntil,
            @JsonProperty("mutedBy") final String mutedBy,
            @JsonProperty("muteReason") final String muteReason,
            @JsonProperty("lastReadMessageId") final ChatMessage.Id lastReadMessageId,
            @JsonProperty("notify") final ChatNotifyPref notify,
            @JsonProperty("addedBackAt") final Instant addedBackAt,
            @JsonProperty("addedBackBy") final String addedBackBy,
            @JsonProperty("schemaVersion") final Integer schemaVersion,
            @JsonProperty("appearance") final ChatAppearance appearance,
            @JsonProperty("guest") final Boolean guest,
            @JsonProperty("invitedVia") final String invitedVia) {
        this.channelId = channelId;
        this.personId = personId;
        this.state = state == null ? MemberState.JOINED : state;
        this.role = role == null ? MemberRole.MEMBER : role;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;
        this.leftReason = leftReason;
        this.removedBy = removedBy;
        this.mutedUntil = mutedUntil;
        this.mutedBy = mutedBy;
        this.muteReason = muteReason;
        this.lastReadMessageId = lastReadMessageId;
        this.notify = notify == null ? ChatNotifyPref.defaults() : notify;
        this.addedBackAt = addedBackAt;
        this.addedBackBy = addedBackBy;
        this.schemaVersion = schemaVersion == null ? CURRENT_SCHEMA : schemaVersion;
        this.appearance = appearance == null ? ChatAppearance.NONE : appearance;
        this.guest = guest != null && guest;
        this.invitedVia = invitedVia;
    }

    /** Pre-guest signature, kept so the many existing call sites don't carry two always-null parameters. */
    public ChatMembership(
            final ChatChannel.Id channelId, final Person.Id personId, final MemberState state,
            final MemberRole role, final Instant joinedAt, final Instant leftAt, final String leftReason,
            final String removedBy, final Instant mutedUntil, final String mutedBy, final String muteReason,
            final ChatMessage.Id lastReadMessageId, final ChatNotifyPref notify, final Instant addedBackAt,
            final String addedBackBy, final Integer schemaVersion, final ChatAppearance appearance) {
        this(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy, mutedUntil, mutedBy,
                muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy, schemaVersion, appearance,
                null, null);
    }

    /**
     * A brand-new JOINED row. A named factory because the all-args constructor takes sixteen positional
     * arguments, most of them null at creation -- counting nulls at a call site is how a value lands in the wrong
     * field, and the compiler cannot help when the neighbours are all the same type.
     */
    public static ChatMembership joining(
            final ChatChannel.Id channelId, final Person.Id personId, final Instant joinedAt) {
        return new ChatMembership(channelId, personId, MemberState.JOINED, MemberRole.MEMBER, joinedAt,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A guest admitted by an invite link: JOINED, guest-marked, carrying the admitting selector. */
    public static ChatMembership guestJoining(
            final ChatChannel.Id channelId, final Person.Id personId, final Instant joinedAt,
            final String inviteSelector) {
        return new ChatMembership(channelId, personId, MemberState.JOINED, MemberRole.MEMBER, joinedAt,
                null, null, null, null, null, null, null, null, null, null, null, null,
                Boolean.TRUE, inviteSelector);
    }

    // Every copy method funnels through the all-args constructor so the guest marker and invite provenance
    // survive each state change -- a with* that dropped them would silently lock a guest out on their next
    // mute, unmute, pref save or read-cursor write.
    public ChatMembership withState(final MemberState newState) {
        return new ChatMembership(channelId, personId, newState, role, joinedAt, leftAt, leftReason, removedBy,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withLeft(final Instant when, final String reason) {
        return new ChatMembership(channelId, personId, MemberState.LEFT, role, joinedAt, when, reason, removedBy,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withRemoved(final Instant when, final String reason, final String by) {
        return new ChatMembership(channelId, personId, MemberState.REMOVED, role, joinedAt, when, reason, by,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withRejoined(final Instant when, final String by) {
        return new ChatMembership(channelId, personId, MemberState.JOINED, role, joinedAt, null, null, null,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, when, by,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withMute(final Instant until, final String by, final String reason) {
        return new ChatMembership(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy,
                until, by, reason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withUnmuted() {
        return new ChatMembership(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy,
                null, null, null, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withAppearance(final ChatAppearance newAppearance) {
        return new ChatMembership(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, newAppearance, guest, invitedVia);
    }

    public ChatMembership withNotify(final ChatNotifyPref newNotify) {
        return new ChatMembership(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, newNotify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withLastRead(final ChatMessage.Id msgId) {
        return new ChatMembership(channelId, personId, state, role, joinedAt, leftAt, leftReason, removedBy,
                mutedUntil, mutedBy, muteReason, msgId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    public ChatMembership withRole(final MemberRole newRole) {
        return new ChatMembership(channelId, personId, state, newRole, joinedAt, leftAt, leftReason, removedBy,
                mutedUntil, mutedBy, muteReason, lastReadMessageId, notify, addedBackAt, addedBackBy,
                schemaVersion, appearance, guest, invitedVia);
    }

    @JsonIgnore
    public boolean isJoined() {
        return state == MemberState.JOINED;
    }

    @JsonIgnore
    public boolean isMuted(final Instant now) {
        return mutedUntil != null && now != null && mutedUntil.isAfter(now);
    }
}
