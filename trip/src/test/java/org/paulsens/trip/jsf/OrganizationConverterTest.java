package org.paulsens.trip.jsf;

import java.io.IOException;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class OrganizationConverterTest {
    private final OrganizationConverter converter = new OrganizationConverter();

    @Test
    public void roundTripsAStoredOrganization() throws IOException {
        final Organization org = Organization.builder().name("Converter Org").build();
        assertTrue(DAO.getInstance().saveOrganization(org));

        assertEquals(converter.getAsObject(null, null, org.getId().getValue()), org);
        assertEquals(converter.getAsString(null, null, org), org.getId().getValue());
        assertEquals(converter.getAsString(null, null, org.getId()), org.getId().getValue());
        assertEquals(converter.getAsString(null, null, "raw-id"), "raw-id");
    }

    @Test
    public void missesAndBlanksAnswerNull() {
        assertNull(converter.getAsObject(null, null, null));
        assertNull(converter.getAsObject(null, null, "  "));
        assertNull(converter.getAsObject(null, null, "no-such-org"));
        assertNull(converter.getAsString(null, null, null));
        assertNull(converter.getAsString(null, null, 42));
    }
}
