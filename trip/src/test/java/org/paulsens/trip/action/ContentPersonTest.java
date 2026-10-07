package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PersonType;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Person cards on content pages: the picker's tenancy (an org page offers only that org's members, and
 * only to someone who may edit it), the save-time re-check a forged id cannot get around, and the card a
 * seeded board row renders.
 */
public class ContentPersonTest {

    private static final Person.Id GLOBAL_EDITOR = Person.Id.from("person-card-global-editor");
    private static final Person.Id ACME_EDITOR = Person.Id.from("person-card-acme-editor");
    private static final Person.Id NOBODY = Person.Id.from("person-card-nobody");
    private static final Person.Id EVENTS_EDITOR = Person.Id.from("person-card-events-editor");
    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final String ACME_BOARD = OrgPageBootstrap.pageKey(ACME, "board");

    private final PrivilegeCommands priv = new PrivilegeCommands();
    private Person kevin;
    private Person ken;
    private Person ann;

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
        // Other suite tests clear caches, and a cleared local partition never rebuilds (ContentCommandsTest).
        DAO.getInstance().getAllContentRecords(Cached.NO).stream()
                .map(ContentRecord::getCurrent)
                .filter(current -> current != null)
                .forEach(current -> DAO.getInstance().saveContent(current, 5));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "person-card global", null,
                List.of(GLOBAL_EDITOR)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "person-card acme",
                FakeData.ACME_ORG_ID, List.of(ACME_EDITOR)));
        // The class owns its people: the seeded personas' cells and memberships are other tests' to change.
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.EVENT_ADMIN, "person-card events", null,
                List.of(EVENTS_EDITOR)));
        kevin = ownPerson("Kevin", "Cardholder", "(555) 010-0103");
        ann = ownPerson("Ann", "Cardholder", null);
        ken = ownPerson("Ken", "Outsider", null);
        try {
            Assert.assertTrue(DAO.getInstance().saveOrgMember(new OrgMember(ACME, kevin.getId(), null)));
            Assert.assertTrue(DAO.getInstance().saveOrgMember(new OrgMember(ACME, ann.getId(), null)));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Person ownPerson(final String first, final String last, final String cell) {
        final Person person = Person.builder().id(Person.Id.newInstance()).first(first).last(last).cell(cell)
                .email(first.toLowerCase(java.util.Locale.ROOT) + "." + UUID.randomUUID() + "@example.com").build();
        try {
            Assert.assertTrue(DAO.getInstance().savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private static ContentCommands as(final Person.Id who) {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> new Caller(who, false,
                new AuditActor(who.getValue() + "@example.com", who.getValue()), new PrivilegeCommands()));
        return content;
    }

    private static ContentInstance card(final String section, final String personId) {
        return new ContentInstance(UUID.randomUUID().toString(), section, "", StarterTemplates.PERSON_ID, 1,
                new HashMap<>(Map.of(PersonType.PROP_PERSON_ID, personId, PersonType.PROP_ROLE, "Member")),
                null, 0, 0, null, null);
    }

    private static List<String> ids(final List<Person> people) {
        return people.stream().map(person -> person.getId().getValue()).toList();
    }

    @Test
    public void anOrgPageOffersOnlyThatOrgsMembersWhoeverAsks() {
        final List<String> forOrgEditor = ids(as(ACME_EDITOR).completeForContent(ACME_BOARD, ""));
        Assert.assertTrue(forOrgEditor.contains(kevin.getId().getValue()));
        Assert.assertFalse(forOrgEditor.contains(ken.getId().getValue()), "never another tenant's people");
        Assert.assertTrue(ids(as(ACME_EDITOR).completeForContent(ACME_BOARD, "cardholder")).contains(
                kevin.getId().getValue()), "the query narrows by name");
        Assert.assertTrue(as(ACME_EDITOR).completeForContent(ACME_BOARD, "zzz-nobody-matches").isEmpty());
        Assert.assertFalse(ids(as(GLOBAL_EDITOR).completeForContent(ACME_BOARD, "")).contains(
                ken.getId().getValue()), "a global editor on an org page is held to that org too");
    }

    @Test
    public void membersAreOfferedByLastNameThenFirst() {
        Assert.assertEquals(ids(as(ACME_EDITOR).completeForContent(ACME_BOARD, "cardholder")),
                List.of(ann.getId().getValue(), kevin.getId().getValue()));
    }

    @Test
    public void aContainerEditorMayNotSearchEveryone() {
        Assert.assertTrue(as(EVENTS_EDITOR).completeForContent("events", "Ken").isEmpty(),
                "editing the shared Events container is not a licence to browse every person");
    }

    @Test
    public void aCardIsComputedOncePerRequest() {
        final Map<String, Object> requestMap = new HashMap<>();
        RequestFacesContext.install(requestMap);
        try {
            final ContentCommands content = as(GLOBAL_EDITOR);
            final ContentInstance row = card(FakeData.BOARD_PAGE_KEY, kevin.getId().getValue());
            final PersonType.Card first = content.personCard(row, true);
            Assert.assertSame(content.personCard(row, true), first, "the second read is the memo");
            Assert.assertEquals(requestMap.size(), 1);
            Assert.assertNotSame(content.personCard(row, false), first, "signed-in and anonymous differ");
        } finally {
            RequestFacesContext.clear();
        }
    }

    /** {@code FacesContext.setCurrentInstance} is protected, so reach it from a subclass. */
    private abstract static class RequestFacesContext extends FacesContext {
        static void install(final Map<String, Object> requestMap) {
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            Mockito.when(external.getRequestMap()).thenReturn(requestMap);
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            FacesContext.setCurrentInstance(ctx);
        }

        static void clear() {
            FacesContext.setCurrentInstance(null);
        }
    }

    @Test
    public void elsewhereOnlyAGlobalEditorSearchesEveryone() {
        Assert.assertTrue(ids(as(GLOBAL_EDITOR).completeForContent(FakeData.BOARD_PAGE_KEY, "Ken"))
                .contains(ken.getId().getValue()));
        Assert.assertTrue(as(ACME_EDITOR).completeForContent(FakeData.BOARD_PAGE_KEY, "Paulsen").isEmpty(),
                "an org editor cannot edit the shared page, so its picker answers nothing");
        Assert.assertTrue(as(NOBODY).completeForContent(ACME_BOARD, "").isEmpty());
        Assert.assertTrue(as(GLOBAL_EDITOR).completeForContent(null, "Paulsen").isEmpty(),
                "no section being edited, nothing to offer");
        Assert.assertTrue(as(GLOBAL_EDITOR).completeForContent("Paulsen").isEmpty(),
                "off a view (no editSection) the bean answers nothing");
    }

    @Test
    public void aSaveRechecksThePickedPerson() {
        Assert.assertTrue(as(ACME_EDITOR).saveContent(card(ACME_BOARD, kevin.getId().getValue())),
                "a member of the page's org");
        Assert.assertFalse(as(ACME_EDITOR).saveContent(card(ACME_BOARD, ken.getId().getValue())),
                "a forged id outside the org is refused");
        Assert.assertFalse(as(GLOBAL_EDITOR).saveContent(card(FakeData.BOARD_PAGE_KEY,
                "00000000-0000-4000-8000-00000000dead")), "an id that names nobody");
        Assert.assertTrue(as(GLOBAL_EDITOR).saveContent(card(FakeData.BOARD_PAGE_KEY, ken.getId().getValue())),
                "the shared page may list anyone");
        Assert.assertTrue(as(GLOBAL_EDITOR).saveContent(card(FakeData.BOARD_PAGE_KEY, " ")),
                "blank: a row with no account");
    }

    @Test
    public void aCardMergesTheRowWithTheLiveAccount() {
        final ContentCommands content = as(GLOBAL_EDITOR);
        final ContentInstance row = card(FakeData.BOARD_PAGE_KEY, kevin.getId().getValue());
        Assert.assertEquals(content.personCard(row, false).name(), "Kevin Cardholder");
        Assert.assertEquals(content.personCard(row, false).role(), "Member");
        Assert.assertEquals(content.personCard(row, false).phone(), "(555) 010-0103");
        Assert.assertNull(content.personCard(row, false).accountEmail(), "signed-in only");
        Assert.assertEquals(content.personCard(row, true).accountEmail(), kevin.getEmail());

        Assert.assertNull(content.personCard(null, true).name());
        Assert.assertNull(content.personCard(card(FakeData.BOARD_PAGE_KEY, " "), true).personId(), "no account");
        final ContentInstance dangling = card(FakeData.BOARD_PAGE_KEY, "00000000-0000-4000-8000-00000000beef");
        Assert.assertNull(content.personCard(dangling, true).personId(), "a deleted account is no account");
    }

    @Test
    public void theBoardPageIsSeededLocally() {
        Assert.assertEquals(DAO.getInstance().getContent(FakeData.BOARD_LIST_ID, Cached.NO).orElseThrow()
                .getSection(), FakeData.BOARD_PAGE_KEY);
        Assert.assertEquals(DAO.getInstance().getContent(FakeData.BOARD_PRESIDENT_ID, Cached.NO).orElseThrow()
                .getValues().get(PersonType.PROP_PERSON_ID), FakeData.ADMIN_PERSON_ID);
        Assert.assertEquals(DAO.getInstance().getContent(FakeData.BOARD_NO_ACCOUNT_ID, Cached.NO).orElseThrow()
                .getValues().get(PersonType.PROP_NAME), "Pat Example");
    }

    @Test
    public void thePickerLabelsAPersonOrAStoredId() {
        final PersonCommands people = new PersonCommands();
        Assert.assertEquals(people.pickerLabelFor(kevin), people.pickerLabel(kevin));
        Assert.assertEquals(people.pickerLabelFor(kevin.getId().getValue()), people.pickerLabel(kevin));
        Assert.assertEquals(people.pickerLabelFor("00000000-0000-4000-8000-00000000beef"),
                "00000000-0000-4000-8000-00000000beef", "a dangling id stays visible");
        Assert.assertEquals(people.pickerLabelFor(" "), "");
        Assert.assertEquals(people.pickerLabelFor(null), "");
        Assert.assertEquals(people.pickerLabelFor(42), "");
    }
}
