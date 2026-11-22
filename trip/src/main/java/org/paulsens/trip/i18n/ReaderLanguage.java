package org.paulsens.trip.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * The language something SENT to a person is written in: mail, push, a reminder ({@code docs/i18n.md}, "Who
 * decides the language"). Never the request's language: the reader is not the one who triggered the send, and
 * most sends run on a background thread with no request at all.
 *
 * <p>In order: the person's own saved choice, the language of the trip the message is about, the default of
 * the organization that sends it. A language counts only when that organization's site OFFERS it, which is what
 * keeps an unreviewed translation from going out in mail before it has been switched on anywhere.
 */
public final class ReaderLanguage {

    private ReaderLanguage() {
    }

    /** The language for {@code person} about {@code trip}; either may be null (a site-level message). */
    public static Locale of(final Person person, final Trip trip) {
        final List<Language> offered = offeredBy(trip == null ? null : trip.getOrganization());
        if (offered.size() == 1) {
            return offered.get(0).getLocale();
        }
        final List<String> wishes = new ArrayList<>();
        wishes.add(person == null ? null : person.getLanguage());
        wishes.add(trip == null || trip.getLanguage() == null ? null : trip.getLanguage().getTag());
        return LanguageChooser.choose(offered, wishes).getLocale();
    }

    /**
     * {@link #of(Person, Trip)} for a recipient known by id. The person is read only when there is a choice to
     * make, so a site that offers one language pays nothing per recipient.
     */
    public static Locale of(final Person.Id personId, final Trip trip) {
        final List<Language> offered = offeredBy(trip == null ? null : trip.getOrganization());
        if (offered.size() == 1 || personId == null) {
            return of((Person) null, trip);
        }
        return of(DAO.getInstance().getPerson(personId, Cached.YES).orElse(null), trip);
    }

    /**
     * The language for someone with no account yet (an invitation to an organization): nobody's choice to honor,
     * so the language the organization's site lists first.
     */
    public static Locale ofInvitee(final Organization org) {
        return offeredBy(org).get(0).getLocale();
    }

    /**
     * The language for STAFF (an office notice, a report or request sent to an organization's admins): their
     * own saved choice when the sending organization's site offers it, else English. Unlike {@link #of} the
     * trip's language never decides: the office did not choose it, and an office that reads English must not
     * get its notices in whatever language one of its trips is hosted in.
     *
     * @param org the organization whose site sends the notice; null for the shared site
     */
    public static Locale ofStaff(final Person staff, final Organization org) {
        return Language.forTag(staff == null ? null : staff.getLanguage())
                .filter(offeredBy(org)::contains)
                .map(Language::getLocale)
                .orElse(Locale.ENGLISH);
    }

    /**
     * {@link #ofStaff} for a configured notification address: the account that address belongs to. A list of
     * addresses, or one no account holds (a shared office mailbox), is read in English.
     */
    public static Locale ofStaffAddress(final String address, final Organization org) {
        if (address == null || address.isBlank() || address.indexOf(',') >= 0) {
            return Locale.ENGLISH;
        }
        final String email = address.trim().toLowerCase(Locale.ROOT);
        return ofStaff(DAO.getInstance().getPersonByEmail(email, Cached.YES), org);
    }

    /** {@link #ofStaffAddress(String, Organization)} for a notice about a trip: its organization sends it. */
    public static Locale ofStaffAddress(final String address, final Trip trip) {
        return ofStaffAddress(address, trip == null ? null : trip.getOrganization());
    }

    /** The languages the sending organization's site offers; the shared site's for a message with no trip. */
    private static List<Language> offeredBy(final Organization org) {
        return new ConfigCommands().siteLanguages(org);
    }
}
