package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.OrgMember;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.security.TokenService;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Self-service account deletion against the real fake-persistence DAO: what goes, what stays, what blocks,
 * and what the organization is told. Mail and profile pictures are the only stand-ins.
 */
public class AccountDeletionCommandsTest {

    private DAO dao;
    private ChatCommands chat;
    private MailCommands mail;
    private ProfilePhotoCommands profilePhotos;
    private PassCommands passes;
    private ConfigCommands config;
    private AccountDeletionCommands deletion;

    @BeforeMethod
    public void setUp() {
        dao = DAO.getInstance();
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        final PhotoChatCommands photoChat = new PhotoChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        mail = Mockito.mock(MailCommands.class);
        profilePhotos = Mockito.mock(ProfilePhotoCommands.class);
        config = Mockito.spy(new ConfigCommands());
        passes = Mockito.mock(PassCommands.class);
        deletion = new AccountDeletionCommands(config, () -> mail, () -> new MailAddressCommands(config),
                () -> chat, () -> photoChat, () -> profilePhotos, () -> passes, TokenService::getInstance);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    public void aSettledPersonIsErasedAndTheOrganizationIsTold() throws IOException {
        final Person me = saved("Settled");
        final Person witness = saved("Witness");
        final Organization org = savedOrg("Pilgrims Inc", "office@example.org", null);
        join(me, org);
        final Trip trip = savedTrip("Past pilgrimage", -30, -20, org, me, witness);
        Assert.assertTrue(dao.saveRegistration(new Registration(trip.getId(), me.getId())
                .withStatus(Registration.Status.CONFIRMED)));
        transaction(me, org, Transaction.TransactionType.Bill, -1000f, "Trip fee");
        transaction(me, org, Transaction.TransactionType.Payment, 1000f, "Paid in full");
        Assert.assertTrue(new BlockListCommands().block(me.getId(), witness.getId()).ok());
        final ChatMessage message = sent(trip, me, "I was here");
        Assert.assertTrue(chat.send(trip.getId(), witness.getId(), "so was I", null, null, actor(witness)).isOk());

        final AccountDeletionCommands.Preview preview = deletion.preview(me.getId());
        Assert.assertFalse(preview.blocked(), "an ended, settled trip does not block: " + preview.reasons());
        Assert.assertEquals(preview.trips().size(), 1);
        Assert.assertTrue(preview.trips().get(0).ended());
        Assert.assertEquals(preview.amountOwedCents(), 0L);
        Assert.assertEquals(preview.contactEmail(), "office@example.org");

        final AccountDeletionCommands.Outcome outcome = deletion.deleteOwnAccount(me.getId(),
                AccountDeletionCommands.CONFIRMATION, actor(me));
        Assert.assertTrue(outcome.ok(), outcome.message());

        // The notice: one per organization, composed before the scrub, with the ledger and a settled line.
        final ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail, Mockito.times(1)).send(ArgumentMatchers.any(), to.capture(), ArgumentMatchers.any(),
                ArgumentMatchers.isNull(), subject.capture(), body.capture(), ArgumentMatchers.any(AuditActor.class));
        Assert.assertEquals(to.getValue(), "office@example.org", "the org contact receives it");
        Assert.assertTrue(subject.getValue().startsWith("Account deleted: Settled "), subject.getValue());
        Assert.assertTrue(subject.getValue().contains(me.getEmail()));
        Assert.assertTrue(body.getValue().contains("Past pilgrimage"));
        Assert.assertTrue(body.getValue().contains("Trip fee"));
        Assert.assertTrue(body.getValue().contains("Balance: settled"), body.getValue());
        Assert.assertTrue(body.getValue().contains("Pilgrims Inc"));

        // Gone: login, profile, chat, block list, org membership, chat membership.
        Mockito.verify(passes).deleteCreds(me.getEmail());
        Assert.assertTrue(dao.getPerson(me.getId(), Cached.NO).isEmpty(), "soft-deleted rows are invisible");
        Assert.assertNull(dao.getPersonByEmail(me.getEmail(), Cached.NO), "email no longer resolves");
        Assert.assertTrue(dao.getPersonDataValues(me.getId(), Cached.NO).isEmpty(), "person data gone");
        Assert.assertTrue(dao.getOrgMember(org.getId(), me.getId(), Cached.NO).isEmpty(), "org membership gone");
        final ChatPage history = chat.history(trip.getId(), witness.getId(), null, 50);
        final ChatMessage after = history.getMessages().stream()
                .filter(m -> m.getId().equals(message.getId())).findFirst().orElseThrow();
        Assert.assertTrue(after.isDeleted(), "their message is tombstoned");
        final ChatMembership membership = dao.getChatMembership(ChatChannel.Id.forTrip(trip.getId()), me.getId(),
                Cached.NO).orElseThrow();
        Assert.assertEquals(membership.getState(), ChatMembership.MemberState.LEFT);
        Mockito.verify(profilePhotos, Mockito.times(ProfilePhotos.MAX_SLOTS))
                .deleteSlotFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt());

        // Kept: the money rows and the registration, under the same id.
        Assert.assertEquals(dao.getTransactions(me.getId(), Cached.NO).size(), 2, "money rows survive");
        Assert.assertTrue(dao.getRegistration(trip.getId(), me.getId(), Cached.NO).isPresent(),
                "the registration row survives");
        // The witness is untouched.
        Assert.assertTrue(dao.getPerson(witness.getId(), Cached.NO).isPresent());
    }

    // ------------------------------------------------------------------ settle-first

    @Test
    public void anUpcomingRegistrationBlocksUntilTheRuleIsSwitchedOff() throws IOException {
        final Person me = saved("Upcoming");
        final Organization org = savedOrg("Trips R Us", "contact@example.org", null);
        final Trip trip = savedTrip("Next month", 20, 30, org, me);
        Assert.assertTrue(dao.saveRegistration(new Registration(trip.getId(), me.getId())
                .withStatus(Registration.Status.PENDING)));

        final AccountDeletionCommands.Preview preview = deletion.preview(me.getId());
        Assert.assertTrue(preview.blocked());
        Assert.assertTrue(preview.settleRequired());
        Assert.assertEquals(preview.reasons().get(0).code(), AccountDeletionCommands.BLOCK_UPCOMING_TRIP);
        Assert.assertEquals(preview.reasons().get(0).tripTitle(), "Next month");
        Assert.assertEquals(preview.contactEmail(), "contact@example.org");

        final AccountDeletionCommands.Outcome refused = deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me));
        Assert.assertFalse(refused.ok());
        Assert.assertEquals(refused.code(), AccountDeletionCommands.REFUSED_BLOCKED);
        Assert.assertNotNull(refused.preview());
        Mockito.verifyNoInteractions(mail);
        Assert.assertTrue(dao.getPerson(me.getId(), Cached.NO).isPresent(), "nothing was touched");

        Mockito.doReturn(false).when(config).getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        Assert.assertFalse(deletion.preview(me.getId()).blocked(), "the switch relaxes the rule");
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).ok());
        Assert.assertTrue(dao.getPerson(me.getId(), Cached.NO).isEmpty());
    }

    @Test
    public void aBalanceOwedBlocksAndIsHighlightedInTheNotice() throws IOException {
        final Person me = saved("Debtor");
        final Organization org = savedOrg("Debt Org", "money@example.org", null);
        join(me, org);
        transaction(me, org, Transaction.TransactionType.Bill, -50f, "Deposit due");

        final AccountDeletionCommands.Preview preview = deletion.preview(me.getId());
        Assert.assertTrue(preview.blocked());
        Assert.assertEquals(preview.reasons().get(0).code(), AccountDeletionCommands.BLOCK_BALANCE_OWED);
        Assert.assertEquals(preview.amountOwedCents(), 5000L);

        Mockito.doReturn(false).when(config).getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), " DELETE ", actor(me)).ok(), "whitespace is fine");
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.eq("money@example.org"),
                ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.any(), body.capture(),
                ArgumentMatchers.any(AuditActor.class));
        Assert.assertTrue(body.getValue().contains("AMOUNT OWED BY TRAVELER: $50.00"), body.getValue());
    }

    @Test
    public void aCreditIsReportedAndDoesNotBlock() throws IOException {
        final Person me = saved("Creditor");
        final Organization org = savedOrg("Refund Org", "refunds@example.org", null);
        join(me, org);
        transaction(me, org, Transaction.TransactionType.Payment, 1234.5f, "Overpaid");
        final AccountDeletionCommands.Preview preview = deletion.preview(me.getId());
        Assert.assertFalse(preview.blocked());
        Assert.assertEquals(preview.amountCreditCents(), 123450L);
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).ok());
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any(), body.capture(),
                ArgumentMatchers.any(AuditActor.class));
        Assert.assertTrue(body.getValue().contains("AMOUNT OWED TO TRAVELER: $1,234.50"), body.getValue());
    }

    @Test
    public void theSoleAdminOfAnOrganizationIsAlwaysBlocked() throws IOException {
        final Person me = saved("Boss");
        final Organization org = savedOrg("Solo Org", null, me);
        join(me, org);
        Mockito.doReturn(false).when(config).getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        final AccountDeletionCommands.Preview preview = deletion.preview(me.getId());
        Assert.assertTrue(preview.blocked(), "structural: the org would have no admin");
        Assert.assertEquals(preview.reasons().get(0).code(), AccountDeletionCommands.BLOCK_SOLE_ORG_ADMIN);
        Assert.assertEquals(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).code(),
                AccountDeletionCommands.REFUSED_BLOCKED);
    }

    @Test
    public void aSecondAdminUnblocksAndTheSeatIsGivenUp() throws IOException {
        final Person me = saved("CoAdmin");
        final Person other = saved("OtherAdmin");
        final Organization org = savedOrg("Duo Org", null, me);
        org.getAdminIds().add(other.getId());
        Assert.assertTrue(dao.saveOrganization(org));
        join(me, org);
        Assert.assertFalse(deletion.preview(me.getId()).blocked());
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).ok());
        final Organization after = dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
        Assert.assertEquals(after.getAdminIds(), List.of(other.getId()));
    }

    // ------------------------------------------------------------------ refusals and resilience

    @Test
    public void confirmationAndIdentityAreChecked() throws IOException {
        final Person me = saved("Careful");
        Assert.assertEquals(deletion.deleteOwnAccount(me.getId(), "delete", actor(me)).code(),
                AccountDeletionCommands.REFUSED_CONFIRMATION, "case matters: a typed literal, not a tap");
        Assert.assertEquals(deletion.deleteOwnAccount(me.getId(), null, actor(me)).code(),
                AccountDeletionCommands.REFUSED_CONFIRMATION);
        Assert.assertEquals(deletion.deleteOwnAccount(Person.Id.from("nobody-" + RandomData.genAlpha(6)),
                "DELETE", actor(me)).code(), AccountDeletionCommands.REFUSED_NOT_FOUND);
        Assert.assertNull(deletion.preview(Person.Id.from("nobody-" + RandomData.genAlpha(6))));
        Assert.assertNull(deletion.preview(null));
        Assert.assertTrue(dao.getPerson(me.getId(), Cached.NO).isPresent());
    }

    @Test
    public void aMailOutageDoesNotBlockTheDeletion() throws IOException {
        final Person me = saved("Unmailed");
        Mockito.when(mail.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class))).thenThrow(new IllegalStateException("SES down"));
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", null).ok(),
                "no actor and no mail: still deleted, recorded in the trail instead");
        Assert.assertTrue(dao.getPerson(me.getId(), Cached.NO).isEmpty());
    }

    // ------------------------------------------------------------------ family

    @Test
    public void dependentsGoWithTheirOnlyManagerAndBlockWhileUnsettled() throws IOException {
        final Person me = saved("Parent");
        final FamilyCommands family = new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> new Caller(me.getId(), false, actor(me), new PrivilegeCommands()));
        final Person kid = family.createFamilyMember("Kid", "Parentson", LocalDate.of(2015, 1, 1),
                Person.Sex.Male, null, false);
        Assert.assertNotNull(kid, "setup: dependent created");
        final Organization org = savedOrg("Family Org", "family@example.org", null);
        final Trip upcoming = savedTrip("Kids camp", 10, 12, org, kid);

        final AccountDeletionCommands.Preview blocked = deletion.preview(me.getId());
        Assert.assertTrue(blocked.blocked());
        Assert.assertEquals(blocked.reasons().get(0).code(), AccountDeletionCommands.BLOCK_DEPENDENT_UNSETTLED);
        Assert.assertEquals(blocked.reasons().get(0).personName(), "Kid Parentson");
        Mockito.doReturn(false).when(config).getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        Assert.assertTrue(deletion.preview(me.getId()).blocked(), "a dependent's open trip blocks regardless");

        // Settle the dependent: the trip ends.
        upcoming.setStartDate(LocalDateTime.now().minusDays(10));
        upcoming.setEndDate(LocalDateTime.now().minusDays(5));
        Assert.assertTrue(dao.saveTrip(upcoming));
        Mockito.doReturn(true).when(config).getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        Assert.assertFalse(deletion.preview(me.getId()).blocked());

        final Family.Id familyId = dao.getPerson(me.getId(), Cached.NO).orElseThrow().getFamilyId();
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).ok());
        Assert.assertTrue(dao.getPerson(kid.getId(), Cached.NO).isEmpty(), "the dependent is deleted too");
        Assert.assertTrue(dao.getFamily(familyId, Cached.NO).isEmpty(), "an emptied family is removed");
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.eq("family@example.org"),
                ArgumentMatchers.any(), ArgumentMatchers.isNull(), ArgumentMatchers.any(), body.capture(),
                ArgumentMatchers.any(AuditActor.class));
        Assert.assertTrue(body.getValue().contains("Kid Parentson"), "the notice names the dependent");
        Assert.assertTrue(body.getValue().contains("Kids camp"), "and the dependent's trip");
    }

    @Test
    public void aMemberWithAnotherManagerLeavesTheFamilyBehind() throws IOException {
        final Person me = saved("Spouse1");
        final FamilyCommands family = new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> new Caller(me.getId(), false, actor(me), new PrivilegeCommands()));
        final Person spouse = family.createFamilyMember("Spouse2", "Both", LocalDate.of(1980, 1, 1),
                Person.Sex.Female, "spouse2." + RandomData.genAlpha(8).toLowerCase(Locale.ROOT) + "@example.com", true);
        final Person kid = family.createFamilyMember("Kid", "Both", LocalDate.of(2016, 1, 1),
                Person.Sex.Female, null, false);
        Assert.assertNotNull(spouse);
        Assert.assertNotNull(kid);
        Assert.assertTrue(deletion.deleteOwnAccount(me.getId(), "DELETE", actor(me)).ok());
        Assert.assertTrue(dao.getPerson(kid.getId(), Cached.NO).isPresent(), "the kid stays with the other manager");
        final Person remaining = dao.getPerson(spouse.getId(), Cached.NO).orElseThrow();
        Assert.assertFalse(remaining.getManagedUsers().contains(me.getId()));
        Assert.assertTrue(remaining.getManagedUsers().contains(kid.getId()));
        final Family after = dao.getFamily(remaining.getFamilyId(), Cached.NO).orElseThrow();
        Assert.assertFalse(after.isMember(me.getId()));
        Assert.assertTrue(after.isManager(spouse.getId()));
    }

    @Test
    public void moneyHelpers() {
        Assert.assertEquals(AccountDeletionCommands.money(123456L), "$1,234.56");
        final Transaction shared = new Transaction(null, Person.Id.from("a"), "g", Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), -100f, "c", "n",
                List.of(Person.Id.from("a"), Person.Id.from("b"), Person.Id.from("c"), Person.Id.from("d")));
        Assert.assertEquals(AccountDeletionCommands.shareCents(shared), -2500L, "a shared row is split evenly");
        final Transaction plain = new Transaction(null, Person.Id.from("a"), null, Transaction.Type.Tx,
                Transaction.TransactionType.Payment, LocalDateTime.now(), 10.005f, null, null, null);
        Assert.assertEquals(AccountDeletionCommands.shareCents(plain), 1001L);
        plain.setAmount(null);
        Assert.assertEquals(AccountDeletionCommands.shareCents(plain), 0L);
    }

    // ------------------------------------------------------------------ fixtures

    private Person saved(final String first) throws IOException {
        final Person person = Person.builder()
                .first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase() + "." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com")
                .build();
        Assert.assertTrue(dao.savePerson(person));
        return person;
    }

    private Organization savedOrg(final String name, final String contact, final Person admin) throws IOException {
        final Organization org = Organization.builder()
                .name(name + " " + RandomData.genAlpha(4))
                .contactEmail(contact)
                .adminIds(admin == null ? new ArrayList<>() : new ArrayList<>(List.of(admin.getId())))
                .created(LocalDateTime.now())
                .build();
        Assert.assertTrue(dao.saveOrganization(org));
        return org;
    }

    private void join(final Person person, final Organization org) throws IOException {
        Assert.assertTrue(dao.saveOrgMember(new OrgMember(org.getId(), person.getId(), LocalDateTime.now())));
        final Person fresh = dao.getPerson(person.getId(), Cached.NO).orElseThrow();
        final List<Organization.Id> orgs = new ArrayList<>(fresh.getOrgIds());
        orgs.add(org.getId());
        fresh.setOrgIds(orgs);
        Assert.assertTrue(dao.savePerson(fresh));
    }

    private Trip savedTrip(final String title, final int startInDays, final int endInDays, final Organization org,
            final Person... people) throws IOException {
        final List<Person.Id> ids = new ArrayList<>();
        for (final Person person : people) {
            ids.add(person.getId());
        }
        final Trip trip = Trip.builder()
                .id("del-" + RandomData.genAlpha(8))
                .title(title)
                .openToPublic(false)
                .startDate(LocalDateTime.now().plusDays(startInDays))
                .endDate(LocalDateTime.now().plusDays(endInDays))
                .people(ids)
                .build();
        trip.setOrgId(org.getId().getValue());
        Assert.assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private void transaction(final Person person, final Organization org, final Transaction.TransactionType type,
            final float amount, final String category) throws IOException {
        final Transaction tx = new Transaction(null, person.getId(), null, Transaction.Type.Tx, type,
                LocalDateTime.now(), amount, category, null, null);
        tx.setOrgId(org.getId().getValue());
        Assert.assertTrue(dao.saveTransaction(tx));
    }

    private ChatMessage sent(final Trip trip, final Person person, final String body) {
        final ChatCommands.SendResult result = chat.send(trip.getId(), person.getId(), body, null, null,
                actor(person));
        Assert.assertTrue(result.isOk(), result.getMessage());
        return result.getMessageObj();
    }

    private static AuditActor actor(final Person person) {
        return new AuditActor(person.getEmail(), person.getId().getValue());
    }
}
