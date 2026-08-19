package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The org tenancy matrix. Every test drives the REAL command + DAO + in-memory store; only the caller (no
 * FacesContext in tests) is stood in for -- the {@link FamilyCommandsTest} pattern.
 */
public class OrgCommandsTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ create

    @Test
    public void siteAdminCreatesAnOrganization() {
        final Organization org = admin().createOrganization("Create " + unique(), "CR", "cr@example.com");
        assertNotNull(org);
        assertTrue(org.getVersion() > 0, "Created means persisted");
        assertNotNull(org.getCreated());
        assertNotNull(org.getCreatedBy());
    }

    @Test
    public void nonSiteAdminCannotCreateAnOrganization() throws IOException {
        assertNull(commandsFor(savedPerson()).createOrganization("Nope " + unique(), null, null));
    }

    @Test
    public void blankOrDuplicateNamesAreRefused() {
        final String name = "Dup " + unique();
        assertNotNull(admin().createOrganization(name, null, null));
        assertNull(admin().createOrganization(name.toUpperCase(java.util.Locale.ROOT), null, null),
                "Duplicate check is case-insensitive");
        assertNull(admin().createOrganization("   ", null, null));
        assertNull(admin().createOrganization(null, null, null));
    }

    // ------------------------------------------------------------------ visibility (the isolation matrix)

    @Test
    public void orgAdminSeesOnlyTheirOwnOrgAsManageable() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final Organization other = admin().createOrganization("Other " + unique(), null, null);

        final List<Organization> manageable = commandsFor(acmeAdmin).getManageableOrgs();
        assertTrue(manageable.contains(acme), "An org admin manages their own org");
        assertFalse(manageable.contains(other), "...and never sees another tenant's org as manageable");
    }

    @Test
    public void siteAdminManagesEveryOrg() throws IOException {
        final Organization acme = orgWithAdmin(savedPerson());
        assertTrue(admin().getManageableOrgs().contains(acme));
        assertTrue(admin().canManageOrg(acme.getId().getValue()));
    }

    @Test
    public void canManageOrgMatrix() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Person outsider = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);

        assertTrue(commandsFor(acmeAdmin).canManageOrg(acme.getId().getValue()));
        assertFalse(commandsFor(outsider).canManageOrg(acme.getId().getValue()));
        assertFalse(commandsFor(outsider).canManageOrg("no-such-org"));
        assertFalse(anonymous().canManageOrg(acme.getId().getValue()));
    }

    // ------------------------------------------------------------------ autocomplete

    @Test
    public void completeOrgsMatchesContainsIgnoreCaseOnNameAndAbbreviation() {
        final String marker = unique();
        final Organization org = admin().createOrganization("Pilgrim " + marker + " Tours", "ZZ" + marker,
                null);
        assertNotNull(org);

        final OrgCommands cmds = admin();
        assertTrue(cmds.completeOrgs("grim " + marker.toUpperCase(java.util.Locale.ROOT)).contains(org),
                "contains + ignore-case on the name");
        assertTrue(cmds.completeOrgs("zz" + marker).contains(org), "abbreviation matches too");
        assertTrue(cmds.completeOrgs("  ").contains(org), "blank query lists everything manageable");
        assertFalse(cmds.completeOrgs("no-such-" + unique()).contains(org));
    }

    @Test
    public void completeAllOrgsIsSiteAdminOnly() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        assertTrue(admin().completeAllOrgs(acme.getName()).contains(acme));
        assertEquals(commandsFor(acmeAdmin).completeAllOrgs(acme.getName()), List.of(),
                "The every-org picker (the switcher) is for site admins only");
    }

    // ------------------------------------------------------------------ membership

    @Test
    public void addMemberWritesTheRowAndSyncsTheBackPointer() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final Person traveler = savedPerson();

        assertTrue(commandsFor(acmeAdmin).addMember(acme.getId().getValue(), traveler.getId()));
        assertTrue(commandsFor(acmeAdmin).isMember(acme.getId().getValue(), traveler.getId()));
        assertTrue(reload(traveler).getOrgIds().contains(acme.getId()),
                "The derived Person.orgIds gains the org");
        assertTrue(commandsFor(acmeAdmin).addMember(acme.getId().getValue(), traveler.getId()),
                "Re-adding is an idempotent no-op");
        assertEquals(reload(traveler).getOrgIds().stream()
                .filter(acme.getId()::equals).count(), 1L, "...and never duplicates the back-pointer");
    }

    @Test
    public void outsiderCannotTouchAnotherOrgsMembership() throws IOException {
        final Organization acme = orgWithAdmin(savedPerson());
        final Person outsider = savedPerson();
        final Person traveler = savedPerson();

        assertFalse(commandsFor(outsider).addMember(acme.getId().getValue(), traveler.getId()));
        assertFalse(commandsFor(outsider).removeMember(acme.getId().getValue(), traveler.getId()));
        assertFalse(commandsFor(outsider).setOrgAdmin(acme.getId().getValue(), outsider.getId(), true));
    }

    @Test
    public void removeMemberEnforcesTheLastOrgRule() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final Person traveler = savedPerson();
        final OrgCommands cmds = commandsFor(acmeAdmin);
        assertTrue(cmds.addMember(acme.getId().getValue(), traveler.getId()));

        assertFalse(cmds.removeMember(acme.getId().getValue(), traveler.getId()),
                "Every person must keep at least one organization");

        // Once they belong somewhere else, removal is allowed and syncs the back-pointer.
        final Organization second = admin().createOrganization("Second " + unique(), null, null);
        assertTrue(admin().addMember(second.getId().getValue(), traveler.getId()));
        assertTrue(cmds.removeMember(acme.getId().getValue(), traveler.getId()));
        assertFalse(cmds.isMember(acme.getId().getValue(), traveler.getId()));
        assertEquals(reload(traveler).getOrgIds(), List.of(second.getId()));
    }

    @Test
    public void removingAnOrgAdminRequiresRevokingFirst() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);

        assertFalse(admin().removeMember(acme.getId().getValue(), acmeAdmin.getId()),
                "An admin member cannot be removed while still an admin");

        assertTrue(admin().setOrgAdmin(acme.getId().getValue(), acmeAdmin.getId(), false));
        // Still refused: last-org rule (the admin only belongs to acme). The refusal ORDER matters --
        // admin-ness is checked first, so this now trips the other rule.
        assertFalse(admin().removeMember(acme.getId().getValue(), acmeAdmin.getId()));
    }

    @Test
    public void removingANonMemberIsRefused() throws IOException {
        final Organization acme = orgWithAdmin(savedPerson());
        assertFalse(admin().removeMember(acme.getId().getValue(), savedPerson().getId()));
    }

    // ------------------------------------------------------------------ admins

    @Test
    public void grantingAdminAutoAddsMembership() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final Person promoted = savedPerson();

        assertTrue(commandsFor(acmeAdmin).setOrgAdmin(acme.getId().getValue(), promoted.getId(), true));
        assertTrue(commandsFor(acmeAdmin).isMember(acme.getId().getValue(), promoted.getId()),
                "Admins are members");
        assertTrue(commandsFor(promoted).canManageOrg(acme.getId().getValue()),
                "The grant is effective immediately");
        assertTrue(commandsFor(acmeAdmin).setOrgAdmin(acme.getId().getValue(), promoted.getId(), true),
                "Granting twice is a no-op");

        assertTrue(commandsFor(acmeAdmin).setOrgAdmin(acme.getId().getValue(), promoted.getId(), false));
        assertFalse(commandsFor(promoted).canManageOrg(acme.getId().getValue()));
    }

    // ------------------------------------------------------------------ edits

    @Test
    public void orgAdminEditsContactButOnlySiteAdminRenames() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);

        final Organization edit = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        edit.setContactEmail("front-desk@acme.example");
        assertTrue(cmds.saveOrganization(edit));

        final Organization rename = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        rename.setName("Renamed " + unique());
        assertFalse(cmds.saveOrganization(rename),
                "Public pages display the name; renaming is site-admin only");

        final Organization adminRename = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        adminRename.setName("Renamed " + unique());
        assertTrue(admin().saveOrganization(adminRename));
    }

    @Test
    public void staleEditFailsWithoutClobbering() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);

        final Organization stale = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        final Organization winner = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        winner.setContactEmail("winner@acme.example");
        assertTrue(cmds.saveOrganization(winner));

        stale.setContactEmail("loser@acme.example");
        assertFalse(cmds.saveOrganization(stale), "A lost race surfaces; it is never retried silently");
        assertEquals(dao.getOrganization(acme.getId(), Cached.NO).orElseThrow().getContactEmail(),
                "winner@acme.example");
    }

    @Test
    public void savingAnUnknownOrUnsavedOrgIsRefused() {
        assertFalse(admin().saveOrganization(null));
        assertFalse(admin().saveOrganization(new Organization()),
                "Version 0 means never persisted -- creation goes through createOrganization");
    }

    // ------------------------------------------------------------------ lookups

    @Test
    public void lookupContractsHoldForUnknownIds() {
        assertNull(admin().findOrganization("no-such-org"));
        assertNull(admin().findOrganization("  "));
        assertNull(admin().findOrganization(null));
        assertNotNull(admin().getOrganization("no-such-org"), "Bean get* never answers null");
        assertEquals(admin().getOrganization("no-such-org").getVersion(), 0L, "...it answers a blank");
    }

    @Test
    public void membershipViewsResolve() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);

        assertTrue(commandsFor(acmeAdmin).getMembers(acme).stream()
                .anyMatch(p -> p.getId().equals(acmeAdmin.getId())));
        assertTrue(commandsFor(acmeAdmin).membershipsOf(reload(acmeAdmin)).contains(acme));
        assertTrue(commandsFor(acmeAdmin).membershipsOf(null).isEmpty());
        assertTrue(commandsFor(acmeAdmin).getMembers(null).isEmpty());
        assertFalse(commandsFor(acmeAdmin).isMember(null, acmeAdmin.getId()));
    }

    // ------------------------------------------------------------------ refusal tails

    @Test
    public void theDefaultConstructorWiresTheFacesCallerSource() {
        assertNotNull(new OrgCommands(), "CDI's no-arg path must construct without a FacesContext");
    }

    @Test
    public void anonymousCallersSeeAndManageNothing() {
        assertEquals(anonymous().getManageableOrgs(), List.of());
        assertEquals(anonymous().completeOrgs("acme"), List.of());
    }

    @Test
    public void unknownOrgIdsAreRefusedAcrossTheWriteApi() {
        final Person.Id somebody = Person.Id.newInstance();
        assertFalse(admin().addMember("no-such-org", somebody));
        assertFalse(admin().removeMember("no-such-org", somebody));
        assertFalse(admin().setOrgAdmin("no-such-org", somebody, true));
    }

    @Test
    public void unknownPeopleAreRefused() throws IOException {
        final Organization acme = orgWithAdmin(savedPerson());
        assertFalse(admin().addMember(acme.getId().getValue(), Person.Id.newInstance()),
                "A membership row must never anchor a person that does not exist");
        assertFalse(admin().setOrgAdmin(acme.getId().getValue(), Person.Id.newInstance(), true),
                "Granting admin to an unknown person fails at the auto-add");
        assertFalse(admin().addMember(acme.getId().getValue(), null));
    }

    @Test
    public void savingAVersionedButUnknownOrgIsRefused() {
        final Organization ghost = new Organization();
        ghost.setName("Ghost");
        ghost.setVersion(5L);
        assertFalse(admin().saveOrganization(ghost));
    }

    @Test
    public void blankNameOnSaveIsRefused() {
        final Organization org = admin().createOrganization("Blankable " + unique(), null, null);
        assertNotNull(org);
        final Organization edit = dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
        edit.setName("   ");
        assertFalse(admin().saveOrganization(edit));
    }

    @Test
    public void saveOrgEditsAppliesOntoAFreshRead() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);

        assertTrue(cmds.saveOrgEdits(acme.getId().getValue(), acme.getName(), "AI", "desk@acme.example"));
        final Organization stored = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        assertEquals(stored.getAbbreviation(), "AI");
        assertEquals(stored.getContactEmail(), "desk@acme.example");

        assertFalse(cmds.saveOrgEdits(acme.getId().getValue(), "Renamed " + unique(), null, null),
                "The rename rule applies through the page-facing edit too");
        assertFalse(cmds.saveOrgEdits("no-such-org", "X", null, null));
        assertFalse(cmds.saveOrgEdits("  ", "X", null, null));
    }

    @Test
    public void memberCountCountsRowsWithoutResolvingPeople() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        assertEquals(admin().getMemberCount(acme), 1);
        assertTrue(admin().addMember(acme.getId().getValue(), savedPerson().getId()));
        assertEquals(admin().getMemberCount(dao.getOrganization(acme.getId(), Cached.NO).orElseThrow()), 2);
        assertEquals(admin().getMemberCount(null), 0);
    }

    @Test
    public void completeOrgsToleratesAMissingAbbreviation() {
        final Organization org = admin().createOrganization("NoAbbr " + unique(), null, null);
        assertNotNull(org);
        assertTrue(admin().completeOrgs("noabbr").contains(org),
                "A null abbreviation must not break the contains matching");
    }

    // ------------------------------------------------------------------ processor configs (tenancy matrix)

    @Test
    public void orgAdminManagesOnlyTheirOwnProcessorConfigs() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final Organization other = orgWithAdmin(savedPerson());
        final OrgCommands cmds = commandsFor(acmeAdmin);

        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "Acme Fake", "FAKE",
                "SANDBOX", true, "client-1", "sb-client-1", 0, 0));
        final List<org.paulsens.trip.model.PaymentProcessorConfig> mine =
                cmds.getProcessorConfigs(acme.getId().getValue());
        assertEquals(mine.size(), 1);
        assertEquals(mine.get(0).getLabel(), "Acme Fake");
        assertEquals(mine.get(0).getPublicConfig().get("clientId"), "client-1");

        assertEquals(cmds.getProcessorConfigs(other.getId().getValue()), List.of(),
                "Another tenant's configs are invisible");
        assertFalse(cmds.saveProcessorConfig(other.getId().getValue(), null, "Sneaky", "FAKE",
                "SANDBOX", true, null, null, 0, 0), "...and unwritable");
        assertNull(cmds.findProcessorConfig(other.getId().getValue(), mine.get(0).getId().getValue()),
                "The composite key means a foreign config id simply misses");
    }

    @Test
    public void processorEditsApplyOntoAFreshReadAndValidate() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);
        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "Editable", "FAKE",
                "SANDBOX", true, null, null, 0, 0));
        final String cfgId = cmds.getProcessorConfigs(acme.getId().getValue()).get(0).getId().getValue();

        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), cfgId, "Edited", "PAYPAL",
                "LIVE", false, "live-id", "sb-id", 350, 45));
        final org.paulsens.trip.model.PaymentProcessorConfig edited =
                cmds.findProcessorConfig(acme.getId().getValue(), cfgId);
        assertEquals(edited.getLabel(), "Edited");
        assertEquals(edited.getType(), org.paulsens.trip.model.ProcessorType.PAYPAL);
        assertEquals(edited.getMode(),
                org.paulsens.trip.model.PaymentProcessorConfig.ProcessorMode.LIVE);
        assertFalse(edited.isEnabled());
        assertEquals(edited.getFeeBps(), 350);

        assertFalse(cmds.saveProcessorConfig(acme.getId().getValue(), cfgId, "  ", "PAYPAL", "LIVE",
                true, null, null, 0, 0), "A blank label is refused");
        assertFalse(cmds.saveProcessorConfig(acme.getId().getValue(), cfgId, "X", "NOT_A_TYPE", "LIVE",
                true, null, null, 0, 0), "An unknown type is refused");
        assertFalse(cmds.saveProcessorConfig(acme.getId().getValue(), "no-such-config", "X", "FAKE",
                "LIVE", true, null, null, 0, 0));
    }

    @Test
    public void secretsRoundTripThroughTheStoreNeverTheRow() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);
        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "Secretive", "FAKE",
                "SANDBOX", true, null, null, 0, 0));
        final String cfgId = cmds.getProcessorConfigs(acme.getId().getValue()).get(0).getId().getValue();

        assertFalse(cmds.hasProcessorSecret(cfgId, false));
        assertTrue(cmds.setProcessorSecret(acme.getId().getValue(), cfgId, "live-secret", null));
        assertTrue(cmds.hasProcessorSecret(cfgId, false));
        assertFalse(cmds.hasProcessorSecret(cfgId, true), "Blank sandbox secret left unchanged");
        assertTrue(cmds.setProcessorSecret(acme.getId().getValue(), cfgId, "", "sb-secret"));
        assertTrue(cmds.hasProcessorSecret(cfgId, true));
        assertTrue(cmds.hasProcessorSecret(cfgId, false), "Blank live secret left unchanged");

        assertFalse(commandsFor(savedPerson()).setProcessorSecret(acme.getId().getValue(), cfgId,
                "stolen", null), "Outsiders cannot write secrets");
        assertNull(cmds.findProcessorConfig(acme.getId().getValue(), cfgId).getPublicConfig()
                .get("clientSecret"), "Secret material never lands in the row");
    }

    @Test
    public void testConnectionAndDeleteFollowTheRules() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);
        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "Pingable", "FAKE",
                "SANDBOX", true, null, null, 0, 0));
        final String cfgId = cmds.getProcessorConfigs(acme.getId().getValue()).get(0).getId().getValue();

        assertTrue(cmds.testProcessorConnection(acme.getId().getValue(), cfgId, false),
                "FAKE always pings OK");
        assertFalse(cmds.testProcessorConnection(acme.getId().getValue(), "no-such-config", false));

        assertFalse(cmds.deleteProcessorConfig(acme.getId().getValue(), cfgId),
                "Delete is site-admin only; org admins disable instead");
        assertTrue(admin().deleteProcessorConfig(acme.getId().getValue(), cfgId));
        assertNull(cmds.findProcessorConfig(acme.getId().getValue(), cfgId));
        assertFalse(admin().deleteProcessorConfig(acme.getId().getValue(), cfgId),
                "Deleting a missing config is refused, not silently OK");
    }

    // ------------------------------------------------------------------ payment config ladder

    @Test
    public void thePaymentLadderResolvesTripOverOrgOverSite() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);

        final org.paulsens.trip.model.Trip tripless = null;
        final org.paulsens.trip.model.TripPaymentConfig site = cmds.effectivePaymentConfig(tripless);
        assertEquals(site.getFeesPaidBy(), org.paulsens.trip.model.FeesPaidBy.ORGANIZATION,
                "The site rung ships ORGANIZATION");
        assertEquals(site.getConfirmationTemplateId(), "payment-confirmation");
        assertFalse(site.isPayable(), "No processor anywhere means not payable");

        // Org rung: defaults set on the org flow into its trips.
        final Organization freshOrg = dao.getOrganization(acme.getId(), Cached.NO).orElseThrow();
        freshOrg.getPaymentDefaults().setMailFrom("Org <no-reply@acme.example>");
        freshOrg.getPaymentDefaults().setFeesPaidBy(org.paulsens.trip.model.FeesPaidBy.PAYER);
        assertTrue(cmds.saveOrganization(freshOrg));
        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id("ladder-" + unique()).title("Ladder").build();
        trip.setOrgId(acme.getId().getValue());
        final org.paulsens.trip.model.TripPaymentConfig viaOrg = cmds.effectivePaymentConfig(trip);
        assertEquals(viaOrg.getMailFrom(), "Org <no-reply@acme.example>");
        assertEquals(viaOrg.getFeesPaidBy(), org.paulsens.trip.model.FeesPaidBy.PAYER);

        // Trip rung wins over both.
        trip.getPaymentConfig().setFeesPaidBy(org.paulsens.trip.model.FeesPaidBy.ORGANIZATION);
        trip.getPaymentConfig().setMailFrom("Trip <trips@acme.example>");
        final org.paulsens.trip.model.TripPaymentConfig viaTrip = cmds.effectivePaymentConfig(trip);
        assertEquals(viaTrip.getMailFrom(), "Trip <trips@acme.example>");
        assertEquals(viaTrip.getFeesPaidBy(), org.paulsens.trip.model.FeesPaidBy.ORGANIZATION);
    }

    @Test
    public void tripEditorsPickFromTheirOrgsEnabledConfigsOnly() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final OrgCommands cmds = commandsFor(acmeAdmin);
        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "On", "FAKE", "SANDBOX",
                true, null, null, 0, 0));
        assertTrue(cmds.saveProcessorConfig(acme.getId().getValue(), null, "Off", "FAKE", "SANDBOX",
                false, null, null, 0, 0));

        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id("choices-" + unique()).build();
        trip.setOrgId(acme.getId().getValue());
        // A non-org-admin (a plain trip editor) still sees the picker choices.
        final Person editor = savedPerson();
        final List<org.paulsens.trip.model.PaymentProcessorConfig> choices =
                commandsFor(editor).getConfigChoicesForTrip(trip);
        assertEquals(choices.size(), 1, "Enabled configs only");
        assertEquals(choices.get(0).getLabel(), "On");

        assertEquals(commandsFor(editor).getConfigChoicesForTrip(
                org.paulsens.trip.model.Trip.builder().id("orgless-" + unique()).build()), List.of(),
                "An org-less legacy trip has no choices");
        assertEquals(anonymous().getConfigChoicesForTrip(trip), List.of());
    }

    @Test
    public void extraTokenTextRoundTrips() {
        final OrgCommands cmds = admin();
        final org.paulsens.trip.model.TripPaymentConfig config =
                new org.paulsens.trip.model.TripPaymentConfig();
        cmds.applyTokensText(config, "officePhone=555-1212\nbroken line\n taxId = 12-345 \n");
        assertEquals(config.getExtraTokens(),
                java.util.Map.of("officePhone", "555-1212", "taxId", "12-345"),
                "Lines without '=' are ignored; keys and values trim");
        assertEquals(cmds.tokensToText(config.getExtraTokens()), "officePhone=555-1212\ntaxId=12-345");
        assertEquals(cmds.tokensToText(null), "");
        cmds.applyTokensText(config, null);
        assertTrue(config.getExtraTokens().isEmpty(), "Clearing the textarea clears the map");
        cmds.applyTokensText(null, "x=y");
    }

    @Test
    public void testSendFollowsTheEffectiveConfigAndRefusesLoudly() throws IOException {
        final Person acmeAdmin = savedPerson();
        final Organization acme = orgWithAdmin(acmeAdmin);
        final org.paulsens.trip.action.MailCommands mail =
                Mockito.mock(org.paulsens.trip.action.MailCommands.class);
        Mockito.when(mail.sendManagedTemplate(Mockito.anyString(), Mockito.anyMap(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(true);
        final OrgCommands cmds = new OrgCommands(callerOf(acmeAdmin), () -> mail);

        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id("mailtest-" + unique()).title("Mail Test Trip").build();
        trip.setOrgId(acme.getId().getValue());
        assertFalse(cmds.sendPaymentTestMail(trip),
                "No From address anywhere resolves to a loud refusal, not a broken send");

        trip.getPaymentConfig().setMailFrom("Trip <no-reply@acme.example>");
        assertTrue(cmds.sendPaymentTestMail(trip));
        Mockito.verify(mail).sendManagedTemplate(Mockito.eq("payment-confirmation"), Mockito.anyMap(),
                Mockito.eq(acmeAdmin.getEmail()), Mockito.eq("Trip <no-reply@acme.example>"),
                Mockito.any(), Mockito.any());

        final Person noEmail = savedPerson();
        noEmail.setEmail(null);
        assertTrue(dao.savePerson(noEmail));
        assertFalse(new OrgCommands(callerOf(noEmail), () -> mail).sendPaymentTestMail(trip),
                "An admin with no address cannot receive the test");
    }

    private java.util.function.Supplier<Caller> callerOf(final Person person) {
        return () -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing());
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh org whose only admin (and member) is the given person -- the standard tenant fixture. */
    private Organization orgWithAdmin(final Person orgAdmin) {
        final Organization org = admin().createOrganization("Acme " + unique(), null, null);
        assertNotNull(org);
        assertTrue(admin().setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("org." + unique() + "@example.com")
                .build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private Person reload(final Person person) {
        return dao.getPerson(person.getId(), Cached.NO).orElseThrow();
    }

    private OrgCommands commandsFor(final Person person) {
        return new OrgCommands(() -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing()));
    }

    /** A site-admin caller; {@code Caller.isSiteAdmin} short-circuits, so no privilege rows are needed. */
    private OrgCommands admin() {
        return new OrgCommands(() -> new Caller(Person.Id.from("admin-" + unique()), true,
                new AuditActor("admin@test", "admin"), grantsNothing()));
    }

    private OrgCommands anonymous() {
        return new OrgCommands(() -> new Caller(null, false, AuditActor.system(), grantsNothing()));
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    private static String unique() {
        return RandomData.genAlpha(10);
    }
}
