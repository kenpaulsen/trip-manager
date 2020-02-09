package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AddressTest {
    private final static String STREET = "2305 NE 16th St.";
    private final static String CITY = "Brush Prairie";
    private final static String STATE = "WA";
    private final static String ZIP = "98606";

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
        final Address addr2 = addr.withStreet(newStreet);
        Assert.assertEquals(addr2.getStreet(), newStreet);
        Assert.assertEquals(addr2.getCity(), addr.getCity());
        Assert.assertEquals(addr2.getState(), addr.getState());
        Assert.assertEquals(addr2.getZip(), addr.getZip());
    }

    @Test
    public void canChangeZip() {
        final String newZip = "97219";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        final Address addr2 = addr.withZip(newZip);
        Assert.assertEquals(addr2.getStreet(), addr.getStreet());
        Assert.assertEquals(addr2.getCity(), addr.getCity());
        Assert.assertEquals(addr2.getState(), addr.getState());
        Assert.assertEquals(addr2.getZip(), newZip);
    }

    @Test
    public void canChangeCity() {
        final String newCity = "Seattle";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        final Address addr2 = addr.withCity(newCity);
        Assert.assertEquals(addr2.getStreet(), addr.getStreet());
        Assert.assertEquals(addr2.getCity(), newCity);
        Assert.assertEquals(addr2.getState(), addr.getState());
        Assert.assertEquals(addr2.getZip(), addr.getZip());
    }

    @Test
    public void canChangeState() {
        final String newState = "Oregon";
        final Address addr = getTestAddress(STREET, CITY, STATE, ZIP);
        final Address addr2 = addr.withState(newState);
        Assert.assertEquals(addr2.getStreet(), addr.getStreet());
        Assert.assertEquals(addr2.getCity(), addr.getCity());
        Assert.assertEquals(addr2.getState(), newState);
        Assert.assertEquals(addr2.getZip(), addr.getZip());
    }

    @Test
    public void canSerializeAddress() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final Address orig = getTestAddress(STREET, CITY, STATE, ZIP);
        final String json = mapper.writeValueAsString(orig);
        final Address restored = mapper.readValue(json, Address.class);
        Assert.assertEquals(restored, orig);
    }

    private Address getTestAddress(final String street, final String city, final String state, final String zip) {
        return new Address(street, city, state, zip);
    }
}
