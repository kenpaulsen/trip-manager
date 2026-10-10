package org.paulsens.trip.action;

import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatMessage;

/**
 * A family manager posting ON BEHALF OF a member: the one sanctioned way to post for someone else.
 *
 * <p>The message stays authored by the signed-in sender -- membership, mute, rate limit, staged photos and
 * adminship are all theirs, and the {@code ChatCommands.send} contract that {@code authorId} IS the caller is
 * untouched -- and carries {@code ChatMessage.onBehalfOfId} naming the member it was posted FOR. Every renderer
 * (the site, mail, push, the digest, the apps) shows that as {@code "Author on behalf of Member"}, composed from
 * the two ids with {@link #JOINER}; it is never body markup. Reply quotes name the author only: a quote says
 * whose words these are, and the stamp stays visible on the original.
 *
 * <p>Its own class rather than more of {@link ChatCommands}, which is at its checkstyle length limit.
 */
public final class ChatOnBehalf {

    /** The byline joiner; the website and the apps compose the same words from the ids. */
    public static final String JOINER = " on behalf of ";

    private ChatOnBehalf() {
    }

    /**
     * Null when the stamp is allowed (or absent); otherwise the refusal, code {@code on_behalf}. The rules: never
     * yourself; only someone the author manages ({@code PersonCommands.canAccessUserId}); only a traveler on the
     * trip's roster -- the same roster test the family branch of {@code ChatCommands.isTripMember} applies, so a
     * PENDING registrant is not yet someone you can speak for.
     */
    static ChatCommands.SendResult denial(final Trip trip, final Person.Id authorId, final Person.Id onBehalfOfId) {
        if (onBehalfOfId == null) {
            return null;
        }
        if (onBehalfOfId.equals(authorId)) {
            return ChatCommands.SendResult.fail("on_behalf", "You cannot post on behalf of yourself.");
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        if (!people.canAccessUserId(people.getPerson(authorId), onBehalfOfId)) {
            return ChatCommands.SendResult.fail("on_behalf",
                    "Only a family manager can post on behalf of that member.");
        }
        if (trip == null || !trip.getPeople().contains(onBehalfOfId)) {
            return ChatCommands.SendResult.fail("on_behalf", "That person is not on this trip.");
        }
        return null;
    }

    /**
     * The byline for a stored message: the author's name, or "Author on behalf of Member" when stamped. Composed
     * here, once, so mail, push and the digest never disagree about the words. Album rows deliberately keep
     * {@link #authorName}: the UPLOAD is the manager's own act.
     */
    public static String byline(final ChatMessage stored) {
        final String author = authorName(stored.getAuthorId());
        if (stored.getOnBehalfOfId() == null) {
            return author;
        }
        return author + JOINER + displayNameOrId(PersonCommands.getPersonCommands(),
                stored.getOnBehalfOfId().getValue());
    }

    /** The author's name for a notification subject line, resolved on the request thread while people are cheap. */
    static String authorName(final Person.Id authorId) {
        return authorId == null ? "Someone" : displayNameOrId(PersonCommands.getPersonCommands(), authorId.getValue());
    }

    /**
     * A person's display name, falling back to their id.
     *
     * <p>The fallback covers a null name, not just a missing person. {@code Person.getPreferredName()} returns
     * {@code first} when there is no nickname, and {@code first} is nullable -- so a person with neither would put
     * a null into the display-name map, and {@code Map.copyOf} in the {@code ChatPage} constructor rejects null
     * values. That threw inside the feed, which means <b>one such person on a trip 500s the chat for everyone on
     * it</b>, not just for themselves.
     */
    public static String displayNameOrId(final PersonCommands people, final String id) {
        final Person person = people.getPerson(Person.Id.from(id));
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank() ? id : name;
    }
}
