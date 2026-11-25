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

    /**
     * The fields checked are the ones FILLED: cleaning a body can make a field (encoded braces restored, a comment or
     * an unlisted tag dropped between two braces), and a field the check never saw would be filled for every
     * recipient (review blocker, 2026-11-23). Each such body is refused like the plain field, at every door.
     */
    @Test
    public void aFieldTheCleanerWouldMakeIsCheckedLikeOneTyped() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.emailsToPeople(ArgumentMatchers.anyList())).thenReturn(List.of(traveler));
        Mockito.when(mail.findPersonByEmail(ArgumentMatchers.anyString())).thenReturn(traveler);
        final MailMerge mailer = merge(holding((name, scope) -> PrivilegeCommands.EMAIL_ADMIN.equals(name)), mail);
        for (final String hidden : List.of("<p>{<!-- -->{cell}}</p>", "<p>{<x>{street}}</p>",
                "<p>%7B%7BbalanceDue%7D%7D</p>", "<p>{<form>{passportIssue}}</p>", "<p>%7B%7BtirpLink%7D%7D</p>",
                "<p><iframe src=\"https://www.youtube.com/embed/abc\"></iframe></p>")) {
            Assert.assertNotNull(mailer.problem("S", hidden, trip), hidden);
            Assert.assertNull(mailer.render(traveler, "S", hidden, trip).preview(), hidden);
            Assert.assertNull(mailer.preview(List.of(traveler.getEmail()), "S", hidden, trip), hidden);
            Assert.assertFalse(mailer.send("no-reply@example.com", List.of(traveler.getEmail()), null, "r", "S",
                    hidden, trip), hidden);
        }
        Mockito.verify(mail, Mockito.never()).sendMerged(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyList());
        Assert.assertEquals(mailer.problem("S", "<p><iframe src=\"https://www.youtube.com/embed/abc\"></iframe></p>",
                trip), "Write the message.", "a body that cleans to nothing is refused, never mailed empty (F5)");
        Assert.assertNull(mailer.problem("S", "<p><img src=\"https://media.example.com/flyer.png\"></p>", trip),
                "a picture alone is a message");
    }

    /**
     * A body longer than any message may be is refused before it is cleaned, at every door, with the scheduled
     * message's own words (the two share one check).
     */
    @Test
    public void aBodyTooLongIsRefusedUncleanedAtEveryDoor() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final MailMerge merge = merge(siteAdmin(), mail);
        final String huge = "<p>" + "a".repeat(ReminderCommands.MAX_RAW_BODY) + "</p>";
        Assert.assertEquals(merge.problem("S", huge, null), "The message is too long.");
        final MailMerge.Rendered refused = merge.render(traveler, "S", huge, null);
        Assert.assertEquals(refused.problem(), "The message is too long.");
        Assert.assertNull(refused.preview(), "a refused text renders nothing");
        Assert.assertFalse(merge.send("no-reply@example.com", List.of(traveler.getEmail()), null, "r", "S", huge,
                null));
        Mockito.verify(mail, Mockito.never()).sendMerged(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyList());
        final String longCleaned = "<p>" + "a".repeat(ReminderCommands.MAX_BODY + 1) + "</p>";
        Assert.assertEquals(merge.problem("S", longCleaned, null), "The message is too long.",
                "under the raw limit but over a message's cleaned length, as a scheduled message refuses it");
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
                trip).preview();

        Assert.assertEquals(got.body(), "Room 301, " + MailTemplatesEscape.of(traveler.getPreferredName()));
        Assert.assertTrue(got.subject().endsWith(" & you"), "a subject is plain text");
        final MailMerge.Rendered refused = asAdmin().render(traveler, "S", "#{x}", trip);
        Assert.assertEquals(refused.problem(), MailMerge.EXPRESSION);
        Assert.assertNull(refused.preview(), "refused text renders nothing");
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

    /**
     * The body is mailed through the one mail allowlist a scheduled message uses (2026-11-23): a plain color and an
     * image by web address go, a video, a script and a handler never do, and what is left out is said on the preview
     * and on the send. A paste too large to read is refused, as a scheduled message's save refuses it.
     */
    @Test
    public void theBodyIsMailedThroughTheMailAllowlistAndADropIsSaid() {
        final String typed = "<p onclick=\"x()\"><span style=\"color: #c00\">Hi</span> {{firstName}}"
                + "<img src=\"https://media.example.com/a.png\"></p><iframe src=\"https://v.example/1\"></iframe>"
                + "<script>bad()</script>";
        final String mailed = "<p><span style=\"color: #c00\">Hi</span> " + traveler.getPreferredName()
                + "<img src=\"https://media.example.com/a.png\"></p>";
        final String cannotCarry = "a video, a frame, a style other than a color, size, alignment or indent, a link "
                + "that is not a full web, email or phone address, an image that is not a full web address, or a "
                + "field inside a link or image address";
        final String removed = "Some formatting a mail cannot carry (" + cannotCarry + ") is left out of what is sent."
                + " Check the preview.";
        final String removedSent = "Some formatting a mail cannot carry (" + cannotCarry + ") was left out of what was"
                + " sent.";
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.emailsToPeople(ArgumentMatchers.anyList())).thenReturn(List.of(traveler));
        Mockito.when(mail.findPersonByEmail(ArgumentMatchers.anyString())).thenReturn(traveler);
        final MailMerge merge = merge(siteAdmin(), mail);

        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertEquals(merge.preview(
                List.of(traveler.getEmail()), "S", typed, null).body(), mailed)), List.of(removed));
        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertTrue(merge.send(
                "no-reply@example.com", List.of(traveler.getEmail()), null, "r@example.com", "S", typed, null))),
                List.of(removedSent), "past tense on the send: the mail has gone");
        Mockito.verify(mail).sendMerged("no-reply@example.com", null, "r@example.com",
                List.of(new MailCommands.Merged(traveler, "S", mailed)));
        Assert.assertEquals(merge.render(traveler, "S", typed, null).preview().body(), mailed,
                "the REST preview alike");

        final String clean = "<p><span style=\"color: #c00\">Hi</span></p>";
        Assert.assertEquals(TestFaces.growlsOf(new HashMap<>(), () -> merge.preview(
                List.of(traveler.getEmail()), "S", clean, null)), List.of(), "nothing left out, nothing said");
        Assert.assertNotNull(asAdmin().problem("S", "<img src=\"data:image/png;base64," + "A".repeat(3_000)
                + "\">", null), "a pasted image too large to read is refused");
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
