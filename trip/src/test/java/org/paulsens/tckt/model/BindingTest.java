package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

public class BindingTest {

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Binding.class).verify();
    }
}