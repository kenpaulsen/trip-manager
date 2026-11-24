package org.paulsens.trip.action;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.i18n.Text;

/**
 * The wording side of a trip reminder: the {@code {{token}}} vocabulary a facilitator may use, the check that a
 * message uses nothing else, and the substitution ({@link TokenText}, shared with the mail merge). Java-only
 * substitution, like the managed MAIL templates ({@code MailCommands.renderManagedTemplateForOrg}): no EL, so a
 * message typed by a trip facilitator can never evaluate anything, and it renders on the scheduler's thread with
 * no Faces context.
 *
 * <p>Every value is HTML-escaped in a body, link tokens included. A token that is unknown is
 * REFUSED when the message is saved ({@link #unknownTokens}); nothing approved can go out with a raw
 * {@code {{typo}}} in it.
 */
public final class ReminderText {
    /**
     * One token a message may contain, with the help the editor shows beside it, held as its message key so the
     * page reads it in the reader's language (it was an English sentence here until 2026-11). The mail merge's
     * {@link MergeText.Field} is one of these with a kind. A class, not a record: a page's EL reads a record's
     * components only, so a computed property such as {@link #getMarkup} would not resolve there.
     */
    public static class Token {
        private final String name;
        private final String helpKey;

        Token(final String name, final String helpKey) {
            this.name = name;
            this.helpKey = helpKey;
        }

        public String getName() {
            return name;
        }

        /** The help in the language of the request in progress. */
        public String getHelp() {
            return Text.get(helpKey);
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

    /**
     * The whole vocabulary, in the order the editor lists it. Help that says the same thing as the mail merge's
     * reads the merge's key (one key per meaning, docs/i18n.md); the rest is worded for a scheduled message.
     */
    public static final List<Token> TOKENS = List.of(
            new Token(FIRST_NAME, "feedback.reminderText.helpFirstName"),
            new Token(TRAVELER_NAME, "feedback.reminderText.helpTravelerName"),
            new Token(TRIP_TITLE, "feedback.mergeText.helpTripTitle"),
            new Token(TRIP_DATES, "feedback.mergeText.helpTripDates"),
            new Token(DAYS_UNTIL, "feedback.reminderText.helpDaysUntil"),
            new Token(BALANCE_DUE, "feedback.reminderText.helpBalanceDue"),
            new Token(BALANCE_DUE_ON, "feedback.mergeText.helpBalanceDueOn"),
            new Token(DOCUMENT_LIST, "feedback.reminderText.helpDocumentList"),
            new Token(PASSPORT_ISSUE, "feedback.reminderText.helpPassportIssue"),
            new Token(ORG_NAME, "feedback.reminderText.helpOrgName"),
            new Token(TRIP_LINK, "feedback.mergeText.helpTripLink"),
            new Token(PAYMENT_LINK, "feedback.mergeText.helpPaymentLink"),
            new Token(DOCUMENTS_LINK, "feedback.reminderText.helpDocumentsLink"),
            new Token(ITINERARY_LINK, "feedback.mergeText.helpItineraryLink"),
            new Token(PROFILE_LINK, "feedback.reminderText.helpProfileLink"));

    private static final Set<String> NAMES = namesOf(TOKENS);

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
        return TokenText.unknown(text, NAMES);
    }

    /**
     * {@code text} as HTML with every known token replaced: the mail BODY. A value is HTML-escaped; a known
     * token with no value becomes empty (a countdown message has no balance), and an unknown token is left as
     * typed, which only a message that skipped {@link #unknownTokens} can contain. Does NOT clean: the caller
     * passes a body already cleaned by {@link RichTextHtml#clean} (the send, once per send); a stored body goes
     * through {@link #renderBody}. Package-private so no page or other package can skip the cleaning.
     */
    static String render(final String text, final Map<String, String> values) {
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
     * A reminder's stored body, ready to be written out as HTML: cleaned again ({@link RichTextHtml#clean}), then
     * merged. Every page and test-mail path writes a stored body out through this, so none can forget the
     * cleaning. The one exception is the send: {@code ReminderSender.sendAll} cleans the body once for the
     * whole send and merges it per traveler with {@link #render}, so the cleaning is not paid per traveler.
     */
    public static String renderBody(final String storedBody, final Map<String, String> values) {
        return render(RichTextHtml.clean(storedBody), values);
    }

    private static String substitute(final String text, final Map<String, String> values, final boolean html) {
        return TokenText.substitute(text, values, NAMES, html);
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
