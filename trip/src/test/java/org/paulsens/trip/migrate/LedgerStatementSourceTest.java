package org.paulsens.trip.migrate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.action.LedgerCommands.Statement;
import org.paulsens.trip.action.LedgerCommands.TripGroup;
import org.paulsens.trip.action.LedgerStatements;
import org.paulsens.trip.migrate.LedgerBindingVerifier.OrgStatement;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.paulsens.trip.migrate.LedgerFixture.ORG;
import static org.paulsens.trip.migrate.LedgerFixture.OTHER_ORG;
import static org.paulsens.trip.migrate.LedgerFixture.TODAY;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The Balance page's statement code run over a parsed dump: what verify's checks 1, 2 and 4 stand on. Every id is
 * random and every name and amount invented.
 */
public class LedgerStatementSourceTest {
    private static final LocalDate EARLY_2024 = LocalDate.of(2024, 2, 1);

    private final LedgerFixture ledger = new LedgerFixture();

    private static String txId(final String key) {
        return key.substring(key.indexOf(',') + 1);
    }

    @Test
    public void everyLiveRowIsOnItsOwnOrganizationsStatementAndADeletedOneOnNone() {
        final String ann = ledger.person("Ann", "Example");
        final String trip = ledger.endedTrip("Spring", ann);
        final String onTrip = ledger.row(ann, -500, EARLY_2024);
        final String paid = ledger.row(ann, 200, EARLY_2024.plusDays(3));
        final String loose = ledger.row(ann, -40, EARLY_2024.plusDays(5));
        final String elsewhere = ledger.row(ann, OTHER_ORG, -75, EARLY_2024);
        final String stray = ledger.row(ann, null, 12, EARLY_2024);
        final String deleted = ledger.row(ann, -999, EARLY_2024);
        final Transaction gone = ledger.tx(deleted);
        gone.delete();
        ledger.put(gone);
        ledger.bind(onTrip, trip);
        ledger.bind(paid, trip);

        final Map<String, OrgStatement> stated = LedgerStatementSource.statements(ledger.data()).of(ann);

        assertEquals(stated.keySet(), Set.of("", ORG, OTHER_ORG), "a row with no organization is keyed \"\"");
        assertEquals(Set.copyOf(stated.get(ORG).txIds()), Set.of(txId(onTrip), txId(paid), txId(loose)));
        assertEquals(stated.get(ORG).txIds().size(), 3, "each row once, the deleted one not at all");
        assertEquals(stated.get(ORG).balance(), -340d);
        assertEquals(stated.get(OTHER_ORG).txIds(), List.of(txId(elsewhere)));
        assertEquals(stated.get(OTHER_ORG).balance(), -75d);
        assertEquals(stated.get("").txIds(), List.of(txId(stray)));
        assertEquals(LedgerStatementSource.statements(ledger.data()).of("nobody"), Map.of());
    }

    @Test
    public void aStatementIsGroupedByTheTripsTheDumpBindsItsRowsTo() {
        final String ann = ledger.person("Ann", "Example");
        final String spring = ledger.endedTrip("Spring", ann);
        final String fall = ledger.laterTrip("Fall", ann);
        final String foreign = ledger.trip(OTHER_ORG, "Theirs", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 9));
        ledger.bind(ledger.row(ann, -500, EARLY_2024), spring);
        ledger.bind(ledger.row(ann, -300, EARLY_2024.plusDays(1)), fall);
        ledger.bind(ledger.row(ann, -20, EARLY_2024.plusDays(2)), foreign);
        ledger.bind(ledger.row(ann, -10, EARLY_2024.plusDays(3)), "a-trip-that-is-gone");
        final LedgerData data = ledger.data();

        final Statement statement = new LedgerStatements(new LedgerStatementSource(data),
                List.of(Person.Id.from(ann))).statement(ORG);

        assertEquals(statement.orgName(), "First Org");
        assertEquals(statement.trips().stream().map(TripGroup::title).toList(),
                List.of("Fall", "Spring", "Not yet assigned to a trip"),
                "newest trip first; a row on another organization's trip or a missing one is unassigned");
        assertEquals(statement.trips().get(2).rows().size(), 2);
        assertEquals(statement.trips().get(0).rows().get(0).personName(), "Ann Example");
        assertEquals(statement.trips().get(0).rows().get(0).personPreferredName(), "Ann");
        assertFalse(statement.trips().get(0).payableInApp());
        assertEquals(statement.trips().get(0).rows().get(0).eventTitle(), "");
        assertEquals(statement.balanceCents(), -83000L);
        assertEquals(statement.trips().get(1).startDate().toLocalDate(), LocalDate.of(2024, 5, 1));
    }

    @Test
    public void aTripsOwnRowsAndABindingWrittenLaterAreSeen() {
        final String trip = ledger.endedTrip("Spring");
        final String own = ledger.row(trip, -700, EARLY_2024);
        final LedgerData data = ledger.data();
        final LedgerBindingVerifier.Statements statements = LedgerStatementSource.statements(data);
        assertEquals(statements.of(trip).get(ORG).txIds(), List.of(txId(own)), "a trip's partition has a statement");

        final LedgerStatementSource source = new LedgerStatementSource(data);
        assertEquals(source.bindings(own, BindingType.TRIP), List.of());
        data.bindForward(own, trip);
        assertEquals(source.bindings(own, BindingType.TRIP), List.of(trip), "a rehearsal's write is read");
        assertEquals(source.bindings(own, BindingType.TRIP_EVENT), List.of(), "no event binding is in a dump");
        assertEquals(source.personName(Person.Id.from(trip)), trip, "a partition that is not a person: its id");
        assertEquals(source.eventTitle("any"), "");
        assertEquals(source.preferredName(Person.Id.from(trip)), "", "nor a first name");
        assertFalse(source.payableInApp(source.trip(trip)), "a dump holds no payment configuration");
        assertTrue(source.sees(Person.Id.from(trip), null), "system reach: every row, whatever its organization");
    }

    @Test
    public void aTripIsHandedOverWithWhatAStatementShowsOfIt() {
        final String dated = ledger.endedTrip("Spring");
        final String undated = ledger.trip(null, "Undated", null, null);
        final LedgerStatementSource source = new LedgerStatementSource(ledger.data());

        final Trip trip = source.trip(dated);
        assertEquals(trip.getId(), dated);
        assertEquals(trip.getTitle(), "Spring");
        assertEquals(trip.getOrgId(), ORG);
        assertEquals(trip.getEndDate().toLocalDate(), LocalDate.of(2024, 5, 10));
        assertNull(source.trip(undated).getStartDate());
        assertNull(source.trip(undated).getEndDate());
        assertNull(source.trip(undated).getOrgId());
        assertNull(source.trip("gone"), "a binding to a trip that no longer exists names no trip");
        assertEquals(source.orgName(OTHER_ORG), "Second Org");
        assertFalse(TODAY.isBefore(LocalDate.of(2024, 5, 10)));
    }
}
