package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;

/**
 * The production (raw table) shapes of the trip-delete DAO sweeps, driven against a mocked
 * {@link Persistence}: the in-memory store does not back these tables (there the cache is the store, and
 * {@code TripDeleteCommandsTest} covers that side), so the raw query/scan enumeration is only reachable here.
 */
public class TripDeleteDaoTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void pdvScanSweepDeletesOnlyMatchingRows() {
        final Persistence persistence = deletablePersistence();
        final DataId target = DataId.from("room-trip-x");
        Mockito.when(persistence.scanAll(ArgumentMatchers.any())).thenReturn(List.of(
                Map.of("userId", attr("user-1"), "dataId", attr(target.getValue())),
                Map.of("userId", attr("user-2"), "dataId", attr("something-else")),
                Map.of("dataId", attr(target.getValue())),                      // missing userId: skipped
                Map.of("userId", attr("user-3"))));                             // missing dataId: skipped
        final PersonDataValueDAO dao = new PersonDataValueDAO(mapper, persistence);

        Assert.assertEquals(dao.deleteAllByDataIds(Set.of(target), Set.of()), 1,
                "only the matching complete row is deleted");
        Assert.assertEquals(dao.deleteAllByDataIds(Set.of(), Set.of()), 0, "no targets, no scan");
        Assert.assertEquals(dao.deleteAllByDataIds(null, null), 0);
    }

    @Test
    public void pdvCandidateSweepDeletesThroughTheCachedReadPath() throws Exception {
        final Persistence persistence = deletablePersistence();
        final Person.Id pid = Person.Id.from("candidate-1");
        final DataId room = DataId.from("roomtrip-y");
        final PersonDataValue kept = PersonDataValue.builder()
                .userId(pid).dataId(DataId.from("unrelated")).type("misc").content("keep").build();
        final PersonDataValue doomed = PersonDataValue.builder()
                .userId(pid).dataId(room).type("room").content("101A").build();
        Mockito.when(persistence.queryAll(ArgumentMatchers.any())).thenReturn(List.of(
                Map.of("content", attr(mapper.writeValueAsString(kept))),
                Map.of("content", attr(mapper.writeValueAsString(doomed)))));
        Mockito.when(persistence.scanAll(ArgumentMatchers.any())).thenReturn(List.of());
        final PersonDataValueDAO dao = new PersonDataValueDAO(mapper, persistence);

        Assert.assertEquals(dao.deleteAllByDataIds(Set.of(room), Set.of(pid)), 1,
                "the candidate's matching row is found via the cached read path");
    }

    @Test
    public void registrationSweepEnumeratesTheRawPartition() {
        final Persistence persistence = deletablePersistence();
        // Rows with no readable content (the read path would drop them) must still be key-swept.
        Mockito.when(persistence.queryAll(ArgumentMatchers.any())).thenReturn(List.of(
                Map.of("tripId", attr("t-1"), "userId", attr("user-a")),
                Map.of("tripId", attr("t-1"), "userId", attr("user-b")),
                Map.of("tripId", attr("t-1"))));                                // missing userId: skipped
        final RegistrationDAO dao = new RegistrationDAO(mapper, persistence);

        Assert.assertEquals(dao.deleteAllForTrip("t-1"), 2);
        Mockito.verify(persistence, Mockito.times(2)).deleteItem(ArgumentMatchers.any());
    }

    @Test
    public void todoSweepEnumeratesTheRawPartitionAndReturnsDataIds() {
        final Persistence persistence = deletablePersistence();
        Mockito.when(persistence.queryAll(ArgumentMatchers.any())).thenReturn(List.of(
                Map.of("tripId", attr("t-2"), "dataId", attr("todo-1")),
                Map.of("tripId", attr("t-2"))));                                // missing dataId: skipped
        final TodoDAO dao = new TodoDAO(mapper, persistence);

        Assert.assertEquals(dao.deleteAllForTrip("t-2"), List.of(DataId.from("todo-1")));
        Mockito.verify(persistence).deleteItem(ArgumentMatchers.any());
    }

    private static Persistence deletablePersistence() {
        final Persistence persistence = Mockito.mock(Persistence.class);
        Mockito.when(persistence.deleteItem(ArgumentMatchers.any())).thenReturn(
                (DeleteItemResponse) DeleteItemResponse.builder()
                        .sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build())
                        .build());
        return persistence;
    }

    private static AttributeValue attr(final String value) {
        return AttributeValue.builder().s(value).build();
    }
}
