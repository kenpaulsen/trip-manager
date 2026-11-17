package org.paulsens.trip.action;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * The values of the mail merge's fields ({@link MergeText}) for each recipient of ONE merge. Built after
 * {@link MailMerge#problem} has accepted the text, so every field used is one its sender may use; this
 * class only decides whose values exist.
 *
 * <p>A recipient who is not on the chosen trip gets the trip's own facts but blank values for everything about
 * them ON the trip (room, balance, documents, passport, profile link). Address fields are filled for people on
 * the trip, and for anyone when a site admin sends: a trip manager may use them only about their own trip's
 * people, because the sender can BCC themselves every recipient's copy.
 */
final class MergeValues {
    private final Trip trip;
    private final boolean contactForAnyone;
    private final boolean travelerFacts;
    private final boolean room;
    private final Map<String, String> tripFacts;
    private final ReminderRecipients recipients;
    private final BiFunction<String, Person.Id, String> roomOf;

    /**
     * @param trip              the chosen trip, or null when none was chosen
     * @param contactForAnyone  fill address fields for recipients off the trip too (a site admin's merge)
     * @param used              the fields the subject and body use ({@link MergeText#fieldsUsed})
     * @param today             what "days until departure" counts from
     * @param recipients        the reminders' fact builder (balance, documents, passport, links)
     * @param roomOf            a person's room label on a trip ("" for none)
     */
    MergeValues(final Trip trip, final boolean contactForAnyone, final Set<String> used, final LocalDate today,
            final ReminderRecipients recipients, final BiFunction<String, Person.Id, String> roomOf) {
        this.trip = trip;
        this.contactForAnyone = contactForAnyone;
        this.recipients = recipients;
        this.roomOf = roomOf;
        // Balance, documents and passport are fresh reads per person: paid for only when the text asks for one
        // of the fields that read fills (ReminderRecipients says which).
        this.travelerFacts = used.stream().anyMatch(ReminderRecipients.TRAVELER_FACTS::contains);
        this.room = used.contains(MergeText.ROOM);
        this.tripFacts = (trip == null) ? Map.of() : recipients.tripFacts(trip, today);
    }

    /**
     * Whether a recipient off the chosen trip gets any of these kinds blank: everything about a person ON the
     * trip, and address fields unless a site admin sends. The rule {@link #valuesFor} applies, for the warning
     * that counts such recipients. No default: a new Kind must say here whether it fills off the trip.
     */
    boolean blanksOffTrip(final Set<MergeText.Kind> kinds) {
        for (final MergeText.Kind kind : kinds) {
            final boolean blank = switch (kind) {
                case PERSON, TRIP -> false;
                case CONTACT -> !contactForAnyone;
                case ON_TRIP, MONEY, TRAVEL_DOCUMENTS -> true;
            };
            if (blank) {
                return true;
            }
        }
        return false;
    }

    /** Whether this person is on the chosen trip's roster; false when no trip was chosen. */
    boolean onTrip(final Person person) {
        return trip != null && person.getId() != null && trip.getPeople().contains(person.getId());
    }

    /** Every field's value for this recipient; a field with nothing to say is absent and renders empty. */
    Map<String, String> valuesFor(final Person person) {
        final Map<String, String> values = new LinkedHashMap<>(tripFacts);
        putPerson(values, person);
        final boolean onTrip = onTrip(person);
        if (contactForAnyone || onTrip) {
            putContact(values, person);
        }
        if (onTrip) {
            if (travelerFacts) {
                values.putAll(recipients.travelerFacts(trip, person));
            }
            if (room) {
                values.put(MergeText.ROOM, roomOf.apply(trip.getId(), person.getId()));
            }
        }
        return values;
    }

    private static void putPerson(final Map<String, String> values, final Person person) {
        values.put(MergeText.FIRST_NAME, person.getPreferredName());
        values.put(MergeText.LEGAL_FIRST_NAME, person.getFirst());
        values.put(MergeText.MIDDLE_NAME, person.getMiddle());
        values.put(MergeText.LAST_NAME, person.getLast());
        values.put(MergeText.FULL_NAME, CheckinCommands.displayName(person));
        values.put(MergeText.EMAIL, person.getEmail());
    }

    private static void putContact(final Map<String, String> values, final Person person) {
        values.put(MergeText.CELL, person.getCell());
        final Address address = person.getAddress();
        if (address != null) {
            values.put(MergeText.STREET, address.getStreet());
            values.put(MergeText.STREET2, address.getStreet2());
            values.put(MergeText.CITY, address.getCity());
            values.put(MergeText.STATE, address.getState());
            values.put(MergeText.ZIP, address.getZip());
            values.put(MergeText.COUNTRY, address.getCountry());
        }
    }
}
