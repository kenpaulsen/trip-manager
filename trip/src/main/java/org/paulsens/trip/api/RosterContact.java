package org.paulsens.trip.api;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.action.PrivacyView;
import org.paulsens.trip.api.dto.TripRosterDto.RosterContactDto;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Person;

/**
 * The contact block of one roster row: the web contacts page's {@link PrivacyView} rule turned into wire fields,
 * so the app and the page can never disagree about whose cell a traveler may see.
 */
final class RosterContact {

    static final String EMAIL = "email";
    static final String CELL = "cell";
    static final String STREET = "street";
    static final String CITY = "city";
    static final String STATE = "state";
    static final String ZIP = "zip";

    private RosterContact() {
    }

    /**
     * What {@code viewer} may see of how to reach {@code subject}, or null when that is nothing at all -- the app
     * then offers no contact details for them. City governs state and street governs zip, as on the page.
     */
    static RosterContactDto of(final Person viewer, final Person subject, final boolean adminView) {
        final PrivacyView pv = PrivacyView.of(viewer, subject, adminView);
        final Address address = subject.getAddress();
        final List<String> masked = new ArrayList<>();
        final String email = shown(pv.getEmail(), subject.getEmail(), EMAIL, masked);
        final String cell = shown(pv.getCell(), subject.getCell(), CELL, masked);
        final String street = shown(pv.getStreet(), address == null ? null : address.getStreet(), STREET, masked);
        final String city = shown(pv.getCity(), address == null ? null : address.getCity(), CITY, masked);
        final String state = shown(pv.getCity(), address == null ? null : address.getState(), STATE, masked);
        final String zip = shown(pv.getStreet(), address == null ? null : address.getZip(), ZIP, masked);
        if (email == null && cell == null && street == null && city == null && state == null && zip == null) {
            return null;
        }
        return new RosterContactDto(email, cell, street, city, state, zip, masked.isEmpty() ? null : masked);
    }

    private static String shown(final PrivacyView.State state, final String value, final String field,
            final List<String> masked) {
        if (state == PrivacyView.State.HIDE || value == null || value.isBlank()) {
            return null;
        }
        if (state == PrivacyView.State.MASK) {
            masked.add(field);
        }
        return value;
    }
}
