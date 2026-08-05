package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.faces.context.FacesContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The DAO failure tails: a store whose writes FAIL maps to {@code false} (never an exception into the caller's
 * join), and a model that cannot serialize is refused loudly before any write is attempted.
 */
public class DAOFailureTailsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    /** Delegates to the real engine but fails every write. */
    private Persistence failingWrites() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("write refused"))
                .when(failing).putItem(ArgumentMatchers.any());
        return failing;
    }

    /** A mapper whose serialization always fails -- the shape of a model/Jackson regression. */
    private static ObjectMapper brokenMapper() throws Exception {
        final ObjectMapper broken = Mockito.mock(ObjectMapper.class);
        Mockito.when(broken.writeValueAsString(ArgumentMatchers.any()))
                .thenThrow(new com.fasterxml.jackson.databind.JsonMappingException(null, "cannot serialize"));
        return broken;
    }

    @Test
    public void aFailedWriteMapsToFalseAcrossTheDaos() throws Exception {
        final Persistence failing = failingWrites();
        final String tripId = "fail-" + RandomData.genAlpha(6);

        Assert.assertFalse(new TodoDAO(mapper, failing).saveTodo(
                TodoItem.builder().tripId(tripId)
                        .dataId(org.paulsens.trip.model.DataId.from("fail-todo"))
                        .description("t").build()));
        Assert.assertFalse(new RegistrationDAO(mapper, failing).saveRegistration(
                new org.paulsens.trip.model.Registration(tripId, Person.Id.from("u"))));
        Assert.assertFalse(new PersonDataValueDAO(mapper, failing).savePersonDataValue(
                org.paulsens.trip.model.PersonDataValue.builder()
                        .userId(Person.Id.from("u")).dataId(org.paulsens.trip.model.DataId.from("d"))
                        .type("t").content("c").build()));
        // These DAOs have no exceptionally on the write: a transport failure propagates to the caller's
        // join. Pinned as-is -- their action beans are the layer that maps it to a FacesMessage.
        Assert.assertThrows(RuntimeException.class,
                () -> new TripEventDAO(mapper, failing).saveTripEvent(
                        new org.paulsens.trip.model.TripEvent("e",
                                org.paulsens.trip.model.TripEvent.Type.EVENT, "t", null,
                                java.time.LocalDateTime.now(), null, null, null)));
        Assert.assertThrows(RuntimeException.class,
                () -> new ConfigDAO(mapper, failing).saveConfig(
                        new org.paulsens.trip.model.Config("f", "v", null, null, null, null)));
        Assert.assertThrows(RuntimeException.class,
                () -> new MediaDAO(mapper, failing).saveMedia(
                        new org.paulsens.trip.model.MediaItem("m", "k", "t", null, "ct", 1, null, 0,
                                null, null)));
        Assert.assertThrows(RuntimeException.class,
                () -> new TransactionDAO(mapper, failing).saveTransaction(
                        new org.paulsens.trip.model.Transaction(Person.Id.from("u"), "g1",
                                org.paulsens.trip.model.Transaction.Type.Tx)));
    }

    /** Delegates to the real engine but fails every partition read. */
    private Persistence failingReads() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused"))
                .when(failing).queryAll(ArgumentMatchers.any());
        return failing;
    }

    /**
     * A failed partition read must surface at the caller's join, never hang or silently answer empty --
     * these are the (synchronous, since the sync-client port) loader failure paths of the partition DAOs.
     */
    @Test
    public void aFailedReadPropagatesFromTheLoaders() {
        final Persistence failing = failingReads();
        final String tripId = "failread-" + RandomData.genAlpha(6);

        Assert.assertThrows(RuntimeException.class,
                () -> new RegistrationDAO(mapper, failing).getRegistrations(tripId));
        Assert.assertThrows(RuntimeException.class,
                () -> new TransactionDAO(mapper, failing).getTransactions(Person.Id.from("u")));
        Assert.assertThrows(RuntimeException.class,
                () -> new TodoDAO(mapper, failing).getTodoItems(tripId));
        Assert.assertThrows(RuntimeException.class,
                () -> new PersonDataValueDAO(mapper, failing).getPersonDataValues(Person.Id.from("u")));
        Assert.assertThrows(RuntimeException.class,
                () -> new BindingDAO(failing, new InMemoryCacheClient())
                        .getBindings(tripId, org.paulsens.trip.model.BindingType.TRIP,
                                org.paulsens.trip.model.BindingType.TRIP_EVENT));
    }

    /** Same contract for the point-read and full-scan loaders, and for the binding write pair. */
    @Test
    public void aFailedPointOrScanReadPropagatesToo() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused")).when(failing).getItem(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("scan refused")).when(failing).scanAll(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("write refused")).when(failing).putItem(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("delete refused"))
                .when(failing).deleteItem(ArgumentMatchers.any());

        Assert.assertThrows(RuntimeException.class,
                () -> new TripEventDAO(mapper, failing).getTripEvent("never-there"));
        // A valkey-like (non-InMemory) client is required: with InMemoryCacheClient the scan loaders are
        // deliberately unreachable (soft revalidate off -- the cache IS the local datastore).
        final CacheClient valkeyLike = Mockito.mock(CacheClient.class,
                AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
        Assert.assertThrows(RuntimeException.class,
                () -> new ConfigDAO(mapper, failing, valkeyLike).getAllConfig());
        Assert.assertThrows(RuntimeException.class,
                () -> new PrivilegesDAO(mapper, failing, valkeyLike).getGlobalPrivileges());
        final BindingDAO bindings = new BindingDAO(failing, new InMemoryCacheClient());
        Assert.assertThrows(RuntimeException.class,
                () -> bindings.saveBinding("a", org.paulsens.trip.model.BindingType.TRIP,
                        "b", org.paulsens.trip.model.BindingType.TRIP_EVENT, false));
        Assert.assertThrows(RuntimeException.class,
                () -> bindings.removeBinding("a", org.paulsens.trip.model.BindingType.TRIP,
                        "b", org.paulsens.trip.model.BindingType.TRIP_EVENT, false));
    }

    /** A cache CLIENT that throws (as opposed to a failing store) must also surface, not hang or invent. */
    @Test
    public void aThrowingCacheClientPropagatesThroughTheDaos() {
        final CacheClient broken = Mockito.mock(CacheClient.class,
                AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
        Mockito.doThrow(new IllegalStateException("cache refused"))
                .when(broken).getValue(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("cache refused"))
                .when(broken).getHashFields(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("cache refused"))
                .when(broken).putHashField(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        Assert.assertThrows(RuntimeException.class,
                () -> new PrivilegesDAO(mapper, DynamoLocal.persistence(), broken)
                        .getTripPrivileges("some-trip"));
        Assert.assertThrows(RuntimeException.class,
                () -> new PrivilegesDAO(mapper, DynamoLocal.persistence(), broken)
                        .getPrivilege("anyPrivName"));
        Assert.assertThrows(RuntimeException.class,
                () -> new PrivilegesDAO(mapper, DynamoLocal.persistence(), broken)
                        .savePrivilege(new Privilege("cacheTails", "d", java.util.List.of())));
        Assert.assertThrows(RuntimeException.class,
                () -> new ConfigDAO(mapper, DynamoLocal.persistence(), broken)
                        .getConfig("anySetting"));
        final ChatDAO chat = new ChatDAO(mapper, DynamoLocal.persistence(), broken);
        Assert.assertThrows(RuntimeException.class,
                () -> chat.getChannel(org.paulsens.trip.model.chat.ChatChannel.Id.from("trip:tails")));
        Assert.assertThrows(RuntimeException.class,
                () -> chat.getMembership(org.paulsens.trip.model.chat.ChatChannel.Id.from("trip:tails"),
                        Person.Id.from("p")));
        final TripDAO trips = new TripDAO(mapper, DynamoLocal.persistence(),
                new TripEventDAO(mapper, DynamoLocal.persistence()), broken);
        Assert.assertThrows(RuntimeException.class,
                () -> trips.getTrip("t-tails"));
        Assert.assertThrows(RuntimeException.class,
                () -> trips.getActiveTrips(java.time.LocalDateTime.now()));
        Assert.assertThrows(RuntimeException.class,
                () -> trips.getInactiveTrips(java.time.LocalDateTime.now(), 5));
        Assert.assertThrows(RuntimeException.class,
                () -> trips.getRecentTrips(5));
        Assert.assertThrows(RuntimeException.class,
                () -> trips.getTripsForUser(Person.Id.from("u")));
    }

    /** Null-input saves are refused with false, and a refused message write degrades to empty, not a 500. */
    @Test
    public void chatNullSavesAndRefusedMessageWritesDegrade() {
        final ChatDAO chat = new ChatDAO(mapper, DynamoLocal.persistence(), new InMemoryCacheClient());
        Assert.assertFalse(chat.saveChannel(null));
        Assert.assertFalse(chat.saveMembership(null));
        Assert.assertTrue(chat.getMessage(null, null).isEmpty());
        Assert.assertTrue(chat.saveMessage(null, null, null).isEmpty());

        // A non-conditional write failure on the message path maps to empty (logged), never an exception:
        // the send is the one chat write a user is actively waiting on.
        final Persistence failing = failingWrites();
        final ChatDAO refused = new ChatDAO(mapper, failing, new InMemoryCacheClient());
        final org.paulsens.trip.model.chat.ChatChannel channel =
                new org.paulsens.trip.model.chat.ChatChannel(
                        org.paulsens.trip.model.chat.ChatChannel.Id.forTrip("tails-w"), "tails-w",
                        org.paulsens.trip.model.chat.ChatChannel.Kind.TRIP, "Test", null, null, null,
                        java.time.Instant.now(), "admin", null, null);
        final org.paulsens.trip.model.chat.ChatMessage draft = new org.paulsens.trip.model.chat.ChatMessage(
                null, channel.getId(), Person.Id.from("p"), null,
                org.paulsens.trip.model.chat.ChatMessage.MessageKind.TEXT, "hi", null, null, null,
                null, null, null, null, null, null);
        Assert.assertTrue(refused.saveMessage(draft, channel, null).isEmpty());
    }

    /** The store-said-no branch (an unsuccessful HTTP status without an exception) maps to false. */
    @Test
    public void anUnsuccessfulPutResponseMapsToFalse() throws Exception {
        final Persistence refusing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder()
                        .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder()
                                .statusCode(500).build())
                        .build())
                .when(refusing).putItem(ArgumentMatchers.any());

        Assert.assertFalse(new RegistrationDAO(mapper, refusing).saveRegistration(
                new org.paulsens.trip.model.Registration("t-500", Person.Id.from("u"))));
        Assert.assertFalse(new TodoDAO(mapper, refusing).saveTodo(
                TodoItem.builder().tripId("t-500")
                        .dataId(org.paulsens.trip.model.DataId.from("todo-500"))
                        .description("t").build()));
        // The singular read's failure path, for symmetry with the plural sweep above.
        final Persistence failingReads = failingReads();
        Assert.assertThrows(RuntimeException.class,
                () -> new RegistrationDAO(mapper, failingReads)
                        .getRegistration("t-500", Person.Id.from("u")));
        Assert.assertThrows(RuntimeException.class,
                () -> new TodoDAO(mapper, failingReads)
                        .getTodoItem("t-500", org.paulsens.trip.model.DataId.from("todo-500")));
    }

    /** Credentials: reads propagate; the save maps to false (logged) -- login must not 500 on a flaky store. */
    @Test
    public void credentialsFailureTails() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused")).when(failing).getItem(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("write refused")).when(failing).putItem(ArgumentMatchers.any());
        final CredentialsDAO dao = new CredentialsDAO(failing, new PersonDAO(mapper, DynamoLocal.persistence()));

        Assert.assertThrows(RuntimeException.class,
                () -> dao.getCredsByEmailAndPass("who@test.com", "pw"));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.getCredsByEmailAdminOnly("who@test.com", Person.Id.from("u")));
        Assert.assertFalse(dao.saveCreds(new org.paulsens.trip.model.Creds(
                        "who@test.com", Person.Id.from("u"), "user", "pw", null)),
                "a refused credential write maps to false, never an exception into the login flow");
    }

    /** Media: the point read degrades to empty (logged); the full load and the delete propagate. */
    @Test
    public void mediaFailureTails() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused")).when(failing).getItem(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("scan refused")).when(failing).scanAll(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("delete refused"))
                .when(failing).deleteItem(ArgumentMatchers.any());
        final CacheClient valkeyLike = Mockito.mock(CacheClient.class,
                AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
        final MediaDAO dao = new MediaDAO(mapper, failing, valkeyLike);

        Assert.assertTrue(dao.getMedia("m-x").isEmpty(),
                "a failing media point read must degrade to empty (the page renders without the tile)");
        Assert.assertThrows(RuntimeException.class, () -> dao.getAllMedia());
        Assert.assertThrows(RuntimeException.class, () -> dao.deleteMedia("m-x"));
    }

    /** Chat: point reads degrade to empty (logged); list/page/reaction paths propagate. */
    @Test
    public void chatFailureTails() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused")).when(failing).getItem(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("query refused"))
                .when(failing).queryAll(ArgumentMatchers.any());
        Mockito.doThrow(new IllegalStateException("delete refused"))
                .when(failing).deleteItem(ArgumentMatchers.any());
        final ChatDAO dao = new ChatDAO(mapper, failing, new InMemoryCacheClient());
        final org.paulsens.trip.model.chat.ChatChannel.Id channel =
                org.paulsens.trip.model.chat.ChatChannel.Id.from("trip:tails-" + RandomData.genAlpha(6));

        Assert.assertTrue(dao.getChannel(channel).isEmpty(),
                "a failing channel point read must degrade to empty");
        Assert.assertTrue(dao.getMembership(channel, Person.Id.from("p")).isEmpty(),
                "a failing membership point read must degrade to empty");
        Assert.assertThrows(RuntimeException.class,
                () -> dao.listMembers(channel));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.deleteReaction(channel,
                        org.paulsens.trip.model.chat.ChatMessage.Id.from("00000000000001-0000"),
                        Person.Id.from("p"), ":+1:"));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.getMessage(channel,
                        org.paulsens.trip.model.chat.ChatMessage.Id.from("00000000000001-0000")));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.getReactionsForRange(channel,
                        org.paulsens.trip.model.chat.ChatMessage.Id.from("00000000000001-0000"),
                        org.paulsens.trip.model.chat.ChatMessage.Id.from("00000000000009-0000")));
    }

    /** The chat WRITE shims: a refused row write surfaces at the join for every chat table. */
    @Test
    public void chatWriteFailureTailsPropagate() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("write refused")).when(failing).putItem(ArgumentMatchers.any());
        final ChatDAO dao = new ChatDAO(mapper, failing, new InMemoryCacheClient());
        final String tripId = "tails-" + RandomData.genAlpha(6);
        final org.paulsens.trip.model.chat.ChatChannel channel =
                new org.paulsens.trip.model.chat.ChatChannel(
                        org.paulsens.trip.model.chat.ChatChannel.Id.forTrip(tripId), tripId,
                        org.paulsens.trip.model.chat.ChatChannel.Kind.TRIP, "Test", null, null, null,
                        java.time.Instant.now(), "admin", null, null);

        Assert.assertThrows(RuntimeException.class,
                () -> dao.saveChannel(channel));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.saveMembership(new org.paulsens.trip.model.chat.ChatMembership(
                        channel.getId(), Person.Id.from("p"),
                        org.paulsens.trip.model.chat.ChatMembership.MemberState.JOINED, null,
                        java.time.Instant.now(), null, null, null, null, null, null, null,
                        org.paulsens.trip.model.chat.ChatNotifyPref.defaults(), null, null, null,
                        null)));
        Assert.assertThrows(RuntimeException.class,
                () -> dao.putReaction(new org.paulsens.trip.model.chat.ChatReaction(
                        channel.getId(), org.paulsens.trip.model.chat.ChatMessage.Id.from("00000000000001-0000"),
                        Person.Id.from("p"), ":+1:", java.time.Instant.now(), null)));
    }

    @Test
    public void anUnserializableModelIsRefusedLoudly() throws Exception {
        final ObjectMapper broken = brokenMapper();
        final Persistence persistence = DynamoLocal.persistence();

        Assert.assertThrows(IllegalStateException.class, () -> new PrivilegesDAO(broken, persistence)
                .savePrivilege(new Privilege("brokenPriv", "d", null)));
        Assert.assertThrows(IllegalStateException.class, () -> new ConfigDAO(broken, persistence)
                .saveConfig(new org.paulsens.trip.model.Config("b", "v", null, null, null, null)));
        Assert.assertThrows(IllegalStateException.class, () -> new MediaDAO(broken, persistence)
                .saveMedia(new org.paulsens.trip.model.MediaItem("m2", "k", "t", null, "ct", 1, null, 0,
                        null, null)));
    }

    /** A mangled audit row must be SKIPPED by the admin page's walk, never render it a 500. */
    @Test
    public void aMangledAuditRowIsSkippedByTheAdminWalk() {
        final Persistence persistence = DynamoLocal.persistence();
        final AuditDAO dao = new AuditDAO(mapper, persistence);
        final String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        persistence.putItem(b -> b.tableName("audit").item(Map.of(
                "day", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s(today).build(),
                "ts", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("1").build(),
                "content", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s("{ not json").build())));

        final org.paulsens.trip.model.AuditPage page = dao.getAuditEvents(
                org.paulsens.trip.model.AuditQuery.builder().build());

        Assert.assertTrue(page.getEvents().stream().noneMatch(e -> e == null),
                "a mangled row must vanish, not appear as a null event");
    }

    /** adminGetCredsByEmail is gated on a FacesContext AND the view's admin flag -- both checked here. */
    @Test
    public void adminCredsLookupRequiresTheAdminView() {
        final CredentialsDAO dao = new CredentialsDAO(DynamoLocal.persistence(),
                new PersonDAO(mapper, DynamoLocal.persistence()));

        Assert.assertNull(dao.adminGetCredsByEmail("x@example.org"),
                "no FacesContext at all: refused");

        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<String, Object> viewMap = new HashMap<>();
        Mockito.when(ctx.getViewRoot().getViewMap(false)).thenReturn(viewMap);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            Assert.assertNull(dao.adminGetCredsByEmail("x@example.org"),
                    "a non-admin view: refused");

            viewMap.put(CredentialsDAO.IS_ADMIN, "true");
            Assert.assertNull(dao.adminGetCredsByEmail("missing-" + RandomData.genAlpha(6) + "@x.org")
                    , "admin, but no such account: null rather than an invented Creds");
        }
    }

    /** The admin-gated read and remove paths surface store failures at the join (never hang or invent). */
    @Test
    public void adminCredsFailureTailsPropagate() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doThrow(new IllegalStateException("read refused")).when(failing).getItem(ArgumentMatchers.any());
        final CredentialsDAO dao = new CredentialsDAO(failing,
                new PersonDAO(mapper, DynamoLocal.persistence()));

        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<String, Object> viewMap = new HashMap<>();
        viewMap.put(CredentialsDAO.IS_ADMIN, "true");
        Mockito.when(ctx.getViewRoot().getViewMap(false)).thenReturn(viewMap);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            Assert.assertThrows(RuntimeException.class,
                    () -> dao.adminGetCredsByEmail("x@example.org"));
        }
    }
}
