package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class AnswerTest {

    @Test
    void equalsWorks() {
        EqualsVerifier.forClass(Answer.class).verify();
    }
}