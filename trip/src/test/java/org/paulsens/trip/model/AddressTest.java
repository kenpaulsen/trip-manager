package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AddressTest {
    private final static String STREET = "2305 NE 16th St.";
    private final static String CITY = "Brush Prairie";
    private final static String STATE = "WA";
    private final static String ZIP = "98606";

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
}
