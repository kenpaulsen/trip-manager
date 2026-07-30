package org.paulsens.trip.model.chat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.model.Person;

/**
 * The stored form of an @mention: <code>@{personId}</code>.
 *
 * <p><b>Defined in P1 even though only notifications (P3) will consume it</b>, because it is the one thing here
 * that cannot be retrofitted. Mentions are embedded in message bodies, and bodies are immutable history; a syntax
 * invented later cannot be applied to messages already written, so every mention sent before then would be
 * permanently invisible to the feature.
 *
 * <p>Storing the <em>id</em> rather than the typed name is what makes a mention survive a rename, and it is why
 * rendering needs a resolver: the display name is looked up at render time, never baked into the body.
 *
 * <p>Pure functions only. {@code Serializable} solely to satisfy the model package sweep, as {@code ChatVisibility}
 * is.
 */
public final class ChatMentions implements Serializable {

    /** Matches {@code @{id}} where the id is a UUID-ish token; deliberately strict so prose cannot match. */
    private static final Pattern MENTION = Pattern.compile("@\\{([A-Za-z0-9._@+-]{1,128})}");

    /**
     * The everyone-mention, matched as typed rather than as a stored id.
     *
     * <p>Unlike a personal mention there is no id to resolve, so this is literal text and must be recognised on
     * a word boundary — otherwise an email address or {@code @allen} would broadcast to the whole trip.
     */
    private static final Pattern ALL = Pattern.compile("(?<![A-Za-z0-9._@+-])@all(?![A-Za-z0-9._+-])",
            Pattern.CASE_INSENSITIVE);

    private ChatMentions() {
    }

    /** Whether this body addresses everyone with {@code @all}. */
    public static boolean mentionsEveryone(final String body) {
        return body != null && ALL.matcher(body).find();
    }

    /** The person ids mentioned in this body, in order, without duplicates. */
    public static List<Person.Id> extract(final String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        final Set<String> seen = new LinkedHashSet<>();
        final Matcher m = MENTION.matcher(body);
        while (m.find()) {
            seen.add(m.group(1));
        }
        final List<Person.Id> out = new ArrayList<>(seen.size());
        for (final String id : seen) {
            out.add(Person.Id.from(id));
        }
        return out;
    }

    /** True when this body mentions the given person. */
    public static boolean mentions(final String body, final Person.Id personId) {
        return personId != null && extract(body).contains(personId);
    }

    /**
     * The token to store for a person. The UI turns a picked name into this before the body is saved, so what is
     * persisted never depends on how the name was spelled at the time.
     */
    public static String token(final Person.Id personId) {
        return personId == null ? "" : "@{" + personId.getValue() + "}";
    }
}
