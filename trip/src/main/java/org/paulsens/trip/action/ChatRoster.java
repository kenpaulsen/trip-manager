package org.paulsens.trip.action;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;

/**
 * Who is in a chat, named the way a person reading an admin page needs them named.
 *
 * <p>A stored {@link ChatMembership} carries only a person id, and a moderator cannot recognise a uuid — every
 * screen that asks somebody to pick, mute or remove a member has to join the row to its {@link Person} first.
 * Doing that here rather than in per-cell page expressions decides the blank-name and missing-person cases once,
 * and gives the tables a real string to sort on.
 *
 * <p>Its own class rather than more of {@link ChatCommands}, which is at its length limit; this is view
 * assembly with no authorization of its own, so callers stay responsible for gating the page. Public so that
 * the page's EL can reach the record accessors — a public record nested in a package-private class is not
 * reflectively reachable, and the failure is an unhelpful one at render time.
 */
public final class ChatRoster {

    private ChatRoster() {
    }

    /** Each membership row joined to the person it names, newest state and all. */
    static List<ChatRoster.RosterRow> view(final List<ChatMembership> rows) {
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Instant now = Instant.now();
        final List<RosterRow> out = new ArrayList<>();
        for (final ChatMembership row : rows) {
            out.add(rowFor(row, people.getPerson(row.getPersonId()), now));
        }
        // Mutable on purpose: a PrimeFaces dataTable sorts its value list in place.
        return out;
    }

    /**
     * The people of this chat: the trip's roster together with anyone whose explicit row says JOINED.
     *
     * <p>The union is the definition of "in this chat" — family managers and invited guests participate without
     * ever appearing on the trip's own roster — and the mention autocomplete and the moderation picker have to
     * agree about it, or a moderator is offered somebody they cannot act on (or cannot reach somebody they can).
     */
    static List<Person> members(final String tripId) {
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.NO).orElse(null);
        final LinkedHashSet<Person.Id> ids =
                new LinkedHashSet<>(trip == null ? List.<Person.Id>of() : trip.getPeople());
        for (final ChatMembership row : DAO.getInstance()
                .listChatMembers(ChatChannel.Id.forTrip(tripId), Cached.NO)) {
            if (row.getState() == ChatMembership.MemberState.JOINED) {
                ids.add(row.getPersonId());
            }
        }
        final List<Person> found = new ArrayList<>();
        for (final Person.Id id : ids) {
            final Person person = people.getPerson(id);
            if (person != null) {
                found.add(person);
            }
        }
        return found;
    }

    /**
     * {@link #members} as picker choices, sorted by label: the whole point of the picker is that a moderator
     * finds somebody by the name they know them by instead of looking an id up first.
     */
    static List<PersonChoice> choices(final String tripId) {
        final List<PersonChoice> out = new ArrayList<>();
        for (final Person person : members(tripId)) {
            out.add(new PersonChoice(person.getId().getValue(), pickerLabel(person)));
        }
        out.sort(Comparator.comparing(PersonChoice::label, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static RosterRow rowFor(final ChatMembership row, final Person person, final Instant now) {
        // The email is shown unredacted: these screens are gated on administering the chat, and telling two
        // people with one name apart is exactly what the address is needed for. Contrast the mention labels
        // in ChatCommands, which are broadcast into the conversation and so honour email privacy.
        final String email = (person == null || person.getEmail() == null) ? "" : person.getEmail();
        return new RosterRow(row.getPersonId().getValue(), displayName(person, row.getPersonId()), email,
                row.getState(), row.isGuest(), row.getInvitedVia(), row.getJoinedAt(), row.getMutedUntil(),
                row.isMuted(now));
    }

    /** "Last, First (email)" — the email is what separates two people who share a name. */
    private static String pickerLabel(final Person person) {
        final String name = displayName(person, person.getId());
        final String email = person.getEmail() == null ? "" : person.getEmail().trim();
        return email.isBlank() ? name : name + " (" + email + ")";
    }

    /** "Last, First" — how somebody scanning a roster looks a member up. Never blank. */
    static String displayName(final Person person, final Person.Id id) {
        final String last = (person == null || person.getLast() == null) ? "" : person.getLast().trim();
        final String first = (person == null || person.getFirst() == null) ? "" : person.getFirst().trim();
        if (last.isEmpty() && first.isEmpty()) {
            // A nameless row is still somebody on the roster, and the id names them uniquely -- better than an
            // empty cell, which reads as a rendering fault and leaves nothing to act on.
            return id == null ? "" : id.getValue();
        }
        if (last.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return last;
        }
        return last + ", " + first;
    }

    /**
     * A roster line of {@code chatSettings.xhtml}.
     *
     * <p>{@link #joinedMillis()} and {@link #mutedUntilMillis()} exist because the server cannot know the
     * viewer's time zone: the page carries the instant to the browser, the only party that can render it in
     * local time and name that zone. The {@code *Utc} strings are what is read before (or without) that
     * rewrite, labelled UTC so an unconverted time is never mistaken for a local one.
     */
    public record RosterRow(String personId, String name, String email, ChatMembership.MemberState state,
            boolean guest, String invitedVia, Instant joinedAt, Instant mutedUntil, boolean muted) {

        private static final DateTimeFormatter UTC_STAMP =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

        public long joinedMillis() {
            return joinedAt == null ? 0L : joinedAt.toEpochMilli();
        }

        public long mutedUntilMillis() {
            return mutedUntil == null ? 0L : mutedUntil.toEpochMilli();
        }

        public String joinedUtc() {
            return utc(joinedAt);
        }

        public String mutedUntilUtc() {
            return utc(mutedUntil);
        }

        private static String utc(final Instant when) {
            return when == null ? "" : UTC_STAMP.format(when) + " UTC";
        }
    }

    /** One entry of a person picker: the id a command needs, under the name a human recognises. */
    public record PersonChoice(String id, String label) {
    }
}
