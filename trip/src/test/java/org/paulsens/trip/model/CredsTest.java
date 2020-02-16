package org.paulsens.trip.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.testng.annotations.Test;

public class CredsTest {

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Creds.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }
}
