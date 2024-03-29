package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PassportTest {
    private final static String NUMBER = "2305 NE 16th St.";
    private final static String COUNTRY = "Brush Prairie";
    private final static LocalDate EXPIRES = LocalDate.now();
    private final static LocalDate ISSUED = LocalDate.now();

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Passport.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canCreatePassport() {
        final Passport pass = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        Assert.assertEquals(pass.getNumber(), NUMBER, "Street doesn't match!");
        Assert.assertEquals(pass.getCountry(), COUNTRY, "City doesn't match!");
        Assert.assertEquals(pass.getExpires(), EXPIRES, "State doesn't match!");
        Assert.assertEquals(pass.getIssued(), ISSUED, "Zip doesn't match!");
    }

    @Test
    public void twoPassportesAreTheSame() {
        final Passport pass1 = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        final Passport pass2 = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        Assert.assertEquals(pass1, pass2, "These should match!");
    }

    @Test
    public void canChangeNumber() {
        final String number = "544-11-77733";
        final Passport pass = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        pass.setNumber(number);
        Assert.assertEquals(pass.getNumber(), number);
        Assert.assertEquals(pass.getCountry(), pass.getCountry());
        Assert.assertEquals(pass.getExpires(), pass.getExpires());
        Assert.assertEquals(pass.getIssued(), pass.getIssued());
    }

    @Test
    public void canChangeZip() {
        final LocalDate newIssue = LocalDate.now();
        final Passport pass = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        pass.setIssued(newIssue);
        Assert.assertEquals(pass.getNumber(), pass.getNumber());
        Assert.assertEquals(pass.getCountry(), pass.getCountry());
        Assert.assertEquals(pass.getExpires(), pass.getExpires());
        Assert.assertEquals(pass.getIssued(), newIssue);
    }

    @Test
    public void canChangeCity() {
        final String newCountry = "Seattle";
        final Passport pass = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        pass.setCountry(newCountry);
        Assert.assertEquals(pass.getNumber(), pass.getNumber());
        Assert.assertEquals(pass.getCountry(), newCountry);
        Assert.assertEquals(pass.getExpires(), pass.getExpires());
        Assert.assertEquals(pass.getIssued(), pass.getIssued());
    }

    @Test
    public void canChangeState() {
        final LocalDate newExpires = LocalDate.now();
        final Passport pass = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        pass.setExpires(newExpires);
        Assert.assertEquals(pass.getNumber(), pass.getNumber());
        Assert.assertEquals(pass.getCountry(), pass.getCountry());
        Assert.assertEquals(pass.getExpires(), newExpires);
        Assert.assertEquals(pass.getIssued(), pass.getIssued());
    }

    @Test
    public void canSerializePassport() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();

        final Passport orig = getTestPassport(NUMBER, COUNTRY, EXPIRES, ISSUED);
        final String json = mapper.writeValueAsString(orig);
        final Passport restored = mapper.readValue(json, Passport.class);
        Assert.assertEquals(restored, orig);
    }

    private Passport getTestPassport(
            final String number, final String country, final LocalDate expires, final LocalDate issued) {
        return new Passport(number, country, expires, issued);
    }
}