package org.paulsens.trip.api;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.time.Instant;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TodoCommands;
import org.paulsens.trip.api.dto.AuditEventDto;
import org.paulsens.trip.api.dto.TodoStatusDto;
import org.paulsens.trip.api.mapper.ValueMappers;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The api package's small pieces: value mappers, the ObjectMapper provider, {@link Beans}, and the leftover DTO
 * corners. Small is not optional -- the whole point of the per-class coverage floor is that simple classes break
 * silently too.
 */
public class ApiSmallPiecesTest {

    // --- ValueMappers ---

    private final ValueMappers mappers = new ValueMappers();

    @Test
    public void idWrappersCrossTheWireAsPlainStrings() {
        Assert.assertEquals(mappers.toString(Person.Id.from("p1")), "p1");
        Assert.assertEquals(mappers.toPersonId("p1"), Person.Id.from("p1"));
        Assert.assertEquals(mappers.toString(DataId.from("d1")), "d1");
        Assert.assertEquals(mappers.toDataId("d1"), DataId.from("d1"));
        Assert.assertEquals(mappers.toIdStrings(List.of(Person.Id.from("a"))), List.of("a"));
        Assert.assertEquals(mappers.toPersonIds(List.of("a")), List.of(Person.Id.from("a")));
    }

    @Test
    public void nullsAndBlanksMapToNullsNotExceptions() {
        Assert.assertNull(mappers.toString((Person.Id) null));
        Assert.assertNull(mappers.toPersonId(null));
        Assert.assertNull(mappers.toPersonId("  "));
        Assert.assertNull(mappers.toString((DataId) null));
        Assert.assertNull(mappers.toDataId(""));
        Assert.assertNull(mappers.toIdStrings(null));
        Assert.assertNull(mappers.toPersonIds(null));
    }

    // --- ObjectMapperProvider ---

    /** The provider must answer the DAO's configured mapper, not a fresh default one; see its class comment. */
    @Test
    public void theWireMapperIsTheDaosOwnMapper() {
        Assert.assertSame(new ObjectMapperProvider().getContext(Object.class), DAO.getInstance().getMapper());
    }

    // --- Beans ---

    @Test
    public void beansSelectsWithTheAnyQualifier() {
        // @FacesConfig on PassCommands is a qualifier that suppresses @Default, so a bare select() cannot find
        // that one bean. The @Any literal in the select call is what this pins; see the Beans class comment.
        try (MockedStatic<CDI> cdi = Mockito.mockStatic(CDI.class)) {
            @SuppressWarnings("unchecked")
            final CDI<Object> container = Mockito.mock(CDI.class);
            @SuppressWarnings("unchecked")
            final Instance<TodoCommands> instance = Mockito.mock(Instance.class);
            final TodoCommands bean = Mockito.mock(TodoCommands.class);
            cdi.when(CDI::current).thenReturn(container);
            Mockito.doReturn(instance).when(container)
                    .select(ArgumentMatchers.eq(TodoCommands.class),
                            ArgumentMatchers.any(java.lang.annotation.Annotation.class));
            Mockito.when(instance.get()).thenReturn(bean);

            Assert.assertSame(Beans.get(TodoCommands.class), bean);

            final org.mockito.ArgumentCaptor<java.lang.annotation.Annotation> qualifier =
                    org.mockito.ArgumentCaptor.forClass(java.lang.annotation.Annotation.class);
            Mockito.verify(container).select(ArgumentMatchers.eq(TodoCommands.class), qualifier.capture());
            Assert.assertEquals(qualifier.getValue().annotationType().getName(),
                    "jakarta.enterprise.inject.Any");
        }
    }

    // --- DTO corners ---

    @Test
    public void auditEventDtoIsAPlainCarrier() {
        final AuditEventDto dto = new AuditEventDto(Instant.now(), "LOGIN", "SUCCESS", "a@x", "id",
                "person", "t@x", "tid", "msg", "org-1");
        Assert.assertEquals(dto.action(), "LOGIN");
        Assert.assertEquals(dto.orgId(), "org-1");
    }

    @Test
    public void withoutNotesStripsExactlyTheNote() {
        final TodoStatusDto dto = new TodoStatusDto("t", "d", "u", "desc", "more", "TODO", "NORMAL", "USER",
                "the private note", "owner", null, null);

        final TodoStatusDto stripped = dto.withoutNotes();

        Assert.assertNull(stripped.notes());
        Assert.assertEquals(stripped.description(), "desc", "Everything but the note survives");
    }

    // --- ApiPrivileges.levelFor, the redaction ladder ---

    private static ApiPrivilegesProbe probe(final boolean siteAdmin, final String heldPrivilege) {
        return new ApiPrivilegesProbe(siteAdmin, heldPrivilege);
    }

    /** Builds an ApiPrivileges over a session-shaped caller with one optional trip privilege stubbed. */
    private static final class ApiPrivilegesProbe {
        private final ApiPrivileges privileges;

        private ApiPrivilegesProbe(final boolean siteAdmin, final String heldPrivilege) {
            final org.paulsens.trip.action.PrivilegeCommands privs =
                    Mockito.mock(org.paulsens.trip.action.PrivilegeCommands.class);
            if (heldPrivilege != null) {
                Mockito.when(privs.check(ArgumentMatchers.eq(heldPrivilege), ArgumentMatchers.any(),
                        ArgumentMatchers.eq(Person.Id.from("viewer")))).thenReturn(true);
            }
            final Caller caller = new Caller(Person.Id.from("viewer"), siteAdmin,
                    org.paulsens.trip.audit.AuditActor.from(null), privs);
            this.privileges = new ApiPrivileges(caller, new PersonCommands());
        }
    }

    @Test
    public void levelForWalksTheLadderInOrder() {
        final Person viewer = new Person();
        viewer.setId(Person.Id.from("viewer"));
        final Person.Id subject = Person.Id.from("subject");

        Assert.assertEquals(probe(false, null).privileges.levelFor(viewer, Person.Id.from("viewer"), null),
                AccessLevel.SELF);

        final Person manager = new Person();
        manager.setId(Person.Id.from("viewer"));
        manager.setManagedUsers(List.of(subject));
        Assert.assertEquals(probe(false, null).privileges.levelFor(manager, subject, null),
                AccessLevel.MANAGER);

        Assert.assertEquals(probe(true, null).privileges.levelFor(viewer, subject, null),
                AccessLevel.SITE_ADMIN);
        Assert.assertEquals(probe(false, ApiPrivileges.TRIP_MGR).privileges.levelFor(viewer, subject, "t1"),
                AccessLevel.TRIP_ADMIN);
        Assert.assertEquals(probe(false, ApiPrivileges.TRIP_VIEW).privileges.levelFor(viewer, subject, "t1"),
                AccessLevel.TRIP_VIEWER);
        Assert.assertEquals(probe(false, null).privileges.levelFor(viewer, subject, "t1"),
                AccessLevel.PEER);
    }
}
