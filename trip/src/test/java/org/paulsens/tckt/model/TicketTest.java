package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

public class TicketTest {

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Ticket.class).verify();
    }
}