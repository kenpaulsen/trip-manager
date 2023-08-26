package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

public class BindingTypeTest {
    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(BindingType.class).verify();
    }
}