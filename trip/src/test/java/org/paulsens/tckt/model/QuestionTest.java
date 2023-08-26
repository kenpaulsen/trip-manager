package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

public class QuestionTest {

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Question.class).verify();
    }
}