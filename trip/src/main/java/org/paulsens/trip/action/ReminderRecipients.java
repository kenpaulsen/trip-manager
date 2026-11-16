package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.site.SiteUrls;
import org.paulsens.trip.util.EmailAddresses;

/**
 * Who a trip reminder goes to, and what its tokens say for each of them. ONE implementation, asked by the page
 * ("who would get this today?"), by the preview, and by the send itself, so what a facilitator approved after
 * looking at the list is decided by the same code that mails it. See {@code docs/reminders.md}.
 *
 * <p>The audience is evaluated on the day it is asked, never frozen: someone who paid yesterday is not reminded
 * today. Every read here is fresh ({@code Cached.NO}): the answer decides whether a person is mailed about money
 * or a missing document, and a stale one sends a wrong message.
 */
public final class ReminderRecipients {
    /** A passport must outlast the trip's end by this long, the rule most countries apply at the border. */
    static final int PASSPORT_MONTHS_AFTER_RETURN = 6;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    /**
     * What ONE viewer of the Communications page may see about travelers, by the rules the rest of the site
     * already keeps (owner decision 2026-11-15: redact only the values a viewer may not see, keep the rest):
     * amounts owed behind the trip's money rule ({@code TripFinanceGate.canView}); passport facts and email
     * addresses behind {@code AccessLevel}'s trip-admin rule (a site admin or the trip's {@code tripMgr}).
     * Managing a trip's messages does not by itself grant any of the three.
     */
    public record Sight(boolean money, boolean travelDocuments, boolean contact) {
        /** Everything: what the send itself works with. */
        public static final Sight ALL = new Sight(true, true, true);
        /** Nothing sensitive. */
        public static final Sight NONE = new Sight(false, false, false);
    }

    /** What a target's {@code reason} sentence gives away, so the redaction never has to parse the sentence. */
    public enum Discloses {
        NOTHING, AMOUNT, PASSPORT
    }

    static final String AMOUNT_HIDDEN = "(amount not shown)";
    static final String PASSPORT_HIDDEN = "(passport details not shown)";
    static final String ADDRESS_HIDDEN = "(address not shown)";

    /**
     * One traveler the reminder is about: why they are in the audience (shown on the page), the addresses the
     * mail goes to (their own, else their family managers'), and the token values of their message.
     */
    public record Target(Person traveler, String reason, Discloses discloses, List<Person> mailTo,
            Map<String, String> facts) {
        public String getName() {
            return CheckinCommands.displayName(traveler);
        }

        public String getPersonId() {
            return traveler.getId().getValue();
        }

        public String getReason() {
            return reason;
        }

        /** The addresses, for the page: "ann@example.com", or "(no email address)". */
        public String getAddresses() {
            if (mailTo.isEmpty()) {
                return "(no email address)";
            }
            final List<String> out = new ArrayList<>();
            for (final Person to : mailTo) {
                out.add(to.getEmail() + (to.getId().equals(traveler.getId()) ? "" : " (family manager)"));
            }
            return String.join(", ", out);
        }

        /**
         * This target as one viewer may see it: the same traveler and wording, with each value they may not see
         * replaced by a placeholder. The words stay ("Owes a balance", "Passport needs attention"): that someone
         * is in the audience is what the page is for, the figure behind it is not this viewer's to read. Only for
         * showing; the send always uses the real target.
         */
        public Target seenBy(final Sight sight) {
            final Map<String, String> shown = new LinkedHashMap<>(facts);
            String why = reason;
            if (!sight.money()) {
                shown.put(ReminderText.BALANCE_DUE, AMOUNT_HIDDEN);
                why = (discloses == Discloses.AMOUNT) ? "Owes a balance" : why;
            }
            if (!sight.travelDocuments()) {
                final boolean issue = !facts.getOrDefault(ReminderText.PASSPORT_ISSUE, "").isEmpty();
                shown.put(ReminderText.PASSPORT_ISSUE, issue ? PASSPORT_HIDDEN : "");
                why = (discloses == Discloses.PASSPORT) ? "Passport needs attention" : why;
            }
            // The mark stays when the reason was left as it is: it still discloses, and a later viewer with less
            // sight, applied to this copy, must still have it replaced.
            final boolean kept = (discloses == Discloses.AMOUNT && sight.money())
                    || (discloses == Discloses.PASSPORT && sight.travelDocuments());
            return new Target(traveler, why, kept ? discloses : Discloses.NOTHING, mailTo, shown);
        }

        /** {@link #getAddresses} as this viewer may see it: how many addresses, not which. */
        public String addressesSeenBy(final Sight sight) {
            return (sight.contact() || mailTo.isEmpty()) ? getAddresses() : ADDRESS_HIDDEN;
        }
    }

    private final BiFunction<String, Person.Id, Long> dueCents;
    private final BiFunction<Trip, Person.Id, List<String>> unsignedTitles;
    private final Function<Person, List<Person>> managers;
    private final Function<Trip, String> baseUrl;

    /** The live wiring: the trip ledger, the trip's documents, the family rows and the trip's own site. */
    public ReminderRecipients() {
        this(ReminderRecipients::ledgerDue, ReminderRecipients::pendingTitles,
                person -> new PersonCommands().mailableManagers(person), ReminderRecipients::siteOf);
    }

    /** Test seam: each outside fact as a function. */
    ReminderRecipients(final BiFunction<String, Person.Id, Long> dueCents,
            final BiFunction<Trip, Person.Id, List<String>> unsignedTitles,
            final Function<Person, List<Person>> managers, final Function<Trip, String> baseUrl) {
        this.dueCents = dueCents;
        this.unsignedTitles = unsignedTitles;
        this.managers = managers;
        this.baseUrl = baseUrl;
    }

    private static Long ledgerDue(final String tripId, final Person.Id personId) {
        return CheckinCommands.balanceOf(tripId, personId).dueCents();
    }

    private static List<String> pendingTitles(final Trip trip, final Person.Id personId) {
        final WaiverCommands waivers = new WaiverCommands(() -> Caller.forActor(AuditActor.system()));
        return waivers.pendingFor(trip, personId).stream().map(WaiverRequirement::getTitle).toList();
    }

    private static String siteOf(final Trip trip) {
        return SiteUrls.baseUrlForTrip(trip, KnownSettings.REG_MAIL_BASE_URL, new ConfigCommands());
    }

    /** The travelers this reminder is about on {@code today}, by name; empty for an unknown trip or reminder. */
    public List<Target> targets(final Trip trip, final TripReminder reminder, final LocalDate today) {
        if (trip == null || reminder == null) {
            return List.of();
        }
        final Registration.Status wanted = (reminder.getAudience() == TripReminder.Audience.PENDING)
                ? Registration.Status.PENDING : Registration.Status.CONFIRMED;
        final Map<String, String> tripFacts = tripFacts(trip, today);
        final List<Target> out = new ArrayList<>();
        for (final Registration reg : DAO.getInstance().getRegistrations(trip.getId(), Cached.NO)) {
            final Person traveler = (reg.getStatus() != wanted) ? null
                    : DAO.getInstance().getPerson(reg.getUserId(), Cached.NO).orElse(null);
            final Target target = (traveler == null) ? null : targetFor(trip, reminder, traveler, tripFacts);
            if (target != null) {
                out.add(target);
            }
        }
        out.sort(Comparator.comparing(Target::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** This traveler as a target, or null when the reminder's condition does not hold for them. */
    private Target targetFor(final Trip trip, final TripReminder reminder, final Person traveler,
            final Map<String, String> tripFacts) {
        final Map<String, String> facts = new LinkedHashMap<>(tripFacts);
        final long due = dueCents.apply(trip.getId(), traveler.getId());
        final List<String> unsigned = unsignedTitles.apply(trip, traveler.getId());
        final String passport = passportIssue(traveler.getPassport(), trip);
        facts.put(ReminderText.TRAVELER_NAME, CheckinCommands.displayName(traveler));
        facts.put(ReminderText.BALANCE_DUE, MoneyMath.formatCents(due));
        facts.put(ReminderText.DOCUMENT_LIST, String.join(", ", unsigned));
        facts.put(ReminderText.PASSPORT_ISSUE, (passport == null) ? "" : passport);
        facts.put(ReminderText.PROFILE_LINK,
                baseUrl.apply(trip) + "/account/person.jsf?id=" + traveler.getId().getValue());
        final TripReminder.Audience audience = reminder.getAudience();
        final String reason = switch (audience) {
            case WITH_BALANCE -> (due > 0) ? "Owes " + MoneyMath.formatCents(due) : null;
            case UNSIGNED_DOCUMENTS -> unsigned.isEmpty() ? null : "Not signed: " + String.join(", ", unsigned);
            case PASSPORT_ISSUE -> passport;
            case PENDING -> "Awaiting approval";
            default -> "Confirmed";
        };
        final Discloses discloses = (audience == TripReminder.Audience.WITH_BALANCE) ? Discloses.AMOUNT
                : (audience == TripReminder.Audience.PASSPORT_ISSUE) ? Discloses.PASSPORT : Discloses.NOTHING;
        return (reason == null) ? null : new Target(traveler, reason, discloses, mailTo(traveler), facts);
    }

    /** The traveler's own address when it is usable; else their family managers, who answer for them. */
    private List<Person> mailTo(final Person traveler) {
        return EmailAddresses.isValid(traveler.getEmail()) ? List.of(traveler) : managers.apply(traveler);
    }

    /**
     * What is wrong with a passport for this trip, in a sentence for the traveler; null when nothing is. A trip
     * with no end date can only be checked for a passport that is missing.
     */
    static String passportIssue(final Passport passport, final Trip trip) {
        if (passport == null || passport.getNumber() == null || passport.getNumber().isBlank()) {
            return "We do not have a passport on file for you.";
        }
        if (passport.getExpires() == null) {
            return "Your passport on file has no expiration date.";
        }
        if (trip.getEndDate() == null) {
            return null;
        }
        final LocalDate needed = trip.getEndDate().toLocalDate().plusMonths(PASSPORT_MONTHS_AFTER_RETURN);
        return passport.getExpires().isBefore(needed)
                ? "Your passport expires " + DAY.format(passport.getExpires()) + ", less than "
                        + PASSPORT_MONTHS_AFTER_RETURN + " months after the trip ends."
                : null;
    }

    /** The token values every message of this trip shares. */
    private Map<String, String> tripFacts(final Trip trip, final LocalDate today) {
        final Map<String, String> facts = new LinkedHashMap<>();
        final String base = baseUrl.apply(trip);
        final String id = trip.getId();
        final LocalDate start = (trip.getStartDate() == null) ? null : trip.getStartDate().toLocalDate();
        final LocalDate end = (trip.getEndDate() == null) ? null : trip.getEndDate().toLocalDate();
        final LocalDate balanceDue = trip.getCheckout().getBalanceDueOn();
        facts.put(ReminderText.TRIP_TITLE, trip.getTitle());
        facts.put(ReminderText.TRIP_DATES, dates(start, end));
        facts.put(ReminderText.DAYS_UNTIL, (start == null || today == null) ? ""
                : Long.toString(Math.max(0, ChronoUnit.DAYS.between(today, start))));
        facts.put(ReminderText.BALANCE_DUE_ON, (balanceDue == null) ? "" : DAY.format(balanceDue));
        facts.put(ReminderText.ORG_NAME, orgName(trip));
        facts.put(ReminderText.TRIP_LINK, base + TripCommands.DETAIL_URL + id);
        facts.put(ReminderText.PAYMENT_LINK, base + "/trip/pay.jsf?trip=" + id);
        facts.put(ReminderText.DOCUMENTS_LINK, base + "/trip/joinTrip.jsf?trip=" + id);
        facts.put(ReminderText.ITINERARY_LINK, base + "/trip/itinerary.jsf?trip=" + id);
        return facts;
    }

    private static String dates(final LocalDate start, final LocalDate end) {
        if (start == null) {
            return "";
        }
        return (end == null || end.equals(start)) ? DAY.format(start) : DAY.format(start) + " to " + DAY.format(end);
    }

    private static String orgName(final Trip trip) {
        if (trip.getOrgId() == null || trip.getOrgId().isBlank()) {
            return "";
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES)
                .map(Organization::getName).orElse("");
    }

    /** The values for ONE addressee of a target: the target's facts plus who is being greeted. */
    public static Map<String, String> valuesFor(final Target target, final Person addressee) {
        final Map<String, String> values = new LinkedHashMap<>(target.facts());
        values.put(ReminderText.FIRST_NAME, (addressee.getPreferredName() == null) ? "" : addressee.getPreferredName());
        return values;
    }

}
