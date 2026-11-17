package org.paulsens.trip.action;

import java.util.HashMap;
import java.util.List;
import java.util.function.BiPredicate;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The mail merge's rules and rendering ({@link MailMerge}). It evaluated its text as EL until medjugorje#79;
 * now an expression is refused, every field is checked against what its sender may use, and values come
 * from Java. The class owns its trip (in the seeded trip's organization) and its traveler, whose room is 301:
 * the shared seed's travelers are changed by other classes running in the same suite.
 */
public class MailMergeTest {
    private String trip;
    private Person traveler;
    private Person admin;

    @BeforeClass
    void beforeClass() throws java.io.IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final DAO dao = DAO.getInstance();
        admin = dao.getPerson(Person.Id.from(FakeData.ADMIN_PERSON_ID), Cached.NO).orElseThrow();
        traveler = Person.builder().first("Tara").last("Merge")
                .email("merge." + java.util.UUID.randomUUID() + "@example.com").build();
        Assert.assertTrue(dao.savePerson(traveler));
        final org.paulsens.trip.model.Trip owned = org.paulsens.trip.model.Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Merge test trip")
                .startDate(java.time.LocalDateTime.now().plusDays(30))
                .endDate(java.time.LocalDateTime.now().plusDays(40))
                .people(List.of(traveler.getId())).build();
        owned.setOrgId(dao.getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow().getOrgId());
        Assert.assertTrue(dao.saveTrip(owned));
        trip = owned.getId();
        Assert.assertTrue(new RegistrationCommands(this::siteAdmin).saveRoom(trip, traveler.getId(), "301"));
    }

    private Caller siteAdmin() {
        return new Caller(admin.getId(), true, AuditActor.from(null), new PrivilegeCommands());
    }

    /** A signed-in non-admin holding exactly the privileges {@code holds} answers for (name, scope). */
    private static Caller holding(final BiPredicate<String, String> holds) {
        final Caller caller = Mockito.mock(Caller.class);
        Mockito.when(caller.isAuthenticated()).thenReturn(true);
        Mockito.when(caller.isSiteAdmin()).thenReturn(false);
        Mockito.when(caller.personId()).thenReturn(Person.Id.newInstance());
        Mockito.when(caller.has(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(call -> holds.test(call.getArgument(0), call.getArgument(1)));
        Mockito.when(caller.has(ArgumentMatchers.anyString())).thenReturn(false);
        return caller;
    }

    private static MailMerge merge(final Caller caller, final MailCommands mail) {
        return new MailMerge(() -> caller, () -> mail);
    }

    private MailMerge asAdmin() {
        return merge(siteAdmin(), new MailCommands());
    }

    @Test
    public void textIsCheckedBeforeAnythingRenders() {
        final MailMerge merge = asAdmin();

        Assert.assertEquals(merge.problem("Hi", "#{Runtime.getRuntime().exec('x')}", null), MailMerge.EXPRESSION);
        Assert.assertEquals(merge.problem("${7*7}", "Hi", null), MailMerge.EXPRESSION);
        Assert.assertTrue(merge.problem("Hi", "{{nope}} {{firstName}}", null).contains("{{nope}}"));
        Assert.assertTrue(merge.problem("{{tripTitle}}", "Room {{room}}", null).contains("{{tripTitle}}, {{room}}"),
                "a trip field needs a trip");
        Assert.assertNull(merge.problem("{{tripTitle}}", "{{room}} {{city}} {{balanceDue}} {{passportIssue}}", trip),
                "a site admin may use every field");
        Assert.assertNull(merge.problem("Hello", "Hi {{firstName}}", null));
    }

    /** An org emailAdmin who does not manage the trip: trip facts yes, addresses, passport and money no. */
    @Test
    public void gatedFieldsFollowTheTripAdminAndMoneyRules() {
        final MailMerge mailer = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)),
                new MailCommands());

        Assert.assertNull(mailer.problem("S", "{{tripTitle}} {{room}}", trip));
        Assert.assertTrue(mailer.problem("S", "{{city}}", trip).startsWith("Only a site administrator"));
        Assert.assertTrue(mailer.problem("S", "{{passportIssue}}", trip).contains("{{passportIssue}}"));
        Assert.assertTrue(mailer.problem("S", "{{balanceDue}}", trip).contains("finances"));

        final MailMerge manager = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)
                || (PrivilegeCommands.TRIP_MGR.equals(name) && trip.equals(scope))), new MailCommands());
        Assert.assertNull(manager.problem("S", "{{city}} {{passportIssue}} {{balanceDue}}", trip));
        Assert.assertTrue(manager.problem("S", "{{city}}", null).startsWith("Only a site administrator"),
                "a trip manager's address fields need their trip chosen");
    }

    private static List<MergeText.Kind> listed(final MailMerge merge, final String tripId) {
        return merge.fieldGroupsFor(tripId).stream().map(MergeText.Group::kind).toList();
    }

    /** The page lists only what its sender may use, by the rule the check applies; trip fields before a trip. */
    @Test
    public void theListOffersOnlyFieldsTheSenderMayUse() {
        final List<MergeText.Kind> every = List.of(MergeText.Kind.values());
        final List<MergeText.Kind> basic = List.of(MergeText.Kind.PERSON, MergeText.Kind.TRIP, MergeText.Kind.ON_TRIP);
        Assert.assertEquals(listed(asAdmin(), null), every);

        final MailMerge mailer = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)),
                new MailCommands());
        Assert.assertEquals(listed(mailer, trip), basic, "no address, passport or money for a plain emailAdmin");

        final MailMerge manager = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)
                || (PrivilegeCommands.TRIP_MGR.equals(name) && trip.equals(scope))), new MailCommands());
        Assert.assertEquals(listed(manager, trip), every, "the trip's manager may use every field about it");
        Assert.assertEquals(listed(manager, null), basic, "until their trip is chosen");
    }

    /** Filtering the To search by a trip fills an empty Trip row, and never replaces one the author chose. */
    @Test
    public void aRosterPickFillsOnlyAnEmptyTripRow() {
        final MailMerge merge = asAdmin();
        Assert.assertEquals(merge.tripAfterRosterPick(null, "b"), "b");
        Assert.assertEquals(merge.tripAfterRosterPick("", "b"), "b");
        Assert.assertEquals(merge.tripAfterRosterPick("a", "b"), "a");
    }

    @Test
    public void renderFillsTheRoomFromTheReservation() {
        final MailMerge.Preview got = asAdmin().render(traveler, "{{tripTitle}} & you", "Room {{room}}, {{firstName}}",
                trip);

        Assert.assertEquals(got.body(), "Room 301, " + MailTemplatesEscape.of(traveler.getPreferredName()));
        Assert.assertTrue(got.subject().endsWith(" & you"), "a subject is plain text");
        Assert.assertThrows(IllegalArgumentException.class, () -> asAdmin().render(traveler, "S", "#{x}", trip));
    }

    @Test
    public void previewIsForTheFirstRecipientOrElseTheSender() {
        final MailMerge merge = asAdmin();

        Assert.assertEquals(merge.preview(List.of(), "For {{firstName}}", "B", null).subject(),
                "For " + admin.getPreferredName());
        Assert.assertEquals(merge.preview(List.of("Tara M <" + traveler.getEmail() + ">", "nobody@nowhere.example"),
                "S", "Room {{room}}", trip).body(), "Room 301");
        Assert.assertNull(merge.preview(List.of(), "S", "#{to.first}", null), "an expression is refused");
    }

    @Test
    public void previewRefusesWhatTheSenderMayNotSee() {
        Assert.assertNull(merge(holding((name, scope) -> false), new MailCommands()).preview(List.of(), "S", "B", null),
                "no emailAdmin anywhere, no preview");
        final MailMerge mailer = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)),
                new MailCommands());
        Assert.assertNull(mailer.preview(List.of("nobody@nowhere.example"), "S", "B", null),
                "the first recipient must be someone the sender may mail");
        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertNull(
                asAdmin().preview(List.of("bob@example"), "S", "B", null))),
                List.of("Cannot preview: The first recipient has no usable address."),
                "a site admin's typo is refused in the send's words, not as nobody to preview");
        Assert.assertNull(mailer.preview(List.of(), "S", "B", null), "a caller with no person row has no preview");
    }

    @Test
    public void sendPersonalizesEachMessageAndRefusesBadText() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.emailsToPeople(ArgumentMatchers.anyList())).thenReturn(List.of(traveler));
        final MailMerge merge = merge(siteAdmin(), mail);

        Assert.assertFalse(merge.send("no-reply@example.com", List.of(traveler.getEmail()), null, "r@example.com", "S",
                "{{nope}}", null));
        Mockito.verifyNoInteractions(mail);
        Assert.assertTrue(merge.send("no-reply@example.com", List.of(traveler.getEmail()), null, "r@example.com",
                "Hi {{firstName}}", "Room {{room}}", trip));
        Mockito.verify(mail).sendMerged("no-reply@example.com", null, "r@example.com",
                List.of(new MailCommands.Merged(traveler, "Hi " + traveler.getPreferredName(), "Room 301")));
        Assert.assertFalse(merge(holding((name, scope) -> false), mail).send("no-reply@example.com",
                List.of(traveler.getEmail()), null, "r", "S", "B", null), "no emailAdmin anywhere, no sending");
    }

    /** A typo is skipped as unusable, not as an outsider, and a list of nothing but typos sends nothing. */
    @Test
    public void unusableAddressesAreSkippedAndNeverMailed() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.emailsToPeople(ArgumentMatchers.anyList())).thenReturn(List.of(traveler));
        final MailMerge merge = merge(siteAdmin(), mail);

        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertFalse(merge.send(
                "no-reply@example.com", List.of("bob@example", "joe smith"), null, "r", "S", "B", null))),
                List.of("2 unusable address(es) were skipped.", "Nobody to mail: No recipient has a usable address."),
                "a site admin has no organization, so nothing may read as an outsider");
        Mockito.verifyNoInteractions(mail);
        Assert.assertTrue(merge.send("no-reply@example.com", List.of("bob@example", traveler.getEmail()), null, "r",
                "S", "B", null));
        Mockito.verify(mail).emailsToPeople(List.of(traveler.getEmail()));
    }

    /** An org-bounded sender is told each reason with its own count, and Preview uses the same words. */
    @Test
    public void typosAndOutsidersAreCountedApart() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final MailMerge mailer = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)), mail);

        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertFalse(mailer.send(
                "no-reply@example.com", List.of("bob@example", "nobody@nowhere.example"), null, "r", "S", "B",
                null))), List.of("1 unusable address(es) were skipped.",
                "1 recipient(s) outside your organization(s) were skipped.",
                "Nobody to mail: No recipients are within your organization(s)."));
        Mockito.verifyNoInteractions(mail);
        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertNull(
                mailer.preview(List.of("bob@example"), "S", "B", null))),
                List.of("Cannot preview: The first recipient has no usable address."));
        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertNull(
                mailer.preview(List.of("nobody@nowhere.example"), "S", "B", null))),
                List.of("Cannot preview: The first recipient is outside your organization(s)."));
    }

    /** {@code MailTemplates.escape}, named for what the assertion means. */
    private static final class MailTemplatesEscape {
        static String of(final String text) {
            return org.paulsens.trip.chat.MailTemplates.escape(text);
        }
    }
}
