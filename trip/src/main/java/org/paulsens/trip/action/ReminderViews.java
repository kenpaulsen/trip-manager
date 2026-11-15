package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;

/**
 * The scalar rows and the one form the Communications page renders ({@code docs/reminders.md}). Rows are built
 * per request and never stored; the form lives in viewScope, so it holds strings and scalars only.
 */
public final class ReminderViews {

    private ReminderViews() {
    }

    /** One scheduled message on the page's Schedule list. */
    @Value
    @Builder
    public static class Row {
        String id;
        String name;
        String kindLabel;
        String scheduleText;
        /** "Wed, Mar 3, 2027", or "No date" when the trip lacks the date the schedule counts from. */
        String sendDateText;
        String audienceLabel;
        /** "Email" or "Email and app notification". */
        String channel;
        String statusLabel;
        /** A stylesheet suffix: draft, approved, changed, sent, missed, failed, nodate. */
        String statusClass;
        /** One line under the status: who approved it and when, or what a facilitator should do next. */
        String statusNote;
        boolean approved;
        boolean approvable;
        boolean sendable;
        /** "Send now", or "Send to anyone missed" once a run for the date has happened. */
        String sendLabel;
    }

    /**
     * Everything the review dialog shows for one message, resolved in ONE pass so its parts cannot disagree: the
     * message as one recipient reads it, who it would go to today, and {@link #hash}, the identity of exactly
     * this wording, audience and date. Approve hands the hash back, so what is approved is what was shown.
     */
    @Value
    @Builder
    public static class Review {
        Row row;
        String subject;
        String body;
        String push;
        String note;
        String from;
        /** Where replies go, in words: an address, or "the From address". */
        String replyTo;
        List<Recipient> recipients;
        String hash;
    }

    /** The Reports dashboard card's two lines, read from the plan once. */
    @Value
    public static class Card {
        String headline;
        String subline;
    }

    /** One option of a menu in the message dialog. */
    @Value
    public static class Choice {
        String id;
        String label;
    }

    /** One traveler a message would go to today, for the review dialog's list. */
    @Value
    public static class Recipient {
        String name;
        /** Why they are in the audience: "Owes $250.00", "Not signed: Release". */
        String reason;
        /** Where the mail goes: their own address, a family manager's, or "(no email address)". */
        String addresses;
    }

    /** One line of the Sent history. */
    @Value
    public static class HistoryRow {
        /** The ledger sort key: the row's identity for "Allow resend". */
        String key;
        String when;
        String reminderName;
        String personName;
        String toEmail;
        String outcomeLabel;
        /** A stylesheet suffix: sent, failed, sending, noaddress, nobody. */
        String outcomeClass;
        /** "Scheduled" or "Sent by hand". */
        String how;
        boolean pushed;
        /** True for a failed or stuck send: the facilitator may clear it so the traveler can be sent to again. */
        boolean clearable;
    }

    /** One transactional email the trip already sends by itself, listed read-only. */
    @Value
    public static class AutoMail {
        String templateId;
        String name;
        /** When it is sent, in the facilitator's words. */
        String trigger;
        /** "Your organization's wording" or "Standard wording". */
        String wording;
        String subject;
        String body;
    }

    /** The message dialog: one reminder being added or edited. */
    @Data
    @NoArgsConstructor
    public static final class Form implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        private String kind = "CUSTOM";
        private String name;
        private String anchor = "TRIP_START";
        /** BEFORE, ON or AFTER the anchor; ignored for a fixed date. */
        private String timing = "BEFORE";
        private int days = 7;
        private LocalDate fixedDate;
        private String audience = "ALL_CONFIRMED";
        private String subject;
        private String body;
        private String pushText;
        /** Why the last save was refused, shown inside the dialog. */
        private String problem;

        /** Whether the audience is the facilitator's choice (a custom message) or fixed by the kind. */
        public boolean isCustom() {
            return "CUSTOM".equals(kind);
        }
    }
}
