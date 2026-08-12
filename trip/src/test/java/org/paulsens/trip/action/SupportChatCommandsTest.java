package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The support channel's own authorization and the request flows. Real DAO + in-memory store; the rate limiter
 * is stubbed to always allow (its own tests cover throttling), mail is a mock so fan-out recipients can be
 * asserted, and callers are built directly (no FacesContext in tests).
 */
public class SupportChatCommandsTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ read authorization

    @Test
    public void onlyJoinedMembersAndChatAdminsMayRead() {
        final Person stranger = savedPerson("s");
        final Person member = savedPerson("m");
        final SupportChatCommands commands = supportFor(member, false);
        commands.ensureSupportChannel(AuditActor.system());

        assertFalse(commands.canReadSupport(callerFor(stranger, false)), "No row, no privilege: no access");
        assertFalse(commands.canReadSupport(new Caller(null, false, null, grantsNothing())),
                "Unauthenticated must be refused");

        dao.saveChatMembership(ChatMembership.joining(
                ChatChannel.Id.forSupport(), member.getId(), java.time.Instant.now()));
        assertTrue(commands.canReadSupport(callerFor(member, false)), "A JOINED row grants access");

        final PrivilegeCommands chatAdmin = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(chatAdmin.check(Mockito.eq("chatAdmin"), Mockito.any(), Mockito.any()))
                .thenReturn(true);
        assertTrue(commands.canReadSupport(
                new Caller(stranger.getId(), false, actorOf(stranger), chatAdmin)),
                "Global chatAdmin reads without a row");
    }

    // ------------------------------------------------------------------ admin-list management

    @Test
    public void addAndRemoveAdminAreConfigAdminGatedAndDriveTheList() {
        final Person target = savedPerson("t");
        final Person nobody = savedPerson("n");

        final SupportChatCommands notAdmin = supportFor(nobody, false);
        assertFalse(notAdmin.addAdmin(target.getId()), "addAdmin requires configAdmin");

        final SupportChatCommands admin = supportFor(nobody, true);
        assertTrue(admin.addAdmin(target.getId()));
        assertTrue(admin.listAdmins().stream().anyMatch(p -> p.getId().equals(target.getId())),
                "The added admin appears in the list");
        assertTrue(admin.canReadSupport(callerFor(target, false)), "...and can now read");

        assertTrue(admin.removeAdmin(target.getId()));
        assertFalse(admin.listAdmins().stream().anyMatch(p -> p.getId().equals(target.getId())),
                "A removed admin leaves the list");
        assertFalse(admin.canReadSupport(callerFor(target, false)), "...and loses access");
        assertTrue(admin.removeAdmin(target.getId()), "Removing an absent admin is a no-op, not an error");
    }

    // ------------------------------------------------------------------ removal requests

    @Test
    public void aRemovalRequestValidatesFamilyAndManagerAndStoresTheMessage() throws IOException {
        final Person owner = familyOwnerWithChild("child-a");
        final Person child = childOf(owner);
        final SupportChatCommands commands = supportFor(owner, false);

        // A stranger's id: refused.
        final Person stranger = savedPerson("x");
        assertFalse(commands.fileRemovalRequest(stranger.getId(), "please"),
                "Only your own family members can be the subject");

        // The child, requested by the manager: stored, marker + @all + admin link in the body.
        assertTrue(commands.fileRemovalRequest(child.getId(), "Left the parish"));
        final List<SupportChatCommands.SupportMessage> messages = asReader().recentMessages(10);
        assertFalse(messages.isEmpty());
        final String body = messages.get(0).body();
        assertTrue(body.startsWith(SupportChatCommands.REMOVAL_MARKER));
        assertTrue(body.contains("@all"));
        assertTrue(body.contains("Left the parish"));
        assertTrue(body.contains(child.getId().getValue()), "The admin link names the target id");

        // The same request again inside the cooldown window: refused.
        assertFalse(commands.fileRemovalRequest(child.getId(), "again"),
                "One open request per kind per person");
    }

    @Test
    public void aNonManagerMemberCannotFileARemovalRequest() throws IOException {
        final Person owner = familyOwnerWithChild("child-b");
        final Person child = childOf(owner);
        final SupportChatCommands asChild = supportFor(child, false);
        assertFalse(asChild.fileRemovalRequest(owner.getId(), "revenge"),
                "A non-manager member must be refused");
    }

    // ------------------------------------------------------------------ limit requests

    @Test
    public void aLimitRequestRequiresActuallyBeingAtTheLimit() throws IOException {
        final Person owner = familyOwnerWithChild("child-c");
        final SupportChatCommands notAtLimit = supportFor(owner, false);
        assertFalse(notAtLimit.fileLimitRequest("more please"), "Below the limit: refused");

        final ConfigCommands tiny = Mockito.mock(ConfigCommands.class);
        Mockito.when(tiny.getInt(org.paulsens.trip.config.KnownSettings.FAMILY_MAX_MEMBERS, 1, 100))
                .thenReturn(2);     // family is owner + child = 2 -> at the limit
        Mockito.when(tiny.getBoolean(org.paulsens.trip.config.KnownSettings.SUPPORT_MAIL_ENABLED))
                .thenReturn(false); // keep this test about the request, not the mail
        Mockito.when(tiny.getString(Mockito.any(org.paulsens.trip.model.SettingDef.class)))
                .thenReturn("http://test");
        final SupportChatCommands atLimit = new SupportChatCommands(
                allowingLimiter(), tiny, Mockito.mock(MailCommands.class), () -> callerFor(owner, false));
        assertTrue(atLimit.fileLimitRequest("we are six"));
        assertTrue(asReader().recentMessages(10).get(0).body()
                .startsWith(SupportChatCommands.LIMIT_MARKER));
    }

    // ------------------------------------------------------------------ mail fan-out

    @Test
    public void requestMailGoesToJoinedAdminsWithUsableEmailsExceptTheRequester() throws IOException {
        final Person owner = familyOwnerWithChild("child-d");
        final Person child = childOf(owner);

        final Person mailed = savedPerson("adm1");          // valid email (savedPerson sets one)
        final Person noEmail = savedPerson("adm2");
        noEmail.setEmail(null);
        assertTrue(dao.savePerson(noEmail));
        final SupportChatCommands admin = supportFor(owner, true);
        assertTrue(admin.addAdmin(mailed.getId()));
        assertTrue(admin.addAdmin(noEmail.getId()));
        assertTrue(admin.addAdmin(owner.getId()));          // the requester is also an admin -- not mailed

        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.formatEmail(Mockito.any()))
                .thenAnswer(inv -> ((Person) inv.getArgument(0)).getEmail());
        final SupportChatCommands commands = new SupportChatCommands(
                allowingLimiter(), new ConfigCommands(), mail, () -> callerFor(owner, false));
        assertTrue(commands.fileRemovalRequest(child.getId(), "details"));

        // Delivery is off-thread; verify with a timeout. Exactly ONE recipient qualifies.
        Mockito.verify(mail, Mockito.timeout(3000).times(1)).send(
                Mockito.any(), Mockito.eq(mailed.getEmail()), Mockito.any(), Mockito.any(),
                Mockito.contains("Support request"), Mockito.contains("removed from their family"),
                Mockito.any());
        Mockito.verify(mail, Mockito.after(500).never()).send(
                Mockito.any(), Mockito.eq(owner.getEmail()), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ------------------------------------------------------------------ replies

    @Test
    public void repliesRequireReadAccessAndStore() {
        final Person adminPerson = savedPerson("rep");
        final Person stranger = savedPerson("str");
        final SupportChatCommands granter = supportFor(adminPerson, true);
        assertTrue(granter.addAdmin(adminPerson.getId()));

        assertFalse(supportFor(stranger, false).postReply("hi"), "A non-reader cannot post");
        assertFalse(supportFor(adminPerson, false).postReply("  "), "Blank replies are refused");
        assertTrue(supportFor(adminPerson, false).postReply("We got your request."));
        assertEquals(asReader().recentMessages(5).get(0).body(), "We got your request.");
        assertTrue(supportFor(stranger, false).recentMessages(5).isEmpty(),
                "A non-reader sees no messages, whatever the page does");
    }

    @Test
    public void aRateLimitedCallerIsRefusedForRequestsAndReplies() throws IOException {
        final Person owner = familyOwnerWithChild("child-e");
        final Person child = childOf(owner);
        final ChatRateLimiter denying = Mockito.mock(ChatRateLimiter.class);
        Mockito.when(denying.check(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(ChatRateLimiter.Decision.deny("burst", 30));
        final SupportChatCommands commands = new SupportChatCommands(denying, new ConfigCommands(),
                Mockito.mock(MailCommands.class), () -> callerFor(owner, true));
        assertFalse(commands.fileRemovalRequest(child.getId(), "x"), "Rate-limited request refused");
        assertTrue(commands.addAdmin(owner.getId()));
        assertFalse(commands.postReply("hello"), "Rate-limited reply refused");
    }

    @Test
    public void anonymousCallersCannotFileAnything() {
        final SupportChatCommands anonymous = new SupportChatCommands(allowingLimiter(),
                new ConfigCommands(), Mockito.mock(MailCommands.class),
                () -> new Caller(null, false, null, grantsNothing()));
        assertFalse(anonymous.fileRemovalRequest(Person.Id.from("whoever"), "x"));
        assertFalse(anonymous.fileLimitRequest("x"));
    }

    @Test
    public void reAddingARemovedAdminRejoinsTheExistingRow() {
        final Person target = savedPerson("rejoin");
        final SupportChatCommands admin = supportFor(savedPerson("boss"), true);
        assertTrue(admin.addAdmin(target.getId()));
        assertTrue(admin.removeAdmin(target.getId()));
        assertTrue(admin.addAdmin(target.getId()), "Re-adding flips the REMOVED row back to JOINED");
        assertTrue(admin.canReadSupport(callerFor(target, false)));
    }

    @Test
    public void overlongDetailsAreTruncated() throws IOException {
        final Person owner = familyOwnerWithChild("child-f");
        final Person child = childOf(owner);
        final String details = "x".repeat(2500) + "ENDMARKER";
        assertTrue(supportFor(owner, false).fileRemovalRequest(child.getId(), details));
        final String body = asReader().recentMessages(5).get(0).body();
        assertFalse(body.contains("ENDMARKER"), "Details beyond the cap are dropped");
    }

    // ------------------------------------------------------------------ helpers

    /** An owner + one clean child member, built through the real FamilyCommands. */
    private Person familyOwnerWithChild(final String childName) throws IOException {
        final Person owner = savedPerson("own");
        final FamilyCommands family = new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> callerFor(owner, false));
        assertNotNull(family.createFamilyMember(childName, "Test", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        return owner;
    }

    private Person childOf(final Person owner) {
        final Person reloaded = dao.getPerson(owner.getId()).orElseThrow();
        final Person.Id childId = reloaded.getManagedUsers().get(reloaded.getManagedUsers().size() - 1);
        return dao.getPerson(childId).orElseThrow();
    }

    private Person savedPerson(final String tag) {
        final Person person = Person.builder()
                .first(tag + RandomData.genAlpha(4)).last(RandomData.genAlpha(6))
                .email(tag + "." + RandomData.genAlpha(8) + "@example.com")
                .build();
        try {
            assertTrue(dao.savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    // ------------------------------------------------------------------ email-conflict requests

    @Test
    public void anEmailConflictRequestIsScopedToProfilesTheAskerManages() throws IOException {
        final Person owner = familyOwnerWithChild("conflict-a");
        final Person child = childOf(owner);
        final SupportChatCommands commands = supportFor(owner, false);
        final String address = "shared." + RandomData.genAlpha(6) + "@example.com";

        final Person stranger = savedPerson("cx");
        assertFalse(commands.fileEmailConflictRequest(stranger.getId(), address, "help"),
                "You can only ask about a profile you manage");
        assertFalse(commands.fileEmailConflictRequest(child.getId(), "foo", "help"),
                "There is nothing to resolve without a real address");

        assertTrue(commands.fileEmailConflictRequest(child.getId(), address, "We share one mailbox"));
        final String body = asReader().recentMessages(10).get(0).body();
        assertTrue(body.startsWith(SupportChatCommands.EMAIL_CONFLICT_MARKER));
        assertTrue(body.contains("@all"), "Channel admins are summoned like every other request");
        assertTrue(body.contains(address), "The address in question is what the admin needs");
        assertTrue(body.contains("We share one mailbox"));
        assertTrue(body.contains(child.getId().getValue()), "The admin link names the subject");

        assertFalse(commands.fileEmailConflictRequest(child.getId(), address, "again"),
                "One open request per kind per person");
    }

    /**
     * The request must not become a directory lookup: it says an address is taken, never who holds it.
     * The asker only ever sees what the profile page already told them.
     */
    @Test
    public void anEmailConflictRequestDoesNotNameTheOtherAccountHolder() throws IOException {
        final String address = "held." + RandomData.genAlpha(6) + "@example.com";
        final Person holder = savedPerson("holder");
        holder.setEmail(address);
        assertTrue(dao.savePerson(holder));

        final Person asker = savedPerson("asker");
        assertTrue(supportFor(asker, false).fileEmailConflictRequest(asker.getId(), address, null));
        final String body = asReader().recentMessages(10).get(0).body();
        assertFalse(body.contains(holder.getId().getValue()),
                "The other account's id must not ride along: " + body);
    }

    private SupportChatCommands supportFor(final Person person, final boolean siteAdmin) {
        return new SupportChatCommands(allowingLimiter(), new ConfigCommands(),
                Mockito.mock(MailCommands.class), () -> callerFor(person, siteAdmin));
    }

    /** A site-admin caller (chatAdmin via the has() short-circuit) for reading messages back in asserts. */
    private SupportChatCommands asReader() {
        final Person reader = savedPerson("rdr");
        return supportFor(reader, true);
    }

    private Caller callerFor(final Person person, final boolean siteAdmin) {
        return new Caller(person.getId(), siteAdmin, actorOf(person), grantsNothing());
    }

    private static AuditActor actorOf(final Person person) {
        return new AuditActor(person.getEmail(), person.getId().getValue());
    }

    private static ChatRateLimiter allowingLimiter() {
        final ChatRateLimiter limiter = Mockito.mock(ChatRateLimiter.class);
        Mockito.when(limiter.check(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(ChatRateLimiter.Decision.allow());
        return limiter;
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }
}
