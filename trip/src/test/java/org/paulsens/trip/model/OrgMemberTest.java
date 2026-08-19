package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class OrgMemberTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        assertEquals(OrgMember.builder().build(), new OrgMember());
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final OrgMember member = OrgMember.builder()
                .orgId(Organization.Id.newInstance())
                .personId(Person.Id.newInstance())
                .joined(LocalDateTime.of(2026, 8, 17, 9, 30))
                .build();
        assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(member), OrgMember.class), member);
    }
}
