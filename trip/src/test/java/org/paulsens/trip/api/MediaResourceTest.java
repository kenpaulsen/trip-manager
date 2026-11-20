package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.MediaItemDto;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link MediaResource}: the media library over the wire.
 *
 * <p>The behaviours most worth keeping honest are the ones the presigned upload design leans on: a confirm
 * must verify the object rather than trust the call, caller-chosen keys must stay out of other features'
 * namespaces, and a key already claimed by a row must 409 rather than quietly minting a second row over the
 * same object.
 */
public class MediaResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("media-admin");
    private static final Person.Id USER = Person.Id.from("media-user");

    private MediaCommands media;
    private org.paulsens.trip.action.TripCommands trips;
    private MediaResource resource;

    @BeforeMethod
    public void bindBeans() {
        media = bindMock(MediaCommands.class);
        bindMock(PersonCommands.class);
        // The album gate asks who may READ the trip (canReadTrip), which asks who manages someone on it.
        trips = bindMock(org.paulsens.trip.action.TripCommands.class);
        // The real chat-album rule, reading this test's media mock: the slot read asks it of a chat reader.
        bind(org.paulsens.trip.action.ChatAlbumAccess.class, new org.paulsens.trip.action.ChatAlbumAccess(
                org.paulsens.trip.action.ChatCommands::getChatCommands, () -> media));
        resource = resource(new MediaResource());
    }

    private static MediaItem item(final String id, final String key, final Boolean hidden) {
        return new MediaItem(id, key, "Title of " + id, "desc", "application/pdf", 9L, "home-docs", 1,
                LocalDateTime.of(2026, 8, 1, 12, 0), "who@example.com", null, hidden);
    }

    @Test
    public void everyAdminOperationRefusesAnOrdinaryUser() {
        signedInAs(USER);

        assertError(resource.list(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.update(CSRF_OK, "id", new MediaResource.UpdateRequest(null, "t", null, null, null,
                null)), 403, ApiErrors.FORBIDDEN);
        assertError(resource.delete(CSRF_OK, "id"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.refresh(CSRF_OK), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void everyMutationRefusesAMissingCsrfSentinel() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(null,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                403, ApiErrors.CSRF);
        assertError(resource.confirm(null,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                403, ApiErrors.CSRF);
        assertError(resource.update(null, "id", null), 403, ApiErrors.CSRF);
        assertError(resource.delete(null, "id"), 403, ApiErrors.CSRF);
        assertError(resource.refresh(null), 403, ApiErrors.CSRF);
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void listReturnsTheInventoryWithResolvedUrls() {
        signedInAsSiteAdmin(ADMIN);
        final MediaItem stored = item("m1", "downloads/guide.pdf", null);
        Mockito.when(media.getAll()).thenReturn(List.of(stored));
        Mockito.when(media.getUrl(stored)).thenReturn("https://cdn.example/downloads/guide.pdf");

        final Response response = resource.list();

        assertOk(response);
        final List<?> items = (List<?>) response.getEntity();
        Assert.assertEquals(items.size(), 1);
        final MediaItemDto dto = (MediaItemDto) items.get(0);
        Assert.assertEquals(dto.id(), "m1");
        Assert.assertEquals(dto.key(), "downloads/guide.pdf");
        Assert.assertEquals(dto.url(), "https://cdn.example/downloads/guide.pdf");
        Assert.assertFalse(dto.hidden());
    }

    /** A rotated picture keeps its key, so both URLs carry the rev, and the thumbnail is the small rendition. */
    @Test
    public void aRotatedItemCarriesItsRevAndBustedUrls() {
        signedInAsSiteAdmin(ADMIN);
        final MediaItem rotated = new MediaItem("m1", "chat/t/a.jpg", null, null, "image/jpeg", 9L, "tripChat-t",
                0, LocalDateTime.of(2026, 8, 1, 12, 0), "who", "chat/t/a-small.jpg", null, null)
                .withRotated(8L, "chat/t/a-small.jpg");
        Mockito.when(media.getAll()).thenReturn(List.of(rotated, item("m2", "downloads/b.pdf", null)));
        Mockito.when(media.getUrl(ArgumentMatchers.any())).thenAnswer(
                call -> "https://cdn.example/" + call.<MediaItem>getArgument(0).getS3Key());
        Mockito.when(media.publicUrl(ArgumentMatchers.anyString())).thenAnswer(
                call -> "https://cdn.example/" + call.<String>getArgument(0));

        final List<?> items = (List<?>) resource.list().getEntity();

        final MediaItemDto dto = (MediaItemDto) items.get(0);
        Assert.assertEquals(dto.rev(), 1);
        Assert.assertEquals(dto.url(), "https://cdn.example/chat/t/a.jpg?r=1");
        Assert.assertEquals(dto.thumbUrl(), "https://cdn.example/chat/t/a-small.jpg?r=1");
        final MediaItemDto plain = (MediaItemDto) items.get(1);
        Assert.assertEquals(plain.rev(), 0);
        Assert.assertEquals(plain.url(), "https://cdn.example/downloads/b.pdf");
        Assert.assertEquals(plain.thumbUrl(), "https://cdn.example/downloads/b.pdf", "no small rendition");
    }

    /** A non-admin browsing a slot sees what a public page would render, never the hidden rows. */
    @Test
    public void slotListingServesOrdinaryUsersTheVisibleItemsOnly() {
        signedInAs(USER);
        final MediaItem visible = item("m1", "downloads/a.pdf", null);
        Mockito.when(media.getVisibleInSlot("home-docs", 0)).thenReturn(List.of(visible));

        final Response response = resource.inSlot("home-docs");

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
        Mockito.verify(media, Mockito.never()).getDiscoverableInSlot(ArgumentMatchers.anyString());
    }

    /**
     * An admin gets the hidden rows too -- of THIS site. The listing goes through the media tenancy rule
     * ({@code getDiscoverableInSlot}), never the raw slot read: a slot name like {@code home-docs} is shared
     * by every organization, and the raw read handed an admin all of theirs (audit 2026-11-11, F10).
     */
    @Test
    public void slotListingServesAdminsThisSitesItemsHiddenIncluded() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getDiscoverableInSlot("home-docs"))
                .thenReturn(List.of(item("m1", "downloads/a.pdf", true)));

        final Response response = resource.inSlot("home-docs");

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
        Mockito.verify(media, Mockito.never()).getVisibleInSlot(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt());
        Mockito.verify(media, Mockito.never()).getInSlot(ArgumentMatchers.anyString());
    }

    /**
     * F10, the album half: being a media admin on the site a request came to says nothing about another
     * organization's trip. An org's media editor asking for a foreign trip's album is an ordinary caller
     * there (absent, since they are not on it), and gets the whole album only for a trip of their own org.
     */
    @Test
    public void anOrgsMediaEditorDoesNotGetAnotherOrganizationsTripAlbum() throws Exception {
        org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        final Person.Id editor = Person.Id.newInstance();
        final org.paulsens.trip.action.PrivilegeCommands priv = new org.paulsens.trip.action.PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate("mediaAdmin",
                org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID, "acme media").withNewPerson(editor), null));
        final org.paulsens.trip.model.Trip theirs = albumTrip(org.paulsens.trip.dynamo.FakeData.BETA_ORG_ID);
        final org.paulsens.trip.model.Trip ours = albumTrip(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID);
        final MediaItem hiddenPhoto = item("m-h", "chat/t/a.jpg", true);
        Mockito.when(media.getDiscoverableInSlot(ArgumentMatchers.anyString())).thenReturn(List.of(hiddenPhoto));
        Mockito.when(media.getVisibleInSlot(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        signedInAs(editor);
        final org.paulsens.trip.site.SiteContext acme = org.paulsens.trip.site.SiteContext.org(
                org.paulsens.trip.model.Organization.Id.from(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID),
                "acme", "acme.localhost");

        final MediaResource onAcme = resource(new MediaResource());
        final Response foreign = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme))
                .call(() -> onAcme.inSlot("tripChat-" + theirs.getId()));
        assertError(foreign, 404, ApiErrors.NOT_FOUND);
        Mockito.verify(media, Mockito.never()).getDiscoverableInSlot("tripChat-" + theirs.getId());
        Assert.assertFalse(onAcme.managesSlotAlbum("tripChat-" + theirs.getId()));
        Assert.assertFalse(onAcme.managesSlotAlbum("tripChat-no-such-trip"));
        Assert.assertFalse(onAcme.managesSlotAlbum("home-docs"), "only a chat slot is an album");

        final Response own = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme))
                .call(() -> onAcme.inSlot("tripChat-" + ours.getId()));
        assertOk(own);
        Assert.assertEquals(((List<?>) own.getEntity()).size(), 1, "their own org's album, hidden rows included");

        // The trip's own manager moderates its album whoever owns the rows (MediaCommands.mayManage).
        final Person.Id manager = Person.Id.newInstance();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate("tripMgr", theirs.getId(), "mgr")
                .withNewPerson(manager), null));
        signedInAs(manager);
        assertOk(resource(new MediaResource()).inSlot("tripChat-" + theirs.getId()));
    }

    /** A private trip of {@code orgId} nobody in these tests is on: its album is members' and managers' only. */
    private static org.paulsens.trip.model.Trip albumTrip(final String orgId) throws java.io.IOException {
        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Album").build();
        trip.setOrgId(orgId);
        trip.setOpenToPublic(false);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(trip));
        return trip;
    }

    /**
     * On the product's own host the API lists every organization, so "publicly listed on this site" is true of
     * any open trip. The album of another organization's open trip is browsable there only where the trip may
     * cross organizations at all (ListingScope.albumAcrossOrgs, the consent half of the open listing's rule):
     * not when its organization turned Shared sites off, unless the caller belongs to that organization.
     */
    @Test
    public void onTheProductHostAnOptedOutOrgsAlbumIsNotBrowsableByStrangers() throws Exception {
        signedInAs(USER);
        final org.paulsens.trip.model.Organization quiet = org.paulsens.trip.model.Organization.builder()
                .name("Quiet album org " + System.nanoTime()).build();
        quiet.setSharedSitesAllowed(false);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(quiet));
        final org.paulsens.trip.model.Organization open = org.paulsens.trip.model.Organization.builder()
                .name("Open album org " + System.nanoTime()).build();
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(open));
        final String quietSlot = "tripChat-" + publicAlbumTrip(quiet.getId().getValue()).getId();
        final String openSlot = "tripChat-" + publicAlbumTrip(open.getId().getValue()).getId();
        final org.paulsens.trip.site.SiteContext product =
                org.paulsens.trip.site.SiteContext.marketing("unitetrip.com").forApiClient();

        final Person stranger = new Person();
        stranger.setId(USER);
        Mockito.when(bean(PersonCommands.class).getPerson(USER)).thenReturn(stranger);
        Assert.assertFalse(onSite(product, () -> resource.mayBrowseSlot(quietSlot)),
                "a stranger must not browse the album of an organization that keeps off shared surfaces");
        Assert.assertTrue(onSite(product, () -> resource.mayBrowseSlot(openSlot)),
                "an organization that allows it: its open trip's album is public there too");

        final Person member = new Person();
        member.setId(USER);
        member.setOrgIds(List.of(quiet.getId()));
        Mockito.when(bean(PersonCommands.class).getPerson(USER)).thenReturn(member);
        Assert.assertTrue(onSite(product, () -> resource(new MediaResource()).mayBrowseSlot(quietSlot)),
                "the organization's own member still browses it");
    }

    /**
     * The album is decided on the organization's consent, not on the trip's dates: an open trip that ended
     * yesterday keeps its album on the product host (it is when the album fills, and the website shows it too),
     * where Discover's "not ended" clause once closed it to everyone off the roster.
     */
    @Test
    public void onTheProductHostAnEndedOpenTripsAlbumStaysBrowsable() throws Exception {
        signedInAs(USER);
        final org.paulsens.trip.model.Organization open = org.paulsens.trip.model.Organization.builder()
                .name("Ended album org " + System.nanoTime()).build();
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(open));
        final org.paulsens.trip.model.Organization quiet = org.paulsens.trip.model.Organization.builder()
                .name("Ended quiet org " + System.nanoTime()).build();
        quiet.setSharedSitesAllowed(false);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(quiet));
        final org.paulsens.trip.model.Trip ended = endedPublicAlbumTrip(open.getId().getValue());
        final org.paulsens.trip.model.Trip endedQuiet = endedPublicAlbumTrip(quiet.getId().getValue());
        final org.paulsens.trip.site.SiteContext product =
                org.paulsens.trip.site.SiteContext.marketing("unitetrip.com").forApiClient();
        final Person stranger = new Person();
        stranger.setId(USER);
        Mockito.when(bean(PersonCommands.class).getPerson(USER)).thenReturn(stranger);

        Assert.assertFalse(org.paulsens.trip.site.ListingScope.discoverableNow(ended), "ended: off the listing");
        Assert.assertTrue(onSite(product, () -> resource.mayBrowseSlot("tripChat-" + ended.getId())),
                "an ended open trip's album stays browsable where its organization allows shared surfaces");
        Assert.assertFalse(onSite(product, () -> resource.mayBrowseSlot("tripChat-" + endedQuiet.getId())),
                "ending a trip must not open the album of an organization that keeps off them");
    }

    /**
     * The open listing's own-right exception holds for the album too: someone who may READ the trip (here a
     * parent managing a traveler on its roster, with no seat and no membership of their own) browses its album
     * even where its organization turned Shared sites off.
     */
    @Test
    public void whoeverMayReadTheTripBrowsesItsAlbumOnTheProductHost() throws Exception {
        signedInAs(USER);
        final org.paulsens.trip.model.Organization quiet = org.paulsens.trip.model.Organization.builder()
                .name("Managed album org " + System.nanoTime()).build();
        quiet.setSharedSitesAllowed(false);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(quiet));
        final org.paulsens.trip.model.Trip trip = publicAlbumTrip(quiet.getId().getValue());
        final String slot = "tripChat-" + trip.getId();
        final org.paulsens.trip.site.SiteContext product =
                org.paulsens.trip.site.SiteContext.marketing("unitetrip.com").forApiClient();
        final Person parent = new Person();
        parent.setId(USER);
        Mockito.when(bean(PersonCommands.class).getPerson(USER)).thenReturn(parent);

        Assert.assertFalse(onSite(product, () -> resource.mayBrowseSlot(slot)), "a stranger: refused");

        Mockito.when(trips.managesSomeoneOn(Mockito.any(), Mockito.eq(USER))).thenReturn(true);
        Assert.assertTrue(onSite(product, () -> resource(new MediaResource()).mayBrowseSlot(slot)),
                "managing a traveler on the trip is a right to read it, and so its album");
    }

    private static org.paulsens.trip.model.Trip publicAlbumTrip(final String orgId) throws java.io.IOException {
        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Public album").build();
        trip.setOrgId(orgId);
        trip.setOpenToPublic(true);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(trip));
        return trip;
    }

    /** {@link #publicAlbumTrip} for a trip that ended yesterday. */
    private static org.paulsens.trip.model.Trip endedPublicAlbumTrip(final String orgId)
            throws java.io.IOException {
        final LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Ended public album")
                .startDate(yesterday.minusDays(7)).endDate(yesterday).build();
        trip.setOrgId(orgId);
        trip.setOpenToPublic(true);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static boolean onSite(final org.paulsens.trip.site.SiteContext site,
            final ScopedValue.CallableOp<Boolean, RuntimeException> body) {
        return ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, site)).call(body);
    }

    /** An album row of a chat photo uploaded {@code minutesAgo} minutes ago (negative: still to come). */
    private static MediaItem chatRow(final String tripId, final String id, final long minutesAgo,
            final boolean hidden) {
        return new MediaItem(id, "chat/" + tripId + "/" + id + ".jpg", id, "desc", "image/jpeg", 9L,
                "tripChat-" + tripId, 0, LocalDateTime.now().minusMinutes(minutesAgo), "who", null, hidden);
    }

    private static List<String> idsOf(final Response response) {
        return ((List<?>) response.getEntity()).stream().map(item -> ((MediaItemDto) item).id()).toList();
    }

    /**
     * A chat member who is NOT on the roster (an invited guest, perhaps of another organization) gets the album
     * rows the CHAT shows them, hidden ones included (owner decision 2026-11-19, "Match the chat"). With the
     * channel's defaults that is the whole album; someone who left or was removed gets no more than any other
     * non-member, and a plain JOINED row (no guest marker) is bookkeeping, never a grant.
     */
    @Test
    public void aChatGuestOffTheRosterGetsTheAlbumTheChatShowsThem() throws Exception {
        signedInAs(USER);
        final org.paulsens.trip.dynamo.DAO dao = org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        final org.paulsens.trip.model.Trip trip = albumTrip(org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID);
        final String slot = "tripChat-" + trip.getId();
        Mockito.when(media.getDiscoverableInSlot(slot)).thenReturn(List.of(
                chatRow(trip.getId(), "old-hidden", 60 * 48, true), chatRow(trip.getId(), "new", -1, false)));
        assertError(resource.inSlot(slot), 404, ApiErrors.NOT_FOUND);

        final org.paulsens.trip.model.chat.ChatChannel channel = new org.paulsens.trip.action.ChatCommands()
                .ensureChannel(trip.getId(), AuditActor.system());
        assertError(resource.inSlot(slot), 404, ApiErrors.NOT_FOUND);
        final org.paulsens.trip.model.chat.ChatMembership guest = org.paulsens.trip.model.chat.ChatMembership
                .guestJoining(channel.getId(), USER, java.time.Instant.now(), "sel");
        Assert.assertTrue(dao.saveChatMembership(guest));

        Assert.assertFalse(resource.isMemberOfSlotTrip(slot), "a guest is not on the roster");
        Assert.assertEquals(idsOf(resource.inSlot(slot)), List.of("old-hidden", "new"),
                "the default chat shows a guest its whole history: the hidden row too");

        Assert.assertTrue(dao.saveChatMembership(guest.withLeft(java.time.Instant.now(), "left")));
        assertError(resource.inSlot(slot), 404, ApiErrors.NOT_FOUND);
        Assert.assertTrue(dao.saveChatMembership(guest.withRemoved(java.time.Instant.now(), "spam", "admin")));
        assertError(resource.inSlot(slot), 404, ApiErrors.NOT_FOUND);
        Assert.assertTrue(dao.saveChatMembership(org.paulsens.trip.model.chat.ChatMembership.joining(
                channel.getId(), USER, java.time.Instant.now())));
        assertError(resource.inSlot(slot), 404, ApiErrors.NOT_FOUND);
    }

    /** With full history for new members off, a guest sees no photo sent before they joined. */
    @Test
    public void aGuestSeesNoPhotoFromBeforeTheyJoinedWhenTheChatHidesHistory() throws Exception {
        final org.paulsens.trip.model.Trip trip = guestOfChat(settings -> settings.fullHistoryForNewMembers(false));
        Mockito.when(media.getDiscoverableInSlot("tripChat-" + trip.getId())).thenReturn(List.of(
                chatRow(trip.getId(), "before", 60, true), chatRow(trip.getId(), "after-hidden", -1, true)));

        Assert.assertEquals(idsOf(resource.inSlot("tripChat-" + trip.getId())), List.of("after-hidden"),
                "the chat hides what came before they joined; what came after shows, hidden or not");
    }

    /** A photo whose message the channel's retention has expired is gone from a guest's album too. */
    @Test
    public void aGuestSeesNoPhotoPastTheChatsRetention() throws Exception {
        final org.paulsens.trip.model.Trip trip = guestOfChat(settings -> settings.retentionSeconds(3_600L));
        Mockito.when(media.getDiscoverableInSlot("tripChat-" + trip.getId())).thenReturn(List.of(
                chatRow(trip.getId(), "expired", 60 * 24, false), chatRow(trip.getId(), "recent", 5, true)));

        Assert.assertEquals(idsOf(resource.inSlot("tripChat-" + trip.getId())), List.of("recent"));
    }

    /**
     * A listed trip's guest gets the chat's rows AND the public ones anyone signed in may browse, in album order:
     * the chat narrows what the album adds for them, never what the public already sees.
     */
    @Test
    public void aListedTripsGuestAlsoGetsThePublicRows() throws Exception {
        final org.paulsens.trip.model.Trip trip = guestOfChat(settings -> settings.fullHistoryForNewMembers(false));
        trip.setOpenToPublic(true);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(trip));
        Mockito.when(media.getDiscoverableInSlot("tripChat-" + trip.getId())).thenReturn(List.of(
                chatRow(trip.getId(), "before-public", 60, false), chatRow(trip.getId(), "before-hidden", 50, true),
                chatRow(trip.getId(), "after-hidden", -1, true)));

        Assert.assertEquals(idsOf(resource.inSlot("tripChat-" + trip.getId())),
                List.of("before-public", "after-hidden"));
    }

    /** A CFPW trip USER is not on, whose chat has these settings and admits USER as a guest joined now. */
    private org.paulsens.trip.model.Trip guestOfChat(final java.util.function.UnaryOperator<
            org.paulsens.trip.model.chat.ChatSettings.ChatSettingsBuilder> settings) throws Exception {
        signedInAs(USER);
        final org.paulsens.trip.dynamo.DAO dao = org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        final org.paulsens.trip.model.Trip trip = albumTrip(org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID);
        final org.paulsens.trip.model.chat.ChatChannel channel = new org.paulsens.trip.action.ChatCommands()
                .ensureChannel(trip.getId(), AuditActor.system());
        Assert.assertTrue(dao.saveChatChannel(channel.withSettings(
                settings.apply(channel.getSettings().toBuilder()).build())));
        Assert.assertTrue(dao.saveChatMembership(org.paulsens.trip.model.chat.ChatMembership.guestJoining(
                channel.getId(), USER, java.time.Instant.now(), "sel")));
        return trip;
    }

    @Test
    public void aBlankSlotNameIsRefused() {
        signedInAs(USER);

        assertError(resource.inSlot(" "), 400, ApiErrors.BAD_REQUEST);
    }

    /**
     * A chat slot is one trip's album and its name is guessable ({@code tripChat-{tripId}}): a non-admin
     * may browse it only as that trip's member, or when the trip is publicly listed on THIS site --
     * otherwise it reads as absent, so one tenant cannot enumerate another's album by trip id.
     */
    @Test
    public void aChatSlotIsBrowsableOnlyByMembersOrWhenTheTripListsOnThisSite() throws Exception {
        signedInAs(USER);
        Mockito.when(media.getVisibleInSlot(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        final org.paulsens.trip.dynamo.DAO dao = org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();

        // A hosted org's public trip: off the shared site by default, and USER is not on it.
        assertError(resource.inSlot("tripChat-" + org.paulsens.trip.dynamo.FakeData.ACME_TRIP_ID), 404,
                ApiErrors.NOT_FOUND);
        assertError(resource.inSlot("tripChat-no-such-trip"), 404, ApiErrors.NOT_FOUND);

        // A shared-tier org's public trip lists on the shared site: browsable by anyone signed in.
        final org.paulsens.trip.model.Trip open = org.paulsens.trip.model.Trip.builder()
                .id("mr-open-" + System.nanoTime()).title("Open").build();
        open.setOrgId(org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID);
        open.setOpenToPublic(true);
        Assert.assertTrue(dao.saveTrip(open));
        final org.paulsens.trip.model.Trip reread =
                dao.getTrip(open.getId(), org.paulsens.trip.cache.Cached.YES).orElse(null);
        Assert.assertNotNull(reread, "the saved trip must be readable");
        Assert.assertEquals(reread.getOpenToPublic(), Boolean.TRUE, "openToPublic survives the save");
        Assert.assertEquals(reread.getOrgId(), org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID, "orgId survives");
        Assert.assertEquals(org.paulsens.trip.action.ChatPhotos.tripOfSlot("tripChat-" + open.getId()), open.getId());
        Assert.assertEquals(resource.personId(), USER, "signed in as USER");
        Assert.assertTrue(resource.mayBrowseSlot("tripChat-" + open.getId()),
                "gate: people=" + reread.getPeople() + " open=" + reread.getOpenToPublic());
        Assert.assertTrue(org.paulsens.trip.site.ListingScope.forSite()
                .shows(org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID), "CFPW lists on the shared site");
        assertOk(resource.inSlot("tripChat-" + open.getId()));

        // A private trip USER belongs to: browsable as a member.
        final org.paulsens.trip.model.Trip mine = org.paulsens.trip.model.Trip.builder()
                .id("mr-mine-" + System.nanoTime()).title("Mine").build();
        mine.setOrgId(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID);
        mine.setOpenToPublic(false);
        mine.setPeople(new java.util.ArrayList<>(List.of(USER)));
        Assert.assertTrue(dao.saveTrip(mine));
        final org.paulsens.trip.model.Trip mineReread =
                dao.getTrip(mine.getId(), org.paulsens.trip.cache.Cached.YES).orElseThrow();
        Assert.assertTrue(mineReread.getPeople().contains(USER), "membership survives: " + mineReread.getPeople());
        Assert.assertTrue(resource.mayBrowseSlot("tripChat-" + mine.getId()), "member gate");
        // A member gets the WHOLE album, hidden rows included (the website's trip media page shows them
        // too): the uploader must be able to find a photo they kept off the public site to change their mind.
        Mockito.when(media.getDiscoverableInSlot("tripChat-" + mine.getId()))
                .thenReturn(List.of(item("m-mine", "chat/" + mine.getId() + "/a.jpg", true)));
        final Response mineResponse = resource.inSlot("tripChat-" + mine.getId());
        assertOk(mineResponse);
        Assert.assertEquals(((List<?>) mineResponse.getEntity()).size(), 1, "the hidden row is served to a member");
        Mockito.verify(media, Mockito.never()).getVisibleInSlot(ArgumentMatchers.eq("tripChat-" + mine.getId()),
                ArgumentMatchers.anyInt());
        Assert.assertFalse(resource.isMemberOfSlotTrip("tripChat-" + open.getId()), "a public viewer is not a member");
        Assert.assertFalse(resource.isMemberOfSlotTrip("home-docs"), "only chat slots have a roster");

        // A site admin (a global media admin) manages every album. A resource memoizes its caller, so the
        // second identity needs its own resource.
        signedInAsSiteAdmin(ADMIN);
        final MediaResource adminResource = resource(new MediaResource());
        Mockito.when(media.getDiscoverableInSlot(ArgumentMatchers.anyString())).thenReturn(List.of());
        // A trip of its own rather than the seeded Acme one: other suites delete that from the shared store.
        assertOk(adminResource.inSlot("tripChat-"
                + albumTrip(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID).getId()));
        assertError(adminResource.inSlot("tripChat-no-such-trip"), 404, ApiErrors.NOT_FOUND);
    }

    /**
     * An org-scoped mediaAdmin is a media administrator on that org's own site only: the admin branch of
     * the API on the org host, an ordinary caller on the shared site.
     */
    @Test
    public void anOrgScopedMediaAdminAdministersOnlyOnItsOrgsSite() throws Exception {
        final Person.Id editor = Person.Id.from("api-acme-media-editor");
        final org.paulsens.trip.action.PrivilegeCommands priv = new org.paulsens.trip.action.PrivilegeCommands();
        org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        priv.savePrivilege(priv.createPrivilege(org.paulsens.trip.action.PrivilegeCommands.MEDIA_ADMIN, "acme",
                org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID, List.of(editor)));
        signedInAs(editor);
        Mockito.when(media.getAll()).thenReturn(List.of());
        assertError(resource.list(), 403, ApiErrors.FORBIDDEN);

        final MediaResource onAcme = resource(new MediaResource());
        final org.paulsens.trip.site.SiteContext acme = org.paulsens.trip.site.SiteContext.org(
                org.paulsens.trip.model.Organization.Id.from(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID),
                "acme", "acme.localhost");
        final Response listed = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(onAcme::list);
        assertOk(listed);
    }

    /** Expense receipts are served only by their own gated servlet: absent here even to a media admin. */
    @Test
    public void receiptsAreAbsentEvenToAnAdmin() {
        signedInAsSiteAdmin(ADMIN);
        final MediaResource adminResource = resource(new MediaResource());
        assertError(adminResource.inSlot("receipts-trip-1"), 404, ApiErrors.NOT_FOUND);
        final MediaItem receipt = new MediaItem("r1", "receipts/trip-1/r.pdf", "r", null, "application/pdf", 9L,
                "receipts-trip-1", 0, LocalDateTime.of(2026, 8, 1, 12, 0), "who@example.com", null, true);
        // Not manageable as library media (mayManage refuses a receipt), and absent even if it were visible.
        Mockito.when(media.getVisible("r1")).thenReturn(receipt);
        assertError(adminResource.get("r1"), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(media, Mockito.never()).getDiscoverableInSlot(ArgumentMatchers.anyString());
    }

    /** Hidden reads as absent for a non-admin: 404, not a redacted body. */
    @Test
    public void gettingOneItemRedactsByPrivilege() {
        signedInAs(USER);
        Mockito.when(media.getVisible("m1")).thenReturn(null);
        assertError(resource.get("m1"), 404, ApiErrors.NOT_FOUND);

        signedInAsSiteAdmin(ADMIN);
        final MediaResource adminResource = resource(new MediaResource());
        final MediaItem hidden = item("m1", "downloads/a.pdf", true);
        Mockito.when(media.getManageable("m1")).thenReturn(hidden);
        Mockito.when(media.getUrl(hidden)).thenReturn("https://cdn.example/downloads/a.pdf");

        final Response response = adminResource.get("m1");

        assertOk(response);
        Assert.assertTrue(((MediaItemDto) response.getEntity()).hidden());
    }

    /**
     * F10, by id: an admin reads hidden rows only where they could WRITE them (the ownership rule). A row
     * that is not theirs to manage here is what it is to everybody else: visible or absent.
     */
    @Test
    public void anAdminGetsAForeignItemOnlyAsThePublicWould() {
        signedInAsSiteAdmin(ADMIN);
        final MediaItem foreignVisible = item("m-v", "org/other/a.pdf", null);
        Mockito.when(media.getManageable(ArgumentMatchers.anyString())).thenReturn(null);
        Mockito.when(media.getVisible("m-v")).thenReturn(foreignVisible);
        Mockito.when(media.getVisible("m-hidden")).thenReturn(null);
        Mockito.when(media.getUrl(foreignVisible)).thenReturn("https://cdn.example/org/other/a.pdf");

        assertOk(resource.get("m-v"));
        assertError(resource.get("m-hidden"), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(media, Mockito.never()).get(ArgumentMatchers.anyString());
    }

    /**
     * F25: a library confirm or edit cannot put a file into a trip's album, a hotel's gallery or a report's
     * receipts. Those slots are listed by their own features with no org filter, so a free-text slot was a
     * way into another organization's album.
     */
    @Test
    public void libraryWritesCannotTargetAnotherFeaturesSlot() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        for (final String slot : List.of("tripChat-11111111-2222-3333-4444-555555555555", "lodging-acc-1",
                "receipts-trip-1")) {
            assertError(resource.confirm(CSRF_OK, new MediaResource.ConfirmRequest("downloads/a.jpg", "T", null,
                    slot, null, null)), 422, ApiErrors.VALIDATION_FAILED);
        }
        Mockito.verify(media, Mockito.never()).confirmUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        final MediaItem library = item("m1", "downloads/a.jpg", null);
        Mockito.when(media.getManageable("m1")).thenReturn(library);
        assertError(resource.update(CSRF_OK, "m1", new MediaResource.UpdateRequest(null, "T", null,
                "tripChat-11111111-2222-3333-4444-555555555555", null, null)), 422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).update(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());

        // An album photo edited in place keeps its slot: unchanged is not a move.
        final MediaItem albumPhoto = new MediaItem("m2", "chat/t/a.jpg", "T", null, "image/jpeg", 9L, "tripChat-t",
                0, LocalDateTime.of(2026, 8, 1, 12, 0), "who", null, null, null);
        Mockito.when(media.getManageable("m2")).thenReturn(albumPhoto);
        Mockito.when(media.update(ArgumentMatchers.eq("m2"), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.eq("tripChat-t"), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any())).thenReturn(true);
        assertOk(resource.update(CSRF_OK, "m2", new MediaResource.UpdateRequest(null, "New title", null,
                "tripChat-t", null, null)));
    }

    @Test
    public void anUploadUrlRequestIsValidatedBeforeAnythingIsSigned() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest(" ", "application/pdf", 1L)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", " ", 1L)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", null)),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 0L)),
                400, ApiErrors.BAD_REQUEST);
        Mockito.verify(media, Mockito.never()).presignUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong());
    }

    @Test
    public void anOversizedUploadIsRefusedBeforeSigning() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(CSRF_OK, new MediaResource.UploadUrlRequest(
                "downloads/a.pdf", "application/pdf", MediaResource.MAX_UPLOAD_BYTES + 1)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verifyNoInteractions(media);
    }

    /** A key is a public URL path; malformed shapes are refused as malformed, not signed. */
    @Test
    public void malformedKeysAreBadRequests() {
        signedInAsSiteAdmin(ADMIN);

        for (final String bad : List.of("down loads/a.pdf", "downloads\\a.pdf", "downloads/../secret.pdf")) {
            assertError(resource.uploadUrl(CSRF_OK,
                    new MediaResource.UploadUrlRequest(bad, "application/pdf", 1L)),
                    400, ApiErrors.BAD_REQUEST);
        }
        Mockito.verifyNoInteractions(media);
    }

    /** profilePics/ and chat/ belong to features that index those prefixes; the API must not write there. */
    @Test
    public void keysInAnotherFeaturesNamespaceAreRefused() {
        signedInAsSiteAdmin(ADMIN);

        for (final String reserved : List.of("profilePics/p1/1-1.jpg", "chat/trip-1/photo.jpg",
                "receipts/trip-1/r.pdf")) {
            assertError(resource.uploadUrl(CSRF_OK,
                    new MediaResource.UploadUrlRequest(reserved, "image/jpeg", 1L)),
                    422, ApiErrors.VALIDATION_FAILED);
            assertError(resource.confirm(CSRF_OK,
                    new MediaResource.ConfirmRequest(reserved, null, null, null, null, null)),
                    422, ApiErrors.VALIDATION_FAILED);
        }
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void anUploadUrlNeedsABucketToPointAt() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                503, ApiErrors.STORE_FAILED);
    }

    /** The JSF page can layer rows over one object; the API refuses to import that accident. */
    @Test
    public void aKeyAlreadyClaimedByARowIsAConflict() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.getByKey("downloads/a.pdf")).thenReturn(item("m1", "downloads/a.pdf", null));

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                409, ApiErrors.CONFLICT);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                409, ApiErrors.CONFLICT);
    }

    @Test
    public void aFailedPresignIsAServerError() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.presignUpload("downloads/a.pdf", "application/pdf", 1L)).thenReturn(null);

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                500, ApiErrors.INTERNAL);
    }

    /** A direct upload's bytes are never seen here, so a type that can run on the media host is not signed. */
    @Test
    public void anUploadUrlIsRefusedForATypeThatCouldRunOnTheMediaHost() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/guide.html", "text/html", 9L)),
                422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/logo.svg", "image/svg+xml", 9L)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).presignUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong());
    }

    @Test
    public void anUploadUrlAnswersTheSignedUrlAndItsTerms() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        // Signed for the key as it will be stored (normalized, site-keyed), not the raw request string.
        Mockito.when(media.presignUpload("downloads/a.pdf", "application/pdf", 9L))
                .thenReturn("https://bucket.s3/downloads/a.pdf?sig");

        final Response response = resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("/downloads/a.pdf", "application/pdf", 9L));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("url"), "https://bucket.s3/downloads/a.pdf?sig");
        // The normalized key, because that is what the signature covers and what the confirm must repeat.
        Assert.assertEquals(body.get("key"), "downloads/a.pdf");
        Assert.assertEquals(body.get("expiresInSeconds"), MediaCommands.UPLOAD_URL_TTL.toSeconds());
    }

    /** The confirm is a claim; an object that is not actually in S3 must not become a listed row. */
    @Test
    public void confirmingAnUploadThatNeverHappenedIsRefused() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf")).thenReturn(java.util.Optional.empty());

        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", "T", null, null, null, null)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).confirmUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void confirmNeedsAKeyAndABucket() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.confirm(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest(" ", null, null, null, null, null)), 400, ApiErrors.BAD_REQUEST);

        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                503, ApiErrors.STORE_FAILED);
    }

    @Test
    public void confirmingRecordsTheRowAndStampsTheActor() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final MediaItem saved = item("m2", "downloads/a.pdf", null);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf"))
                .thenReturn(java.util.Optional.of(new MediaCommands.StoredObject(9L, "application/pdf")));
        Mockito.when(media.confirmUpload("downloads/a.pdf", "Guide", "d", "home-docs", 2,
                "admin@example.com", Boolean.TRUE)).thenReturn(saved);
        Mockito.when(media.getUrl(saved)).thenReturn("https://cdn.example/downloads/a.pdf");

        final Response response = resource.confirm(CSRF_OK, new MediaResource.ConfirmRequest(
                "downloads/a.pdf", "Guide", "d", "home-docs", 2, Boolean.TRUE));

        assertOk(response);
        Assert.assertEquals(((MediaItemDto) response.getEntity()).id(), "m2");
    }

    @Test
    public void aConfirmTheStoreRefusesIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf"))
                .thenReturn(java.util.Optional.of(new MediaCommands.StoredObject(9L, "application/pdf")));
        Mockito.when(media.confirmUpload(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void updatingAnUnknownItemIs404() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getManageable("nope")).thenReturn(null);

        assertError(resource.update(CSRF_OK, "nope",
                new MediaResource.UpdateRequest(null, "T", null, null, null, null)), 404, ApiErrors.NOT_FOUND);
        assertError(resource.update(CSRF_OK, "nope", null), 400, ApiErrors.BAD_REQUEST);
    }

    /** A rename must not steal a key another row already owns, nor move into a reserved namespace. */
    @Test
    public void renamingGuardsTheTargetKey() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getManageable("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.getByKey("downloads/b.pdf")).thenReturn(item("m2", "downloads/b.pdf", null));

        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest("downloads/b.pdf", "T", null, null, null, null)),
                409, ApiErrors.CONFLICT);
        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest("profilePics/p1.jpg", "T", null, null, null, null)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).update(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());
    }

    /** An omitted hidden flag preserves what the row already says instead of quietly unhiding it. */
    @Test
    public void updatePreservesHiddenWhenTheBodyIsSilent() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final MediaItem hidden = item("m1", "downloads/a.pdf", true);
        Mockito.when(media.getManageable("m1")).thenReturn(hidden);
        Mockito.when(media.getUrl(hidden)).thenReturn("url");
        Mockito.when(media.update("m1", null, "New title", "d", "slot", 3, true, "admin@example.com"))
                .thenReturn(true);

        final Response response = resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest(null, "New title", "d", "slot", 3, null));

        assertOk(response);
        Mockito.verify(media).update("m1", null, "New title", "d", "slot", 3, true, "admin@example.com");
    }

    @Test
    public void aFailedUpdateIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getManageable("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.update(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest(null, "T", null, null, null, null)), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void deletingRemovesTheRowAndNamesWhatWentAway() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(media.getManageable("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.delete("m1", "admin@example.com")).thenReturn(true);

        final Response response = resource.delete(CSRF_OK, "m1");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("deleted"), "m1");
        Assert.assertEquals(body.get("key"), "downloads/a.pdf");
    }

    @Test
    public void deletingAnUnknownItemIs404AndAFailedDeleteIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getManageable("nope")).thenReturn(null);
        assertError(resource.delete(CSRF_OK, "nope"), 404, ApiErrors.NOT_FOUND);

        Mockito.when(media.getManageable("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.delete(ArgumentMatchers.eq("m1"), ArgumentMatchers.any())).thenReturn(false);
        assertError(resource.delete(CSRF_OK, "m1"), 500, ApiErrors.STORE_FAILED);
    }

    /**
     * A row the caller may not manage on THIS site -- another tenant's, or the shared site's from an org
     * host -- reads as absent on update and delete: the commands' ownership rule decides, the resource
     * only asks, and no write is attempted.
     */
    @Test
    public void aForeignRowIs404OnUpdateAndDelete() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getManageable("theirs")).thenReturn(null);
        Mockito.when(media.get("theirs")).thenReturn(item("theirs", "downloads/theirs.pdf", null));

        assertError(resource.update(CSRF_OK, "theirs",
                new MediaResource.UpdateRequest(null, "T", null, null, null, null)), 404, ApiErrors.NOT_FOUND);
        assertError(resource.delete(CSRF_OK, "theirs"), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(media, Mockito.never()).update(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());
        Mockito.verify(media, Mockito.never()).delete(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** On an organization's host the caller's key is stored under the org's namespace, both steps. */
    @Test
    public void onAnOrgHostTheUploadKeyIsNamespacedUnderTheOrg() throws Exception {
        org.paulsens.trip.dynamo.DAO.getInstance();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final String stored = MediaCommands.ORG_KEY_PREFIX + org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID
                + "/downloads/a.pdf";
        final MediaItem saved = item("m9", stored, null);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.presignUpload(stored, "application/pdf", 9L)).thenReturn("https://s3/x?sig");
        Mockito.when(media.statObject(stored))
                .thenReturn(java.util.Optional.of(new MediaCommands.StoredObject(9L, "application/pdf")));
        Mockito.when(media.confirmUpload(stored, "T", null, "home-docs", 0, "admin@example.com", null))
                .thenReturn(saved);
        Mockito.when(media.getUrl(saved)).thenReturn("https://cdn/x");
        final org.paulsens.trip.site.SiteContext acme = org.paulsens.trip.site.SiteContext.org(
                org.paulsens.trip.model.Organization.Id.from(org.paulsens.trip.dynamo.FakeData.ACME_ORG_ID),
                "acme", "acme.localhost");

        final Response signed = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(() -> resource.uploadUrl(CSRF_OK,
                        new MediaResource.UploadUrlRequest("/downloads/a.pdf", "application/pdf", 9L)));
        assertOk(signed);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) signed.getEntity();
        Assert.assertEquals(body.get("key"), stored, "the response names the key as stored");

        final Response confirmed = ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(() -> resource.confirm(CSRF_OK,
                        new MediaResource.ConfirmRequest(stored, "T", null, "home-docs", 0, null)));
        assertOk(confirmed);
        Assert.assertEquals(((MediaItemDto) confirmed.getEntity()).id(), "m9");

        // Another tenant's namespace, and any org namespace from the shared host, is refused unsigned.
        final String betas = MediaCommands.ORG_KEY_PREFIX + org.paulsens.trip.dynamo.FakeData.BETA_ORG_ID
                + "/downloads/a.pdf";
        assertError(ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(() -> resource.uploadUrl(CSRF_OK,
                        new MediaResource.UploadUrlRequest(betas, "application/pdf", 9L))),
                422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.uploadUrl(CSRF_OK, new MediaResource.UploadUrlRequest(stored, "application/pdf", 9L)),
                422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.confirm(CSRF_OK, new MediaResource.ConfirmRequest(stored, "T", null, null, 0, null)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.times(1)).presignUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong());
    }

    /** The refresh must pass the REST edge's actor: AuditActor.current() records nobody off a Faces thread. */
    @Test
    public void refreshingReportsTheCountAndCarriesTheActor() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(media.refreshFromDatabase(ArgumentMatchers.any(AuditActor.class))).thenReturn(42);

        final Response response = resource.refresh(CSRF_OK);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("items"), 42);
        Mockito.verify(media, Mockito.never()).refreshFromDatabase();
    }

    @Test
    public void theProducedTypeIsTheMediaMediaType() {
        Assert.assertEquals(new MediaResource().versionedType(), ApiMediaTypes.MEDIA_V1);
    }


    /** Locally the bases are this server's own servlets, made absolute from the request; both end in a slash. */
    @Test
    public void basesNameTheServletsLocallyAndAreAbsolute() {
        signedInAs(USER);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        Mockito.when(request.getScheme()).thenReturn("http");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);

        final Response response = resource.bases();
        assertOk(response);
        final org.paulsens.trip.api.dto.MediaBasesDto bases =
                (org.paulsens.trip.api.dto.MediaBasesDto) response.getEntity();
        Assert.assertEquals(bases.chatPhotos(), "http://localhost:8080/chat-photos/");
        Assert.assertEquals(bases.profilePhotos(), "http://localhost:8080/profile-photos/");
        Assert.assertFalse(bases.remote(), "no bucket in tests");
    }
}
