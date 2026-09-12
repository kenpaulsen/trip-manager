package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AddressTest {
    private static final String STREET = "2305 NE 16th St.";
    private static final String CITY = "Brush Prairie";
    private static final String STATE = "WA";
    private static final String ZIP = "98606";

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Address.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canCreateAddress() {
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        Assert.assertEquals(addr.getZip(), ZIP, "Zip doesn't match!");
        Assert.assertEquals(addr.getCity(), CITY, "City doesn't match!");
        Assert.assertEquals(addr.getState(), STATE, "State doesn't match!");
        Assert.assertEquals(addr.getStreet(), STREET, "Street doesn't match!");
    }

    @Test
    public void twoAddressesAreTheSame() {
        final Address addr1 = getTestAddress(STREET, CITY, STATE, ZIP);
        final Address addr2 = getTestAddress(STREET, CITY, STATE, ZIP);
        Assert.assertEquals(addr1, addr2, "These should match!");
    }

    @Test
    public void canChangeStreet() {
        final String newStreet = "21 Fireweed Ln.";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        addr.setStreet(newStreet);
        Assert.assertEquals(addr.getStreet(), newStreet);
        Assert.assertEquals(addr.getCity(), addr.getCity());
        Assert.assertEquals(addr.getState(), addr.getState());
        Assert.assertEquals(addr.getZip(), addr.getZip());
    }

    @Test
    public void canChangeZip() {
        final String newZip = "97219";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        addr.setZip(newZip);
        Assert.assertEquals(addr.getStreet(), addr.getStreet());
        Assert.assertEquals(addr.getCity(), addr.getCity());
        Assert.assertEquals(addr.getState(), addr.getState());
        Assert.assertEquals(addr.getZip(), newZip);
    }

    @Test
    public void canChangeCity() {
        final String newCity = "Seattle";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        addr.setCity(newCity);
        Assert.assertEquals(addr.getStreet(), addr.getStreet());
        Assert.assertEquals(addr.getCity(), newCity);
        Assert.assertEquals(addr.getState(), addr.getState());
        Assert.assertEquals(addr.getZip(), addr.getZip());
    }

    @Test
    public void canChangeState() {
        final String newState = "Oregon";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        addr.setState(newState);
        Assert.assertEquals(addr.getStreet(), addr.getStreet());
        Assert.assertEquals(addr.getCity(), addr.getCity());
        Assert.assertEquals(addr.getState(), newState);
        Assert.assertEquals(addr.getZip(), addr.getZip());
    }

    @Test
    public void street2AndCountryAreSetterPopulatedAndSurviveJson() throws IOException {
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        Assert.assertNull(addr.getStreet2(), "The four-arg creator leaves the newer fields null");
        Assert.assertNull(addr.getCountry());
        addr.setStreet2("Suite 4");
        addr.setCountry("Bosnia and Herzegovina");
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Address restored = mapper.readValue(mapper.writeValueAsString(addr), Address.class);
        Assert.assertEquals(restored.getStreet2(), "Suite 4");
        Assert.assertEquals(restored.getCountry(), "Bosnia and Herzegovina");
        Assert.assertEquals(restored, addr);
    }

    @Test
    public void oldJsonWithoutTheNewFieldsStillReads() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Address restored = mapper.readValue(
                "{\"street\":\"1 Main\",\"city\":\"Rome\",\"state\":null,\"zip\":\"00100\"}", Address.class);
        Assert.assertEquals(restored.getStreet(), "1 Main");
        Assert.assertNull(restored.getCountry());
    }

    @Test
    public void canSerializeAddress() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Address orig = getTestAddress(STREET, CITY, STATE, ZIP);
        final String json = mapper.writeValueAsString(orig);
        final Address restored = mapper.readValue(json, Address.class);
        Assert.assertEquals(restored, orig);
    }

    private Address getTestAddress(final String street, final String city, final String state, final String zip) {
        return new Address(street, city, state, zip);
    }

    /** The one-line form groups street, town and country and drops whatever is blank. */
    @Test
    public void oneLineDropsBlanksAndKeepsTheGroups() {
        final Address full = new Address();
        full.setStreet("Podbrdo 25");
        full.setCity("Medjugorje");
        full.setZip("88266");
        full.setCountry("Bosnia and Herzegovina");
        Assert.assertEquals(full.oneLine(), "Podbrdo 25, Medjugorje 88266, Bosnia and Herzegovina");
        full.setStreet2(" Apt 3 ");
        full.setState("");
        Assert.assertEquals(full.oneLine(), "Podbrdo 25 Apt 3, Medjugorje 88266, Bosnia and Herzegovina");
        Assert.assertEquals(new Address().oneLine(), "", "nothing known, nothing printed");
        final Address townOnly = new Address();
        townOnly.setCity("Split");
        Assert.assertEquals(townOnly.oneLine(), "Split");
    }
}
