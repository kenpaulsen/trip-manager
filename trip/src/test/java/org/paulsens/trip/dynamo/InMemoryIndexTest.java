package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.util.RandomData;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * The in-memory fake's secondary-index support, through the one index that uses it. A fake that answered
 * nothing here would make the hotel's cross-trip view untestable, and a fake that answered the WRONG
 * partition would be worse: the query carries three placeholders, and picking the first one at random used
 * to be good enough only because every earlier query carried one.
 */
public class InMemoryIndexTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 20, 15, 0);

    @Test
    public void anIndexQueryPicksItsPartitionKeyOutOfTheExpression() throws IOException {
        final Accommodation.Id wanted = Accommodation.Id.newInstance();
        final Accommodation.Id other = Accommodation.Id.newInstance();
        final Reservation mine = at(wanted, AT.plusDays(3));
        assertTrue(DAO.getInstance().saveReservation(mine));
        assertTrue(DAO.getInstance().saveReservation(at(other, AT.plusDays(3))));
        // Both the partition value and the two range bounds are :placeholders in one expression.
        assertEquals(DAO.getInstance().getReservationsAt(wanted, AT), List.of(mine));
    }

    @Test
    public void aRowMissingAnIndexKeyIsSimplyNotIndexed() throws IOException {
        final Accommodation.Id acc = Accommodation.Id.newInstance();
        final Reservation noEnd = Reservation.builder().tripId("trip-" + RandomData.genAlpha(6))
                .accommodationId(acc).occupants(List.of(Person.Id.newInstance())).start(AT).build();
        final Reservation noHotel = Reservation.builder().tripId("trip-" + RandomData.genAlpha(6))
                .occupants(List.of(Person.Id.newInstance())).start(AT).end(AT.plusDays(2)).build();
        assertTrue(DAO.getInstance().saveReservation(noEnd));
        assertTrue(DAO.getInstance().saveReservation(noHotel));
        assertTrue(DAO.getInstance().getReservationsAt(acc, null).isEmpty(),
                "a sparse index holds only rows carrying both of its keys");
        // Both rows are still readable by their own table keys, which is the point of sparseness.
        assertTrue(DAO.getInstance().getReservation(noEnd.getTripId(), noEnd.getId(), Cached.NO).isPresent());
    }

    @Test
    public void theIndexKeysFollowALaterEditOfTheStay() throws IOException {
        final Accommodation.Id acc = Accommodation.Id.newInstance();
        final Reservation res = at(acc, AT.plusDays(3));
        assertTrue(DAO.getInstance().saveReservation(res));
        assertEquals(DAO.getInstance().getReservationsAt(acc, AT.plusDays(3)).size(), 1);
        res.setEnd(AT.plusDays(9));
        assertTrue(DAO.getInstance().saveReservation(res));
        assertEquals(DAO.getInstance().getReservationsAt(acc, AT.plusDays(8)).size(), 1,
                "the index key is rewritten with the row, so a lengthened stay stays findable");
    }


    /** A query naming an index the table does not have is a mistake, not an empty answer. */
    @Test
    public void twoConditionsOnOneSortKeyAreRefusedLikeDynamoDB() {
        // "> :after AND < :before" is what the chat reaction window shipped with: the fake evaluated it and
        // production answered 400 on every read. BETWEEN is the one legal spelling of a two-sided range.
        final InMemoryPersistence fake = new InMemoryPersistence();
        final Map<String, String> names = Map.of("#channelId", ChatDAO.ATTR_CHANNEL_ID, "#msgId", ChatDAO.ATTR_MSG_ID);
        final Map<String, AttributeValue> values = Map.of(
                ":c", AttributeValue.builder().s("trip:x").build(),
                ":after", AttributeValue.builder().s("1").build(),
                ":before", AttributeValue.builder().s("9").build());
        final IllegalArgumentException refused = expectThrows(IllegalArgumentException.class,
                () -> fake.query(b -> b.tableName(ChatDAO.MESSAGES_TABLE)
                        .keyConditionExpression("#channelId = :c AND #msgId > :after AND #msgId < :before")
                        .expressionAttributeNames(names).expressionAttributeValues(values)));
        assertTrue(refused.getMessage().contains("one condition per key"), refused.getMessage());
        // The same window as ONE condition, unaliased on the sort key, is accepted.
        final Map<String, AttributeValue> between = Map.of(
                ":c", AttributeValue.builder().s("trip:x").build(),
                ":lo", AttributeValue.builder().s("1").build(),
                ":hi", AttributeValue.builder().s("9").build());
        assertTrue(fake.query(b -> b.tableName(ChatDAO.MESSAGES_TABLE)
                .keyConditionExpression("#channelId = :c AND " + ChatDAO.ATTR_MSG_ID + " BETWEEN :lo AND :hi")
                .expressionAttributeNames(Map.of("#channelId", ChatDAO.ATTR_CHANNEL_ID))
                .expressionAttributeValues(between)).items().isEmpty());
    }

    @Test
    public void anUnknownIndexFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryPersistence()
                .queryAll(qb -> qb.tableName(LodgingDAO.RESERVATIONS_TABLE).indexName("no-such-index")
                        .keyConditionExpression("accommodationId = :a")
                        .expressionAttributeValues(java.util.Map.of(":a",
                                software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                        .s("x").build()))
                        .build()));
    }

    /** An aliased partition key resolves through ExpressionAttributeNames, the way a reserved word must. */
    @Test
    public void anAliasedPartitionKeyStillResolves() throws IOException {
        final Accommodation.Id acc = Accommodation.Id.newInstance();
        final InMemoryPersistence store = new InMemoryPersistence();
        final LodgingDAO dao = new LodgingDAO(DAO.getInstance().getMapper(), store,
                new org.paulsens.trip.cache.NoopCacheClient());
        assertTrue(dao.saveReservation(at(acc, AT.plusDays(2))));
        final List<java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> rows =
                store.queryAll(qb -> qb
                        .tableName(LodgingDAO.RESERVATIONS_TABLE).indexName(LodgingDAO.BY_ACCOMMODATION)
                        .keyConditionExpression("#a = :a")
                        .expressionAttributeNames(java.util.Map.of("#a", "accommodationId"))
                        .expressionAttributeValues(java.util.Map.of(":a",
                                software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                        .s(acc.getValue()).build()))
                        .build());
        assertEquals(rows.size(), 1);
    }

    private static Reservation at(final Accommodation.Id accId, final LocalDateTime end) {
        return Reservation.builder().tripId("trip-" + RandomData.genAlpha(8)).accommodationId(accId)
                .occupants(List.of(Person.Id.newInstance())).start(AT).end(end).build();
    }
}
