package org.paulsens.trip.action;

/**
 * What the Room Invoice PDF prints above each hotel's section: whose invoice it is. {@code orgName} is the
 * organization running the trip (the party the hotel bills), {@code tripTitle} the trip; either may be blank
 * for a trip that has lost its organization, and the renderer then leaves that part out rather than printing
 * "null".
 */
public record RoomInvoiceHeading(String tripTitle, String orgName) {

    /** The heading with blanks for nulls, so a renderer never prints "null". */
    public RoomInvoiceHeading {
        tripTitle = (tripTitle == null) ? "" : tripTitle.trim();
        orgName = (orgName == null) ? "" : orgName.trim();
    }

    /** "Invoice to Center for Peace for Spring Trip room charges at Pansion." (no "to" without an organization). */
    public String invoiceLine(final String accommodation) {
        final String to = orgName.isEmpty() ? "" : " to " + orgName;
        return "Invoice" + to + " for " + tripTitle + " room charges at " + accommodation + ".";
    }
}
