package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.chat.MailTemplates;

/**
 * The wording side of a trip reminder: the {@code {{token}}} vocabulary a facilitator may use, the check that a
 * message uses nothing else, and the substitution. Java-only substitution, like the managed MAIL templates
 * ({@code MailCommands.renderManagedTemplateForOrg}): no EL, so a message typed by a trip facilitator can never
 * evaluate anything, and it renders on the scheduler's thread with no Faces context.
 *
 * <p>Every value is escaped except the link tokens, which the code builds itself. A token that is unknown is
 * REFUSED when the message is saved ({@link #unknownTokens}); nothing approved can go out with a raw
 * {@code {{typo}}} in it.
 */
public final class ReminderText {
    /**
     * One token a message may contain, with the help text the editor shows beside it. A class, not a record: a
     * page's EL reads a record's components only, so a computed property such as {@link #getMarkup} would not
     * resolve there.
     */
    public static final class Token {
        private final String name;
        private final String help;

        Token(final String name, final String help) {
            this.name = name;
            this.help = help;
        }

        public String getName() {
            return name;
        }

        public String getHelp() {
            return help;
        }

        /** The token as it is typed: {@code {{name}}}. */
        public String getMarkup() {
            return "{{" + name + "}}";
        }
    }

    public static final String FIRST_NAME = "firstName";
    public static final String TRAVELER_NAME = "travelerName";
    public static final String TRIP_TITLE = "tripTitle";
    public static final String TRIP_DATES = "tripDates";
    public static final String DAYS_UNTIL = "daysUntilDeparture";
    public static final String BALANCE_DUE = "balanceDue";
    public static final String BALANCE_DUE_ON = "balanceDueOn";
    public static final String DOCUMENT_LIST = "documentList";
    public static final String PASSPORT_ISSUE = "passportIssue";
    public static final String ORG_NAME = "orgName";
    public static final String TRIP_LINK = "tripLink";
    public static final String PAYMENT_LINK = "paymentLink";
    public static final String DOCUMENTS_LINK = "documentsLink";
    public static final String ITINERARY_LINK = "itineraryLink";
    public static final String PROFILE_LINK = "profileLink";

    /** The whole vocabulary, in the order the editor lists it. */
    public static final List<Token> TOKENS = List.of(
            new Token(FIRST_NAME, "The first name of the person receiving the message."),
            new Token(TRAVELER_NAME, "The traveler the message is about (a family manager may receive it for them)."),
            new Token(TRIP_TITLE, "The trip's title."),
            new Token(TRIP_DATES, "The trip's start and end dates."),
            new Token(DAYS_UNTIL, "Days from the send date until the trip starts."),
            new Token(BALANCE_DUE, "What the traveler still owes on this trip."),
            new Token(BALANCE_DUE_ON, "The trip's balance due date."),
            new Token(DOCUMENT_LIST, "The documents the traveler has not signed."),
            new Token(PASSPORT_ISSUE, "What is wrong with the traveler's passport on file."),
            new Token(ORG_NAME, "Your organization's name."),
            new Token(TRIP_LINK, "Web address of the trip page."),
            new Token(PAYMENT_LINK, "Web address of the trip's payment page."),
            new Token(DOCUMENTS_LINK, "Web address where the traveler signs the trip's documents."),
            new Token(ITINERARY_LINK, "Web address of the trip's itinerary."),
            new Token(PROFILE_LINK, "Web address of the traveler's profile, where a passport is entered."));

    private static final Set<String> NAMES = namesOf(TOKENS);
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*\\}\\}");

    private ReminderText() {
    }

    private static Set<String> namesOf(final List<Token> tokens) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Token token : tokens) {
            names.add(token.getName());
        }
        return names;
    }

    /** The {@code {{...}}} names in {@code text} that are not in the vocabulary, in order, each once. */
    public static List<String> unknownTokens(final String text) {
        final Set<String> unknown = new LinkedHashSet<>();
        if (text != null) {
            final Matcher matcher = TOKEN.matcher(text);
            while (matcher.find()) {
                if (!NAMES.contains(matcher.group(1))) {
                    unknown.add(matcher.group(1));
                }
            }
        }
        return new ArrayList<>(unknown);
    }

    /**
     * {@code text} as HTML with every known token replaced: the mail BODY. A value is HTML-escaped; a known
     * token with no value becomes empty (a countdown message has no balance), and an unknown token is left as
     * typed, which only a message that skipped {@link #unknownTokens} can contain.
     */
    public static String render(final String text, final Map<String, String> values) {
        return substitute(text, values, true);
    }

    /**
     * {@code text} as plain text with every known token replaced: the mail SUBJECT and the push text, neither of
     * which is HTML, so "Smith &amp; Sons" must stay as typed.
     */
    public static String renderPlain(final String text, final Map<String, String> values) {
        return substitute(text, values, false);
    }

    /**
     * A reminder's stored body, ready to be written out as HTML: cleaned again ({@link ReminderHtml#clean}), then
     * merged. THE one way a body leaves the store for a page or a mailbox, so no caller can forget the cleaning.
     */
    public static String renderBody(final String storedBody, final Map<String, String> values) {
        return render(ReminderHtml.clean(storedBody), values);
    }

    private static String substitute(final String text, final Map<String, String> values, final boolean html) {
        if (text == null) {
            return "";
        }
        final Matcher matcher = TOKEN.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String name = matcher.group(1);
            final String raw = valueOf(values, name);
            final String value = !NAMES.contains(name) ? matcher.group() : html ? MailTemplates.escape(raw) : raw;
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String valueOf(final Map<String, String> values, final String name) {
        final String value = (values == null) ? null : values.get(name);
        return (value == null) ? "" : value;
    }

    /** Sample values for a preview that has no real recipient yet, so every token shows something. */
    public static Map<String, String> sampleValues() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(FIRST_NAME, "Maria");
        values.put(TRAVELER_NAME, "Maria Lopez");
        values.put(TRIP_TITLE, "Sample Trip");
        values.put(TRIP_DATES, "Jun 1, 2027 to Jun 10, 2027");
        values.put(DAYS_UNTIL, "14");
        values.put(BALANCE_DUE, "$250.00");
        values.put(BALANCE_DUE_ON, "May 1, 2027");
        values.put(DOCUMENT_LIST, "Liability Release");
        values.put(PASSPORT_ISSUE, "Your passport expires Aug 15, 2027, less than 6 months after the trip ends.");
        values.put(ORG_NAME, "Your Organization");
        values.put(TRIP_LINK, "https://example.com/trip");
        values.put(PAYMENT_LINK, "https://example.com/pay");
        values.put(DOCUMENTS_LINK, "https://example.com/documents");
        values.put(ITINERARY_LINK, "https://example.com/itinerary");
        values.put(PROFILE_LINK, "https://example.com/profile");
        return values;
    }
}
