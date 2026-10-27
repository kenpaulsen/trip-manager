package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import lombok.Value;
import org.paulsens.trip.model.WaiverAcceptance;

/**
 * One document a trip requires, seen from one traveler: the pinned text to show, and where that traveler stands
 * against it. Built per request by {@link WaiverCommands#requirements}; never stored. The text is the PINNED
 * version's, never the org's newest -- what a traveler reads is what the trip administrator chose.
 */
@Value
public class WaiverRequirement implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    String waiverId;
    String title;
    int version;
    boolean acceptOlder;
    String bodyHtml;
    String sha256;
    WaiverCommands.Standing standing;
    /** The acceptance that decided the standing (the pinned version's, else the newest other), or null. */
    WaiverAcceptance acceptance;

    /** Whether this requirement lets the traveler register. */
    public boolean isSatisfied() {
        return standing.isSatisfied();
    }

    /** The standing's name, for pages that compare strings. */
    public String getStandingKey() {
        return standing.name();
    }
}
