package org.paulsens.trip.content;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;

/**
 * One person's card, as a programmatic content type: the board-of-directors row (photo, name, role, contact
 * details). The instance stores the person's ID plus whatever the page itself says about them (role, public
 * address); the name, cell, account email and photo are read LIVE from the person's account at render, so a
 * profile edit shows everywhere the person is listed with nothing duplicated. A row with no account (someone
 * who never signed up) is still possible: the name and phone overrides then carry the whole row.
 *
 * <p>Privacy: listing a person is a content editor's deliberate act of publication, so the account-derived
 * fields keep the reach the hand-written board page always gave them (the cell to everyone, the account
 * email to signed-in viewers) -- EXCEPT that a field the person has made PRIVATE is withheld here too. The
 * values an editor typed into the row (role, public email, name and phone overrides) are the page's own
 * words and always show.
 */
public final class PersonType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "person";
    public static final String PROP_PERSON_ID = "personId";
    public static final String PROP_ROLE = "role";
    public static final String PROP_PUBLIC_EMAIL = "publicEmail";
    public static final String PROP_NAME = "name";
    public static final String PROP_PHONE = "phone";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Person (from account)";
    }

    @Override
    public String getDescription() {
        return "One person's card: photo, name, role and contact details, read live from their account; "
                + "their privacy choices are honored.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_PERSON_ID, Placeholder.Type.PERSON, "Person",
                        "Type a name to search; leave empty for someone without an account", false),
                new Placeholder(PROP_ROLE, Placeholder.Type.TEXT, "Role / title",
                        "e.g. President; blank shows none", false),
                new Placeholder(PROP_PUBLIC_EMAIL, Placeholder.Type.TEXT, "Public email",
                        "The address to publish, e.g. a role mailbox", false),
                new Placeholder(PROP_NAME, Placeholder.Type.TEXT, "Name",
                        "Only when there is no account, or to show a different name", false),
                new Placeholder(PROP_PHONE, Placeholder.Type.TEXT, "Phone",
                        "Only when there is no account, or to show a different number", false));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/person.xhtml";
    }

    /**
     * What one row shows. {@code personId} is null when the row names no (existing) account;
     * {@code accountEmail} is the small signed-in-only line and {@code avatarEmail} feeds the gravatar
     * fallback, each null when withheld.
     */
    public record Card(String personId, String name, String role, String publicEmail, String accountEmail,
            String phone, String avatarEmail, String initials) {
    }

    /**
     * The card for a row, by the rules in the class comment. {@code person} is the resolved account, or null
     * when the row names none or names one that no longer exists.
     */
    public static Card card(final Map<String, String> values, final Person person, final boolean signedIn) {
        final String role = value(values, PROP_ROLE);
        final String publicEmail = value(values, PROP_PUBLIC_EMAIL);
        final String nameOverride = value(values, PROP_NAME);
        final String phoneOverride = value(values, PROP_PHONE);
        if (person == null) {
            return new Card(null, nameOverride, role, publicEmail, null, phoneOverride, null,
                    initials(nameOverride));
        }
        final String name = nameOverride != null ? nameOverride : accountName(person);
        final boolean emailShareable = person.getPrivacy().isEmailVisible() && present(person.getEmail());
        final String phone = phoneOverride != null ? phoneOverride
                : person.getPrivacy().isCellVisible() && present(person.getCell()) ? person.getCell() : null;
        final String accountEmail = signedIn && emailShareable
                && !person.getEmail().trim().equalsIgnoreCase(publicEmail == null ? "" : publicEmail)
                ? person.getEmail().trim() : null;
        return new Card(person.getId().getValue(), name, role, publicEmail, accountEmail, phone,
                emailShareable ? person.getEmail().trim() : null, initials(name));
    }

    private static String accountName(final Person person) {
        final String first = person.getPreferredName() == null ? "" : person.getPreferredName();
        final String last = person.getLast() == null ? "" : person.getLast();
        final String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    /** One or two letters for an avatar with no picture: first and last word's initials. */
    static String initials(final String name) {
        if (name == null) {
            return "";
        }
        final String[] words = name.trim().split("\\s+");
        final String first = words[0].isEmpty() ? "" : words[0].substring(0, 1);
        final String last = words.length > 1 ? words[words.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }

    private static String value(final Map<String, String> values, final String name) {
        final String raw = values == null ? null : values.get(name);
        return present(raw) ? raw.trim() : null;
    }

    private static boolean present(final String text) {
        return text != null && !text.isBlank();
    }
}
