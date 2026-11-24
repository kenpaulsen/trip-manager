package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.paulsens.trip.i18n.Text;

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
        PERSON("feedback.mergeText.kindPerson", false),
        CONTACT("feedback.mergeText.kindContact", false),
        TRIP("feedback.mergeText.kindTrip", true),
        ON_TRIP("feedback.mergeText.kindOnTrip", true),
        MONEY("feedback.mergeText.kindMoney", true),
        TRAVEL_DOCUMENTS("feedback.mergeText.kindTravelDocuments", true);

        private final String labelKey;
        private final boolean needsTrip;

        Kind(final String labelKey, final boolean needsTrip) {
            this.labelKey = labelKey;
            this.needsTrip = needsTrip;
        }

        /** The label in the language of the request in progress. */
        public String getLabel() {
            return Text.get(labelKey);
        }

        /** Whether the field has no value until the author chooses a trip. */
        public boolean isNeedsTrip() {
            return needsTrip;
        }
    }

    /**
     * One field the author may type: a reminder's {@link ReminderText.Token} (name, help key, markup) plus the
     * {@link Kind} that says what it needs and who may use it.
     */
    public static final class Field extends ReminderText.Token {
        private final Kind kind;

        Field(final String name, final Kind kind, final String helpKey) {
            super(name, helpKey);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
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
            new Field(FIRST_NAME, Kind.PERSON, "feedback.mergeText.helpFirstName"),
            new Field(LEGAL_FIRST_NAME, Kind.PERSON, "feedback.mergeText.helpLegalFirstName"),
            new Field(MIDDLE_NAME, Kind.PERSON, "feedback.mergeText.helpMiddleName"),
            new Field(LAST_NAME, Kind.PERSON, "feedback.mergeText.helpLastName"),
            new Field(FULL_NAME, Kind.PERSON, "feedback.mergeText.helpFullName"),
            new Field(EMAIL, Kind.PERSON, "feedback.mergeText.helpEmail"),
            new Field(CELL, Kind.CONTACT, "feedback.mergeText.helpCell"),
            new Field(STREET, Kind.CONTACT, "feedback.mergeText.helpStreet"),
            new Field(STREET2, Kind.CONTACT, "feedback.mergeText.helpStreet2"),
            new Field(CITY, Kind.CONTACT, "feedback.mergeText.helpCity"),
            new Field(STATE, Kind.CONTACT, "feedback.mergeText.helpState"),
            new Field(ZIP, Kind.CONTACT, "feedback.mergeText.helpZip"),
            new Field(COUNTRY, Kind.CONTACT, "feedback.mergeText.helpCountry"),
            new Field(ReminderText.TRIP_TITLE, Kind.TRIP, "feedback.mergeText.helpTripTitle"),
            new Field(ReminderText.TRIP_DATES, Kind.TRIP, "feedback.mergeText.helpTripDates"),
            new Field(ReminderText.DAYS_UNTIL, Kind.TRIP, "feedback.mergeText.helpDaysUntil"),
            new Field(ReminderText.BALANCE_DUE_ON, Kind.TRIP, "feedback.mergeText.helpBalanceDueOn"),
            new Field(ReminderText.ORG_NAME, Kind.TRIP, "feedback.mergeText.helpOrgName"),
            new Field(ReminderText.TRIP_LINK, Kind.TRIP, "feedback.mergeText.helpTripLink"),
            new Field(ReminderText.PAYMENT_LINK, Kind.TRIP, "feedback.mergeText.helpPaymentLink"),
            new Field(ReminderText.DOCUMENTS_LINK, Kind.TRIP, "feedback.mergeText.helpDocumentsLink"),
            new Field(ReminderText.ITINERARY_LINK, Kind.TRIP, "feedback.mergeText.helpItineraryLink"),
            new Field(ReminderText.PROFILE_LINK, Kind.ON_TRIP,
                    "feedback.mergeText.helpProfileLink"),
            new Field(ROOM, Kind.ON_TRIP, "feedback.mergeText.helpRoom"),
            new Field(ReminderText.DOCUMENT_LIST, Kind.ON_TRIP, "feedback.mergeText.helpDocumentList"),
            new Field(ReminderText.BALANCE_DUE, Kind.MONEY, "feedback.mergeText.helpBalanceDue"),
            new Field(ReminderText.PASSPORT_ISSUE, Kind.TRAVEL_DOCUMENTS,
                    "feedback.mergeText.helpPassportIssue"));

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
