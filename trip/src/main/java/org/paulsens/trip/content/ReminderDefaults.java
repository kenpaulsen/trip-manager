package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.model.TripReminder;

/**
 * The starting text of a trip's suggested reminders ({@code docs/reminders.md}). Each default is also a MAIL
 * starter template ({@link StarterTemplates}), so an organization can reword its own starting text once on the
 * Templates page; a trip's "Add suggested messages" COPIES the org's copy (or this text when the template was
 * never installed) into the trip as a draft. From then on the trip's copy is the only one that matters: a later
 * template edit never changes a message a facilitator already approved.
 *
 * <p>Tokens are {@code ReminderText}'s vocabulary, not the transactional templates'.
 */
public final class ReminderDefaults {
    public static final String BALANCE_DUE_ID = "reminder-balance-due";
    public static final String WAIVER_ID = "reminder-waiver";
    public static final String PASSPORT_ID = "reminder-passport";
    public static final String COUNTDOWN_ID = "reminder-countdown";

    /** One suggested reminder: when it goes, and the starter template its wording comes from. */
    public record Suggestion(TripReminder.Kind kind, String name, TripReminder.Anchor anchor, int offsetDays,
            String templateId) {
    }

    /** One starter text: the subject (a MAIL template's name) and the body. */
    public record Text(String id, String subject, String description, String body) {
    }

    /**
     * What "Add suggested messages" offers, in schedule order. Three balance reminders share one text: two
     * weeks before, three days before, and the day after the due date.
     */
    public static final List<Suggestion> SUGGESTIONS = List.of(
            new Suggestion(TripReminder.Kind.BALANCE_DUE, "Balance due in 2 weeks",
                    TripReminder.Anchor.BALANCE_DUE_DATE, -14, BALANCE_DUE_ID),
            new Suggestion(TripReminder.Kind.BALANCE_DUE, "Balance due in 3 days",
                    TripReminder.Anchor.BALANCE_DUE_DATE, -3, BALANCE_DUE_ID),
            new Suggestion(TripReminder.Kind.BALANCE_DUE, "Balance overdue",
                    TripReminder.Anchor.BALANCE_DUE_DATE, 1, BALANCE_DUE_ID),
            new Suggestion(TripReminder.Kind.WAIVER_UNSIGNED, "Documents to sign",
                    TripReminder.Anchor.TRIP_START, -30, WAIVER_ID),
            new Suggestion(TripReminder.Kind.PASSPORT, "Passport check",
                    TripReminder.Anchor.TRIP_START, -90, PASSPORT_ID),
            new Suggestion(TripReminder.Kind.COUNTDOWN, "One week to go",
                    TripReminder.Anchor.TRIP_START, -7, COUNTDOWN_ID));

    private static final String TOKEN_NOTE = " This is only the STARTING text: each trip copies it into its own "
            + "messages on the trip's Communications page, where a facilitator rewords and approves it. Tokens: "
            + "firstName, travelerName, tripTitle, tripDates, daysUntilDeparture, balanceDue, balanceDueOn, "
            + "documentList, passportIssue, orgName, tripLink, paymentLink, documentsLink, itineraryLink, "
            + "profileLink.";

    public static final List<Text> TEXTS = List.of(
            new Text(BALANCE_DUE_ID, "Your balance for {{tripTitle}}",
                    "Starting text for a trip's balance reminders, sent to confirmed travelers who still owe "
                            + "money." + TOKEN_NOTE,
                    """
                    <p>Hi {{firstName}},</p>
                    <p>This is a reminder about the balance for <b>{{tripTitle}}</b>.</p>
                    <p>Balance for {{travelerName}}: <b>{{balanceDue}}</b><br/>
                    Due: {{balanceDueOn}}</p>
                    <p><a href="{{paymentLink}}">Make a payment</a></p>
                    <p>If you have already paid, thank you, and please reply so we can check our records.</p>
                    """),
            new Text(WAIVER_ID, "Please sign your documents for {{tripTitle}}",
                    "Starting text for a trip's document reminder, sent to confirmed travelers who have a "
                            + "required document left to sign." + TOKEN_NOTE,
                    """
                    <p>Hi {{firstName}},</p>
                    <p><b>{{tripTitle}}</b> needs a signature from {{travelerName}} on: {{documentList}}.</p>
                    <p><a href="{{documentsLink}}">Read and sign</a></p>
                    <p>Questions? Just reply to this email.</p>
                    """),
            new Text(PASSPORT_ID, "Passport check for {{tripTitle}}",
                    "Starting text for a trip's passport reminder, sent to confirmed travelers whose passport is "
                            + "missing from their profile or expires too soon after the trip." + TOKEN_NOTE,
                    """
                    <p>Hi {{firstName}},</p>
                    <p>We are checking passports for <b>{{tripTitle}}</b> ({{tripDates}}).</p>
                    <p>For {{travelerName}}: {{passportIssue}}</p>
                    <p>Many countries require a passport that is valid for 6 months after you return. Please
                    <a href="{{profileLink}}">update the passport on your profile</a> once you have it.</p>
                    """),
            new Text(COUNTDOWN_ID, "{{daysUntilDeparture}} days until {{tripTitle}}",
                    "Starting text for a trip's pre-departure message, sent to everyone confirmed on the trip."
                            + TOKEN_NOTE,
                    """
                    <p>Hi {{firstName}},</p>
                    <p><b>{{tripTitle}}</b> starts in {{daysUntilDeparture}} days ({{tripDates}}).</p>
                    <p><a href="{{itineraryLink}}">See the itinerary</a></p>
                    <p>Questions before we leave? Just reply to this email.</p>
                    """));

    private ReminderDefaults() {
    }

    /** The built-in text of a starter id; null for an id that is not a reminder default. */
    public static Text text(final String id) {
        for (final Text text : TEXTS) {
            if (text.id().equals(id)) {
                return text;
            }
        }
        return null;
    }
}
