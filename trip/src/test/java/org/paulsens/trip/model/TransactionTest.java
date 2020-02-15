package org.paulsens.trip.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.testng.annotations.Test;

public class TransactionTest {

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Transaction.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }
}
