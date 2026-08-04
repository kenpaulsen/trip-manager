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
        Mockito.doReturn(CompletableFuture.failedFuture(new IllegalStateException("write refused")))
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
                        .description("t").build()).join());
        Assert.assertFalse(new RegistrationDAO(mapper, failing).saveRegistration(
                new org.paulsens.trip.model.Registration(tripId, Person.Id.from("u"))).join());
        Assert.assertFalse(new PersonDataValueDAO(mapper, failing).savePersonDataValue(
                org.paulsens.trip.model.PersonDataValue.builder()
                        .userId(Person.Id.from("u")).dataId(org.paulsens.trip.model.DataId.from("d"))
                        .type("t").content("c").build()).join());
        // These DAOs have no exceptionally on the write: a transport failure propagates to the caller's
        // join. Pinned as-is -- their action beans are the layer that maps it to a FacesMessage.
        Assert.assertThrows(java.util.concurrent.CompletionException.class,
                () -> new TripEventDAO(mapper, failing).saveTripEvent(
                        new org.paulsens.trip.model.TripEvent("e",
                                org.paulsens.trip.model.TripEvent.Type.EVENT, "t", null,
                                java.time.LocalDateTime.now(), null, null, null)).join());
        Assert.assertThrows(java.util.concurrent.CompletionException.class,
                () -> new ConfigDAO(mapper, failing).saveConfig(
                        new org.paulsens.trip.model.Config("f", "v", null, null, null, null)).join());
        Assert.assertThrows(java.util.concurrent.CompletionException.class,
                () -> new MediaDAO(mapper, failing).saveMedia(
                        new org.paulsens.trip.model.MediaItem("m", "k", "t", null, "ct", 1, null, 0,
                                null, null)).join());
    }

    @Test
    public void anUnserializableModelIsRefusedLoudly() throws Exception {
        final ObjectMapper broken = brokenMapper();
        final Persistence persistence = DynamoLocal.persistence();

        Assert.assertThrows(IllegalStateException.class, () -> new PrivilegesDAO(broken, persistence)
                .savePrivilege(new Privilege("brokenPriv", "d", null)).join());
        Assert.assertThrows(IllegalStateException.class, () -> new ConfigDAO(broken, persistence)
                .saveConfig(new org.paulsens.trip.model.Config("b", "v", null, null, null, null)).join());
        Assert.assertThrows(IllegalStateException.class, () -> new MediaDAO(broken, persistence)
                .saveMedia(new org.paulsens.trip.model.MediaItem("m2", "k", "t", null, "ct", 1, null, 0,
                        null, null)).join());
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
                        .s("{ not json").build()))).join();

        final org.paulsens.trip.model.AuditPage page = dao.getAuditEvents(
                org.paulsens.trip.model.AuditQuery.builder().build()).join();

        Assert.assertTrue(page.getEvents().stream().noneMatch(e -> e == null),
                "a mangled row must vanish, not appear as a null event");
    }

    /** adminGetCredsByEmail is gated on a FacesContext AND the view's admin flag -- both checked here. */
    @Test
    public void adminCredsLookupRequiresTheAdminView() {
        final CredentialsDAO dao = new CredentialsDAO(DynamoLocal.persistence(),
                new PersonDAO(mapper, DynamoLocal.persistence()));

        Assert.assertNull(dao.adminGetCredsByEmail("x@example.org").join(),
                "no FacesContext at all: refused");

        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final Map<String, Object> viewMap = new HashMap<>();
        Mockito.when(ctx.getViewRoot().getViewMap(false)).thenReturn(viewMap);
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            Assert.assertNull(dao.adminGetCredsByEmail("x@example.org").join(),
                    "a non-admin view: refused");

            viewMap.put(CredentialsDAO.IS_ADMIN, "true");
            Assert.assertNull(dao.adminGetCredsByEmail("missing-" + RandomData.genAlpha(6) + "@x.org")
                    .join(), "admin, but no such account: null rather than an invented Creds");
        }
    }
}
