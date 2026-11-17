package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The mail merge's wording: the {@code {{field}}} vocabulary its author may use, the checks that a message uses
 * nothing else, and the substitution ({@link TokenText}, the engine trip reminders use). A field shared with
 * the reminders ({@link ReminderText}) has the same name and means the same thing.
 *
 * <p>This replaced EL ({@code #{to.first}}) in 2026-11: the merge text is typed by an organization's
 * {@code emailAdmin}, and evaluating it let that tenant run code on the shared server (medjugorje#79). Each field
 * carries a {@link Kind}, which says what it needs (a chosen trip) and who may use it; {@link MailMerge} applies
 * those rules before anything renders.
 *
 * <p>Deliberately absent (owner decision 2026-11-16): passport number and expiry, birthdate, TSA number, emergency
 * contact and a person's private notes. A merge is BCC-able to its sender, so a field is a way to read every
 * recipient's value, not only to send it.
 */
public final class MergeText {
    /** What a field is about, which decides what it needs and who may use it. */
    public enum Kind {
        PERSON("About each recipient", false),
        CONTACT("Each recipient's address and phone", false),
        TRIP("About the chosen trip", true),
        ON_TRIP("Each recipient on the chosen trip", true),
        MONEY("Each recipient's balance on the chosen trip", true),
        TRAVEL_DOCUMENTS("Each recipient's passport status for the chosen trip", true);

        private final String label;
        private final boolean needsTrip;

        Kind(final String label, final boolean needsTrip) {
            this.label = label;
            this.needsTrip = needsTrip;
        }

        public String getLabel() {
            return label;
        }

        /** Whether the field has no value until the author chooses a trip. */
        public boolean isNeedsTrip() {
            return needsTrip;
        }
    }

    /**
     * One field the author may type, with the help the page shows beside it. A class, not a record, for the
     * reason {@link ReminderText.Token} gives: a page's EL reads only a record's components.
     */
    public static final class Field {
        private final String name;
        private final String help;
        private final Kind kind;

        Field(final String name, final Kind kind, final String help) {
            this.name = name;
            this.kind = kind;
            this.help = help;
        }

        public String getName() {
            return name;
        }

        public String getHelp() {
            return help;
        }

        public Kind getKind() {
            return kind;
        }

        /** The field as it is typed: {@code {{name}}}. */
        public String getMarkup() {
            return "{{" + name + "}}";
        }
    }

    public static final String FIRST_NAME = ReminderText.FIRST_NAME;
    public static final String LEGAL_FIRST_NAME = "legalFirstName";
    public static final String MIDDLE_NAME = "middleName";
    public static final String LAST_NAME = "lastName";
    public static final String FULL_NAME = "fullName";
    public static final String EMAIL = "email";
    public static final String CELL = "cell";
    public static final String STREET = "street";
    public static final String STREET2 = "street2";
    public static final String CITY = "city";
    public static final String STATE = "state";
    public static final String ZIP = "zip";
    public static final String COUNTRY = "country";
    public static final String ROOM = "room";

    /** The whole vocabulary, in the order the page lists it. */
    public static final List<Field> FIELDS = List.of(
            new Field(FIRST_NAME, Kind.PERSON, "First name, as the person prefers to be called."),
            new Field(LEGAL_FIRST_NAME, Kind.PERSON, "First name as on their passport."),
            new Field(MIDDLE_NAME, Kind.PERSON, "Middle name."),
            new Field(LAST_NAME, Kind.PERSON, "Last name."),
            new Field(FULL_NAME, Kind.PERSON, "Preferred first name and last name."),
            new Field(EMAIL, Kind.PERSON, "Email address."),
            new Field(CELL, Kind.CONTACT, "Cell phone."),
            new Field(STREET, Kind.CONTACT, "Street address."),
            new Field(STREET2, Kind.CONTACT, "Second address line."),
            new Field(CITY, Kind.CONTACT, "City."),
            new Field(STATE, Kind.CONTACT, "State or province."),
            new Field(ZIP, Kind.CONTACT, "Postal code."),
            new Field(COUNTRY, Kind.CONTACT, "Country."),
            new Field(ReminderText.TRIP_TITLE, Kind.TRIP, "The trip's title."),
            new Field(ReminderText.TRIP_DATES, Kind.TRIP, "The trip's start and end dates."),
            new Field(ReminderText.DAYS_UNTIL, Kind.TRIP, "Days from today until the trip starts."),
            new Field(ReminderText.BALANCE_DUE_ON, Kind.TRIP, "The trip's balance due date."),
            new Field(ReminderText.ORG_NAME, Kind.TRIP, "The name of the trip's organization."),
            new Field(ReminderText.TRIP_LINK, Kind.TRIP, "Web address of the trip page."),
            new Field(ReminderText.PAYMENT_LINK, Kind.TRIP, "Web address of the trip's payment page."),
            new Field(ReminderText.DOCUMENTS_LINK, Kind.TRIP, "Web address where the trip's documents are signed."),
            new Field(ReminderText.ITINERARY_LINK, Kind.TRIP, "Web address of the trip's itinerary."),
            new Field(ReminderText.PROFILE_LINK, Kind.ON_TRIP,
                    "Web address of the person's profile, where a passport is entered."),
            new Field(ROOM, Kind.ON_TRIP, "The person's room on the trip, from their lodging reservation."),
            new Field(ReminderText.DOCUMENT_LIST, Kind.ON_TRIP, "The trip documents the person has not signed."),
            new Field(ReminderText.BALANCE_DUE, Kind.MONEY, "What the person still owes on the trip."),
            new Field(ReminderText.PASSPORT_ISSUE, Kind.TRAVEL_DOCUMENTS,
                    "What is wrong with the passport on file for this trip, or nothing when it is fine."));

    /** The fields of one {@link Kind}, under its label, as the page lists them. */
    public record Group(Kind kind, String label, List<Field> fields) {
    }

    private static final Map<String, Kind> KINDS = kindsOf(FIELDS);
    /**
     * An EL marker: a hash or dollar sign, then a brace that does NOT open a field. "Room #{{room}}" and
     * "${{balanceDue}}" are fields typed after a symbol, not expressions.
     */
    private static final Pattern EXPRESSION = Pattern.compile("[#$]\\{(?!\\{)");

    private MergeText() {
    }

    /** The vocabulary grouped by kind, in {@link Kind} order; built per call (a few dozen fields). */
    public static List<Group> groups() {
        return groupsOf(FIELDS);
    }

    private static List<Group> groupsOf(final List<Field> fields) {
        final List<Group> groups = new ArrayList<>();
        for (final Kind kind : Kind.values()) {
            groups.add(new Group(kind, kind.getLabel(),
                    fields.stream().filter(field -> field.getKind() == kind).toList()));
        }
        return List.copyOf(groups);
    }

    private static Map<String, Kind> kindsOf(final List<Field> fields) {
        final Map<String, Kind> kinds = new LinkedHashMap<>();
        for (final Field field : fields) {
            kinds.put(field.getName(), field.getKind());
        }
        return kinds;
    }

    /**
     * Whether any text still holds an EL marker: a hash or dollar sign before an opening brace. The merge refuses
     * such a message
     * rather than mailing the marker as text: its author almost certainly means the old personalization, and
     * every recipient would get the raw expression.
     */
    public static boolean hasExpression(final String... texts) {
        for (final String text : texts) {
            if (text != null && EXPRESSION.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** The {@code {{...}}} names in the texts that are not fields, in order, each once. */
    public static List<String> unknownFields(final String... texts) {
        final Set<String> unknown = new LinkedHashSet<>();
        for (final String text : texts) {
            unknown.addAll(TokenText.unknown(text, KINDS.keySet()));
        }
        return new ArrayList<>(unknown);
    }

    /** The fields the texts use, in order, each once; unknown names are left out. */
    public static Set<String> fieldsUsed(final String... texts) {
        final Set<String> used = new LinkedHashSet<>();
        for (final String text : texts) {
            for (final String name : TokenText.namesIn(text)) {
                if (KINDS.containsKey(name)) {
                    used.add(name);
                }
            }
        }
        return used;
    }

    /** The kinds of the fields the texts use. */
    public static Set<Kind> kindsUsed(final String... texts) {
        final Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (final String name : fieldsUsed(texts)) {
            kinds.add(KINDS.get(name));
        }
        return kinds;
    }

    /** The fields of the given kinds the texts use, as typed ({@code {{room}}}), for a refusal to name them. */
    public static List<String> markupOf(final Set<Kind> kinds, final String... texts) {
        final List<String> out = new ArrayList<>();
        for (final String name : fieldsUsed(texts)) {
            if (kinds.contains(KINDS.get(name))) {
                out.add("{{" + name + "}}");
            }
        }
        return out;
    }

    /** {@code body} as HTML with every field filled, each value HTML-escaped: the mail BODY. */
    public static String render(final String body, final Map<String, String> values) {
        return TokenText.substitute(body, values, KINDS.keySet(), true);
    }

    /** {@code subject} with every field filled as plain text: a subject is not HTML. */
    public static String renderPlain(final String subject, final Map<String, String> values) {
        return TokenText.substitute(subject, values, KINDS.keySet(), false);
    }
}
