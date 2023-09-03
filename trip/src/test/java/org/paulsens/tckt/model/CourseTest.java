package org.paulsens.tckt.model;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.annotations.Test;

public class CourseTest {

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Course.class)
                .withOnlyTheseFields("id")
                .verify();
    }
}