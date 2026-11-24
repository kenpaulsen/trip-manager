package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.RoomBlock;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The exact condition every versioned DAO puts with. The in-memory fake evaluates a condition but cannot say the
 * request named the right attribute, and these conditions are what keep two writers from overwriting each other
 * (a payment processor's credentials, an organization's admins, a family's members): version 0 may only CREATE,
 * refused when the named key attribute already exists; any other version may only replace that exact stored
 * version. The attribute a create names differs per table and is not always the partition key.
 */
public class VersionedPutConditionTest {
    private static final LocalDateTime AT = LocalDateTime.of(2027, 5, 1, 15, 0);

    private final RecordingPersistence store = new RecordingPersistence();

    /** A save that can fail only by throwing; each bumps its row's version when it succeeds. */
    @FunctionalInterface
    private interface Save {
        Boolean run() throws IOException;
    }

    @Test
    public void familySavesOnItsId() throws IOException {
        final FamilyDAO dao = new FamilyDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final Person.Id creator = Person.Id.from(RandomData.genAlpha(8));
        final Family family = Family.builder().id(Family.Id.from(RandomData.genAlpha(10)))
                .memberIds(List.of(creator)).managerIds(List.of(creator)).createdBy(creator).build();
        assertCreateThenReplace(() -> dao.saveFamily(family), FamilyDAO.FAMILY_TABLE, "id");
    }

    @Test
    public void organizationSavesOnItsId() throws IOException {
        final OrganizationDAO dao =
                new OrganizationDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final Organization org = Organization.builder().name("Condition " + RandomData.genAlpha(6)).build();
        assertCreateThenReplace(() -> dao.saveOrganization(org), OrganizationDAO.ORG_TABLE, "id");
    }

    @Test
    public void processorConfigSavesOnItsIdNotItsOrg() throws IOException {
        final PaymentProcessorDAO dao =
                new PaymentProcessorDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final PaymentProcessorConfig config = PaymentProcessorConfig.builder()
                .orgId(Organization.Id.newInstance()).label("Condition").type(ProcessorType.FAKE).enabled(true)
                .build();
        assertCreateThenReplace(() -> dao.saveConfig(config), PaymentProcessorDAO.PROCESSORS_TABLE, "id");
    }

    @Test
    public void budgetAndExpenseReportSaveOnTheirTripId() throws IOException {
        final BudgetDAO budgets = new BudgetDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final TripBudget budget = TripBudget.builder().tripId("trip-" + RandomData.genAlpha(10)).build();
        assertCreateThenReplace(() -> budgets.saveBudget(budget), BudgetDAO.BUDGETS_TABLE, "tripId");

        final ExpenseReportDAO reports =
                new ExpenseReportDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final TripExpenseReport report =
                TripExpenseReport.builder().tripId("trip-" + RandomData.genAlpha(10)).build();
        assertCreateThenReplace(() -> reports.saveReport(report), ExpenseReportDAO.EXPENSES_TABLE, "tripId");
    }

    @Test
    public void waiverDocumentSavesOnItsIdNotItsOrg() throws IOException {
        final WaiverDAO dao = new WaiverDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final WaiverDocument doc = WaiverDocument.builder().orgId("org-1").title("Condition").build();
        assertCreateThenReplace(() -> dao.saveDocument(doc), WaiverDAO.WAIVERS_TABLE, "id");
    }

    @Test
    public void reminderPlanSavesOnItsSortKey() throws IOException {
        final ReminderDAO dao = new ReminderDAO(DAO.getInstance().getMapper(), store);
        final TripReminderPlan plan = TripReminderPlan.builder().tripId("trip-" + RandomData.genAlpha(10)).build();
        assertCreateThenReplace(() -> dao.savePlan(plan), ReminderDAO.REMINDERS_TABLE, "sk");
    }

    @Test
    public void theFiveLodgingRowsSaveOnTheirId() throws IOException {
        final LodgingDAO dao = new LodgingDAO(DAO.getInstance().getMapper(), store, new InMemoryCacheClient());
        final Accommodation acc = Accommodation.builder().name("Condition " + RandomData.genAlpha(6)).build();
        assertCreateThenReplace(() -> dao.saveAccommodation(acc), LodgingDAO.ACCOMMODATIONS_TABLE, "id");

        final ReservationOffer offer = ReservationOffer.builder().tripId("trip-1").name("Double").build();
        assertCreateThenReplace(() -> dao.saveOffer(offer), LodgingDAO.OFFERS_TABLE, "id");

        final Reservation res = Reservation.builder().tripId("trip-1").accommodationId(acc.getId())
                .start(AT).end(AT.plusDays(2)).build();
        assertCreateThenReplace(() -> dao.saveReservation(res), LodgingDAO.RESERVATIONS_TABLE, "id");

        final RoomBlock block = RoomBlock.builder().accommodationId(acc.getId()).roomIds(List.of("room-1"))
                .start(AT.toLocalDate()).end(AT.toLocalDate().plusDays(2)).build();
        assertCreateThenReplace(() -> dao.saveBlock(block), LodgingDAO.BLOCKS_TABLE, "id");

        final RatePlan plan = RatePlan.builder().accommodationId(acc.getId()).name("Summer").build();
        assertCreateThenReplace(() -> dao.savePlan(plan), LodgingDAO.RATE_PLANS_TABLE, "id");
    }

    /**
     * Saves the same object twice: the first put (version 0) must be a create conditioned on {@code createAttr}
     * not existing, the second (version 1) a replace conditioned on the stored version being exactly 1. Each put
     * carries the NEXT version in its {@code version} attribute.
     */
    private void assertCreateThenReplace(final Save save, final String table, final String createAttr)
            throws IOException {
        // TestNG runs every method on one instance, so the store is shared: count only this row's two puts.
        store.puts.clear();
        assertTrue(save.run());
        assertTrue(save.run());
        assertEquals(store.puts.size(), 2);

        final PutItemRequest create = store.puts.get(0);
        assertEquals(create.tableName(), table);
        assertEquals(create.conditionExpression(), "attribute_not_exists(#i)");
        assertEquals(create.expressionAttributeNames(), Map.of("#i", createAttr));
        assertTrue(create.expressionAttributeValues().isEmpty(), "a create compares nothing");
        assertEquals(create.item().get("version"), number(1));
        assertTrue(create.item().containsKey(createAttr), "the create's condition names an attribute of the row");

        final PutItemRequest replace = store.puts.get(1);
        assertEquals(replace.tableName(), table);
        assertEquals(replace.conditionExpression(), "#v = :expected");
        assertEquals(replace.expressionAttributeNames(), Map.of("#v", "version"));
        assertEquals(replace.expressionAttributeValues(), Map.of(":expected", number(1)));
        assertEquals(replace.item().get("version"), number(2));
    }

    private static AttributeValue number(final long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
