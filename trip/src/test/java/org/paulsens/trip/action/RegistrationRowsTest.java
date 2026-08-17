package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The scalar row model behind the admin trip-registrations table (see RegistrationCommands.RegRow): the
 * view holds ONLY these rows so PrimeFaces can sort them in place across postbacks, while the real
 * Registration and Person resolve per request through the row's userId. These tests pin the mirror
 * (rowsForTrip), the keyed per-request resolves (rowRegistration / registeredByLabel / regOptionValue),
 * and the session-serialization shape the whole design depends on.
 */
public class RegistrationRowsTest {

    private final RegistrationCommands reg = new RegistrationCommands();

    @Test
    public void rowsMirrorTheStoredRegistrationsAsScalars() throws IOException {
        final String tripId = "rows-trip-" + RandomData.genAlpha(8);
        final Person.Id traveler = Person.Id.newInstance();
        final Registration stored = new Registration(tripId, traveler).withStatusString("Pending");
        stored.getOptions().put(Registration.OPT_PARTY, "party-1");
        assertTrue(DAO.getInstance().saveRegistration(stored));

        final List<RegistrationCommands.RegRow> rows = reg.rowsForTrip(tripId);
        assertEquals(rows.size(), 1);
        final RegistrationCommands.RegRow row = rows.getFirst();
        assertEquals(row.getUserId(), traveler.getValue());
        assertEquals(row.getCreated(), stored.getCreated());
        assertEquals(row.getStatus(), "Pending");
        assertEquals(row.getParty(), "party-1");
    }

    @Test
    public void rowResolvesKeyIntoTheRequestsSingleRead() throws IOException {
        final String tripId = "rows-trip-" + RandomData.genAlpha(8);
        final Person owner = Person.builder()
                .first("Rowena").last(RandomData.genAlpha(8))
                .email("rows." + RandomData.genAlpha(10) + "@example.com")
                .build();
        assertTrue(DAO.getInstance().savePerson(owner));
        final Person.Id traveler = Person.Id.newInstance();
        final Registration stored = new Registration(tripId, traveler).withStatusString("Confirmed");
        stored.getOptions().put(Registration.OPT_REGISTERED_BY, owner.getId().getValue());
        stored.getOptions().put("7", "Window seat");
        assertTrue(DAO.getInstance().saveRegistration(stored));

        final List<Registration> regs = reg.getRegistrations(tripId);
        assertEquals(reg.rowRegistration(regs, traveler.getValue()).getStatus().getDescription(),
                "Confirmed");
        assertNull(reg.rowRegistration(regs, "nobody"), "A vanished row resolves to null, not a blank");
        assertNull(reg.rowRegistration(null, traveler.getValue()));
        assertNull(reg.rowRegistration(regs, null));

        assertEquals(reg.registeredByLabel(regs, traveler.getValue()), "Rowena",
                "The row-model label variant resolves through the same read");
        assertEquals(reg.registeredByLabel(regs, "nobody"), "",
                "No registration means no registered-by label");

        assertEquals(reg.regOptionValue(regs, traveler.getValue(), 7), "Window seat");
        assertNull(reg.regOptionValue(regs, traveler.getValue(), 99), "Unanswered option");
        assertNull(reg.regOptionValue(regs, traveler.getValue(), null));
        assertNull(reg.regOptionValue(regs, "nobody", 7));
    }

    /**
     * The row rides in the serialized session, so it must round-trip as plain field data: a no-arg
     * construction (Kryo runs no constructor) and a full serialize/deserialize with every field intact.
     */
    @Test
    public void regRowRoundTripsThroughSerialization() throws IOException, ClassNotFoundException {
        new RegistrationCommands.RegRow();
        final RegistrationCommands.RegRow row = new RegistrationCommands.RegRow(
                "user-1", java.time.LocalDateTime.of(2026, 8, 17, 12, 0), "Pending", "party-9");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(row);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(in.readObject(), row);
        }
    }
}
