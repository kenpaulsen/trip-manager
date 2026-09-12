package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.FamilyCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.CreateFamilyMemberRequest;
import org.paulsens.trip.api.dto.FamilyDto;
import org.paulsens.trip.api.dto.FamilyMemberDto;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link FamilyResource} against the REAL {@code FamilyCommands} and in-memory store (the family rules are
 * a security boundary and are never mocked). What is pinned is the edge: who may see whose family, which
 * bean refusal becomes which status and wire code, and that every answer is the family AFTER the write.
 */
public class FamilyResourceTest extends ResourceTestSupport {

    @BeforeMethod
    public void bindBeans() {
        bind(PersonCommands.class, new PersonCommands());
    }

    private static Person somebody(final String first, final String email) {
        final PersonCommands people = new PersonCommands();
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Familytester");
        person.setEmail(email);
        Assert.assertTrue(people.savePerson(person));
        return person;
    }

    private static String unique() {
        return RandomData.genAlpha(10);
    }

    private static CreateFamilyMemberRequest member(final String first, final String sex, final String email,
            final boolean manager) {
        return new CreateFamilyMemberRequest(first, "Familytester", LocalDate.of(2012, 3, 4), sex, email, manager,
                null);
    }

    private FamilyResource as(final Person person) {
        signedInAs(person.getId());
        return resource(new FamilyResource());
    }

    private FamilyDto familyOf(final Response response) {
        assertOk(response);
        return (FamilyDto) response.getEntity();
    }

    private FamilyDto familyIn(final Response response) {
        assertOk(response);
        return (FamilyDto) ((Map<?, ?>) response.getEntity()).get("family");
    }

    @Test
    public void nobodyHasAFamilyUntilTheFirstMemberIsAdded() {
        final Person owner = somebody("Own", "own." + unique() + "@example.com");
        final FamilyResource mine = as(owner);

        final FamilyDto alone = familyOf(mine.family(null));
        Assert.assertNull(alone.id());
        Assert.assertTrue(alone.canManage(), "the first add forms the family, so the affordance shows");
        Assert.assertEquals(alone.members().size(), 1);
        Assert.assertEquals(alone.members().get(0).id(), owner.getId().getValue());
        Assert.assertFalse(alone.members().get(0).manager());
        Assert.assertNotNull(alone.members().get(0).person(), "the member record rides along, redacted");
        Assert.assertTrue(alone.members().get(0).missingProfileFields().contains("Passport number"));

        final Response added = mine.addMember(CSRF_OK, member("Kid", "male", null, false));
        assertOk(added);
        final FamilyMemberDto kid = (FamilyMemberDto) ((Map<?, ?>) added.getEntity()).get("member");
        Assert.assertEquals(kid.first(), "Kid");
        Assert.assertFalse(kid.manager());
        Assert.assertNull(kid.deleteBlockReason(), "a fresh member has no history: deletable");
        final FamilyDto family = familyIn(added);
        Assert.assertNotNull(family.id());
        Assert.assertEquals(family.memberIds().size(), 2);
        Assert.assertEquals(family.managerIds(), List.of(owner.getId().getValue()));
        Assert.assertTrue(family.canManage());
        Assert.assertEquals(family.members().get(0).id(), owner.getId().getValue(), "the caller comes first");
        Assert.assertTrue(family.members().get(0).manager());

        // The GET agrees, and a plain member sees the same household without the manager's reach.
        Assert.assertEquals(familyOf(mine.family(null)).id(), family.id());
        final FamilyDto asKid = familyOf(as(personOf(kid)).family(null));
        Assert.assertEquals(asKid.id(), family.id());
        Assert.assertFalse(asKid.canManage());
        Assert.assertNull(asKid.members().get(1).deleteBlockReason(), "no reach, no delete answer computed");
    }

    private static Person personOf(final FamilyMemberDto member) {
        final Person person = new Person();
        person.setId(Person.Id.from(member.id()));
        return person;
    }

    @Test
    public void addMemberMapsTheBeansRefusalsOntoStatuses() {
        final Person owner = somebody("Own", "own." + unique() + "@example.com");
        final Person taken = somebody("Taken", "taken." + unique() + "@example.com");
        final FamilyResource mine = as(owner);

        assertError(mine.addMember(null, member("Kid", "Male", null, false)), 403, ApiErrors.CSRF);
        assertError(mine.addMember(CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(mine.addMember(CSRF_OK, member("Kid", "Other", null, false)), 422, ApiErrors.VALIDATION_FAILED);
        assertError(mine.addMember(CSRF_OK, member("Kid", "", null, false)), 422,
                FamilyCommands.FamilyResult.SEX_REQUIRED);
        assertError(mine.addMember(CSRF_OK, new CreateFamilyMemberRequest("Kid", "F", LocalDate.now().plusDays(1),
                "Male", null, false, null)), 422, FamilyCommands.FamilyResult.BIRTHDATE_FUTURE);
        assertError(mine.addMember(CSRF_OK, member("Kid", "Male", null, true)), 422,
                FamilyCommands.FamilyResult.MANAGER_NEEDS_EMAIL);
        assertError(mine.addMember(CSRF_OK, member("Kid", "Male", taken.getEmail(), false)), 409,
                FamilyCommands.FamilyResult.EMAIL_IN_USE);
        assertError(mine.addMember(CSRF_OK, new CreateFamilyMemberRequest("Kid", "F", LocalDate.of(2012, 1, 1),
                "Male", null, false, taken.getId().getValue())), 403, ApiErrors.FORBIDDEN);
        // The bean forms the (empty) family row before the email check -- its order, not this edge's -- so
        // what every refusal guarantees is that no member was added.
        Assert.assertEquals(familyOf(mine.family(null)).members().size(), 1, "every refusal added nobody");

        // A plain member may not add; a manager acting FOR a member grows that member's family (their own).
        final Response added = mine.addMember(CSRF_OK, member("Teen", "Female", "teen." + unique() + "@x.com", false));
        final FamilyMemberDto teen = (FamilyMemberDto) ((Map<?, ?>) added.getEntity()).get("member");
        assertError(as(personOf(teen)).addMember(CSRF_OK, member("Pal", "Male", null, false)), 403,
                FamilyCommands.FamilyResult.NOT_MANAGER);
        final FamilyDto grown = familyIn(as(owner).addMember(CSRF_OK, new CreateFamilyMemberRequest("Baby", "F",
                LocalDate.of(2020, 1, 1), "Female", null, false, teen.id())));
        Assert.assertEquals(grown.memberIds().size(), 3);
    }

    @Test
    public void familyReadsFollowTheActForRule() {
        final Person owner = somebody("Own", "own." + unique() + "@example.com");
        final Person stranger = somebody("Stranger", "str." + unique() + "@example.com");
        final Person admin = somebody("Admin", "adm." + unique() + "@example.com");
        final FamilyResource mine = as(owner);
        final FamilyMemberDto kid = (FamilyMemberDto) ((Map<?, ?>) mine.addMember(CSRF_OK,
                member("Kid", "Male", null, false)).getEntity()).get("member");

        Assert.assertEquals(familyOf(mine.family(kid.id())).members().size(), 2, "a manager may look via a member");
        assertError(mine.family(stranger.getId().getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(as(stranger).family(kid.id()), 403, ApiErrors.FORBIDDEN);

        signedInAsSiteAdmin(admin.getId());
        final FamilyResource asAdmin = resource(new FamilyResource());
        final FamilyDto seen = familyOf(asAdmin.family(kid.id()));
        Assert.assertEquals(seen.members().size(), 2);
        Assert.assertTrue(seen.canManage(), "a site admin manages any household");
        assertThrown(() -> asAdmin.family("no-such-person"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void managerAndDeleteWritesAnswerTheFamilyAfterTheChange() {
        final Person owner = somebody("Own", "own." + unique() + "@example.com");
        final FamilyResource mine = as(owner);
        final FamilyMemberDto kid = (FamilyMemberDto) ((Map<?, ?>) mine.addMember(CSRF_OK,
                member("Kid", "Male", null, false)).getEntity()).get("member");
        final FamilyMemberDto spouse = (FamilyMemberDto) ((Map<?, ?>) mine.addMember(CSRF_OK,
                member("Spouse", "Female", "sp." + unique() + "@example.com", false)).getEntity()).get("member");

        assertError(mine.setManager(kid.id(), null, Map.of("manager", true)), 403, ApiErrors.CSRF);
        assertError(mine.setManager(kid.id(), CSRF_OK, Map.of("manager", "yes")), 400, ApiErrors.VALIDATION_FAILED);
        assertError(mine.setManager(kid.id(), CSRF_OK, Map.of("manager", true)), 422,
                FamilyCommands.FamilyResult.MANAGER_NEEDS_EMAIL);
        assertError(mine.setManager(owner.getId().getValue(), CSRF_OK, Map.of("manager", false)), 422,
                FamilyCommands.FamilyResult.LAST_MANAGER);
        assertError(mine.setManager("no-such-person", CSRF_OK, Map.of("manager", true)), 404, ApiErrors.NOT_FOUND);
        assertError(as(personOf(kid)).setManager(spouse.id(), CSRF_OK, Map.of("manager", true)), 403,
                FamilyCommands.FamilyResult.NOT_MANAGER);

        final FamilyDto promoted = familyOf(as(owner).setManager(spouse.id(), CSRF_OK, Map.of("manager", true)));
        Assert.assertTrue(promoted.managerIds().contains(spouse.id()));
        Assert.assertEquals(promoted.version(), familyOf(mine.family(null)).version(),
                "the answer is the row just written, which the store now agrees with");

        assertError(mine.deleteMember(kid.id(), null), 403, ApiErrors.CSRF);
        assertError(mine.deleteMember(owner.getId().getValue(), CSRF_OK), 422,
                FamilyCommands.FamilyResult.CANNOT_DELETE_SELF);
        assertError(mine.deleteMember("no-such-person", CSRF_OK), 404, ApiErrors.NOT_FOUND);
        final Response deleted = mine.deleteMember(kid.id(), CSRF_OK);
        assertOk(deleted);
        Assert.assertEquals(((Map<?, ?>) deleted.getEntity()).get("deleted"), true);
        final FamilyDto after = (FamilyDto) ((Map<?, ?>) deleted.getEntity()).get("family");
        Assert.assertFalse(after.memberIds().contains(kid.id()));
        Assert.assertEquals(after.members().size(), 2);
    }
}
