package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Getter;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.content.MarketingPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.security.ProcessorSecrets;
import org.paulsens.trip.model.Person.Sex;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import org.paulsens.trip.cache.Cached;

public final class FakeData {

    private FakeData() {
    }

    @Getter
    private static List<Person> fakePeople;
    @Getter
    private static List<Trip> fakeTrips;

    /**
     * Whether this JVM is in local mode.
     *
     * <p>Delegates to {@link LocalMode}, which resolves the answer once at context startup. This used to read
     * {@code FacesContext} directly and report local whenever there was none -- so every non-request thread in a
     * real deployment (a startup listener, a JAX-RS resource, a scheduled task) said "local", and whichever of
     * them reached the {@code DAO} singleton first made the whole deployment serve fake data. See
     * {@link LocalMode} for the incident and why the default is now production.
     */
    public static boolean isLocal() {
        return LocalMode.isLocal();
    }

    public static void initFakeData() {
        fakePeople = initFakePeople();
        fakeTrips = initFakeTrips();
    }

    /**
     * The fake datastore for local mode and tests.
     *
     * <p>Every table answers the way {@link Persistence}'s defaults do -- empty reads, successful writes --
     * EXCEPT {@code audit}, which is backed by a real in-memory table.
     *
     * <p>The exception is needed because {@link AuditDAO} is deliberately uncached, so it is the one DAO whose
     * reads go all the way to the datastore. With the plain fake, an audit record written locally could never
     * be read back: the admin page would be permanently empty on a laptop, and any test of it could only ever
     * assert that the page renders -- not that the feature works. Which is exactly the gap that let a
     * reserved-word bug reach production.
     */
    public static Persistence createFakePersistence() {
        return new InMemoryPersistence();
    }

    public static Persistence createFakePersistenceWithQueryMonitor(
            final Consumer<Consumer<QueryRequest.Builder>> whenQueryCalled) {
        return new Persistence() {
            @Override
            public QueryResponse query(Consumer<QueryRequest.Builder> queryRequest) {
                whenQueryCalled.accept(queryRequest);
                return QueryResponse.builder().items(new ArrayList<>()).build();
            }
        };
    }

    public static void addFakeData() {
        if (isLocal()) {
            // Setup some sample data
            FakeData.getFakePeople().forEach(p -> {
                try {
                    DAO.getInstance().savePerson(p);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
            FakeData.getFakeTrips().forEach(t -> {
                try {
                    DAO.getInstance().saveTrip(t);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
            addFakeMedia();
            addFakePrivileges();
            addFakeContent();
            addFakeFamily();
            addFakeOrgs();
            addFakeLodging();
            relaxChatLimitsForFakeTrips();
        }
    }

    /**
     * The lodging demo: "Pansion Dragićević" with the exact inventory the old hard-coded rooms page listed
     * (floors 0, 1, 2; the notes are the pansion's own), one PER_ROOM offer on the Spring Demo trip tracking
     * its existing LODGING event, Dave (user5) alone in room 114 for the whole stay, and Matt (user6)
     * joining him four days later under his OWN reservation -- the late-arriver case the itinerary override
     * exists for. Bills recompute, so the local ledger shows lodging rows. Through the REAL writers, as a site
     * admin, and idempotent (the accommodation's fixed id is the guard). The two people are deliberately the
     * ones no legacy room test writes a free-text room for.
     */
    private static void addFakeLodging() {
        final Person admin = DAO.getInstance().getPersonByEmail(localEmail("admin"), Cached.NO);
        if (admin == null) {
            return;
        }
        final org.paulsens.trip.action.LodgingCommands lodging = new org.paulsens.trip.action.LodgingCommands(
                () -> new org.paulsens.trip.action.Caller(admin.getId(), true,
                        org.paulsens.trip.audit.AuditActor.system(),
                        new org.paulsens.trip.action.PrivilegeCommands()));
        if (DAO.getInstance().getAccommodation(
                org.paulsens.trip.model.Accommodation.Id.from(CFPW_ACCOMMODATION_ID), Cached.NO).isEmpty()) {
            seedPansion(lodging, admin);
        }
        // Reservations are guarded separately: every initFakeData mints fresh person ids (the trips are
        // re-saved with them), so the CURRENT Dave and Matt must hold the demo reservations, whoever held
        // them under the previous ids.
        final String offerId = lodging.defaultOfferId(FAKE_TRIP_ID);
        // From the static people list, not the email index: these are the ids the re-saved roster holds.
        final Person dave = fakePersona("user5");
        final Person matt = fakePersona("user6");
        if (offerId.isEmpty() || dave == null || matt == null) {
            throw new IllegalStateException("Fake lodging seed: the offer or the user5/user6 personas are missing");
        }
        final org.paulsens.trip.model.ReservationOffer offer = lodging.findOffer(FAKE_TRIP_ID, offerId);
        final String room114 = lodging.findAccommodation(CFPW_ACCOMMODATION_ID).getRooms().stream()
                .filter(room -> "114".equals(room.getRoomNumber())).findFirst().orElseThrow().getId();
        if (lodging.activeReservationsFor(FAKE_TRIP_ID, dave.getId()).isEmpty()) {
            seedReservation(lodging, offer, dave, room114, 0, null);
        }
        if (lodging.activeReservationsFor(FAKE_TRIP_ID, matt.getId()).isEmpty()) {
            seedReservation(lodging, offer, matt, room114, 4, "Arrives four days after the group (late flight).");
        }
    }

    private static void seedPansion(final org.paulsens.trip.action.LodgingCommands lodging, final Person admin) {
        final org.paulsens.trip.model.Accommodation pansion = org.paulsens.trip.model.Accommodation.builder()
                .id(org.paulsens.trip.model.Accommodation.Id.from(CFPW_ACCOMMODATION_ID))
                .name("Pansion Dragićević")
                .description("Family-run pansion at the foot of Apparition Hill; 2 singles, 15 doubles, 3 triples.")
                .address(pansionAddress())
                .email("pansion@example.com").phone("+387 36 651 000").website("https://pansion.example")
                .orgIds(List.of(Organization.Id.from(CFPW_ORG_ID)))
                .createdBy(admin.getId()).created(LocalDateTime.now())
                .build();
        seedPansionInventory(pansion);
        final Person.Id contact = lodging.findOrCreateContact("pansion@example.com", "Ivan Dragićević", CFPW_ORG_ID);
        pansion.setContactId(contact);
        try {
            if (!DAO.getInstance().saveAccommodation(pansion)) {
                throw new IllegalStateException("Fake lodging seed: could not save the pansion");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake lodging seed: could not save the pansion", ex);
        }
        if (contact == null || !lodging.addManager(CFPW_ACCOMMODATION_ID, contact)
                || !lodging.addManager(CFPW_ACCOMMODATION_ID, admin.getId())) {
            throw new IllegalStateException("Fake lodging seed: could not grant accommodationAdmin");
        }
        final org.paulsens.trip.action.LodgingViews.OfferForm offer =
                new org.paulsens.trip.action.LodgingViews.OfferForm();
        offer.setName("Double room, shared");
        offer.setAccommodationId(CFPW_ACCOMMODATION_ID);
        offer.setRoomTypeIds(new java.util.ArrayList<>(java.util.List.of(pansion.getRoomTypes().get(1).getId())));
        offer.setTripEventId(FAKE_TRIP_LODGING_EVENT_ID);
        offer.setPricingModel("PER_ROOM");
        offer.setNightlyPrice(60.0);
        offer.setValidFrom(fakeStayStart().toLocalDate().atStartOfDay());
        offer.setValidUntil(fakeStayEnd().toLocalDate().atTime(23, 59));
        offer.setDefaultStart(fakeStayStart());
        offer.setDefaultEnd(fakeStayEnd());
        offer.setCancelFeeKind("PERCENT");
        offer.setCancelFeeAmount(10.0);
        offer.setPolicyHtml("<p>Cancellations within 30 days of arrival forfeit 10% of the stay.</p>");
        if (!lodging.saveOffer(FAKE_TRIP_ID, offer)) {
            throw new IllegalStateException("Fake lodging seed: could not save the offer");
        }
    }

    private static void seedReservation(final org.paulsens.trip.action.LodgingCommands lodging,
            final org.paulsens.trip.model.ReservationOffer offer, final Person who, final String roomId,
            final int daysLate, final String notes) {
        final org.paulsens.trip.action.LodgingViews.ReservationForm form =
                new org.paulsens.trip.action.LodgingViews.ReservationForm();
        form.setOfferId(offer.getId().getValue());
        form.setPersonIds(List.of(who.getId().getValue()));
        form.setStart(offer.getDefaultStart().plusDays(daysLate));
        form.setEnd(offer.getDefaultEnd());
        form.setRoomId(roomId);
        form.setNotes(notes);
        if (lodging.createReservations(FAKE_TRIP_ID, form) != 1) {
            throw new IllegalStateException("Fake lodging seed: could not reserve for " + who.getEmail());
        }
    }

    /**
     * The demo trip's hotel stay: check in on day 48 at 3pm, check out on day 60 at 10am. The LODGING event
     * and the lodging option that tracks it MUST agree on these to the minute. When they differ, every
     * occupant's itinerary says "(dates from your reservation)" -- a hint meant to mark the late arriver,
     * not the four people who arrived with the group. The event used to take whatever time of day the seed
     * happened to run at, so it always differed.
     */
    private static LocalDateTime fakeStayStart() {
        return LocalDateTime.now().plusDays(48).withHour(15).withMinute(0).withSecond(0).withNano(0);
    }

    private static LocalDateTime fakeStayEnd() {
        return LocalDateTime.now().plusDays(60).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private static Person fakePersona(final String persona) {
        return getFakePeople().stream().filter(p -> localEmail(persona).equals(p.getEmail())).findFirst()
                .orElse(null);
    }

    private static Address pansionAddress() {
        final Address address = new Address("Podbrdo 25", "Medjugorje", null, "88266");
        address.setCountry("Bosnia and Herzegovina");
        return address;
    }

    /** The rooms page's old hard-coded table, room for room: number, max, note; floor = first digit. */
    private static final String[][] PANSION_ROOMS = {
        {"001", "2", "min 2"}, {"002", "3", "min 2"}, {"003", "2", ""}, {"004", "1", ""},
        {"005", "2", "small - single ok"},
        {"101", "2", "min 2 (big)"}, {"102", "2", "2 (1 bed)"}, {"103", "2", "min 2 (big)"}, {"104", "1", ""},
        {"105", "2", "small"}, {"106", "5", "5 (4 beds)"}, {"107", "2", "very nice"}, {"108", "1", "very nice"},
        {"109", "2", "2 (1 bed)"}, {"110", "2", "small but nice"}, {"111", "1", ""}, {"112", "2", "big"},
        {"114", "3", "garden balcony"}, {"115", "2", "app hill balcony, small bath"}, {"116", "2", "big"},
        {"117", "2", "terrace, hang clothes, big bath"},
        {"201", "2", "min 2"}, {"202", "4", "min 2 people"}, {"203", "2", "ok for single"},
        {"204", "2", "ok for single"}, {"205", "2", "ok for single"}, {"206", "2", "ok for single"},
        {"207", "2", "2 (1 bed)"},
    };

    private static void seedPansionInventory(final org.paulsens.trip.model.Accommodation pansion) {
        final java.util.Map<Integer, org.paulsens.trip.model.RoomType> byMax = new java.util.LinkedHashMap<>();
        byMax.put(1, org.paulsens.trip.model.RoomType.builder().name("Single").minPeople(1).maxPeople(1).build());
        byMax.put(2, org.paulsens.trip.model.RoomType.builder().name("Double").minPeople(1).maxPeople(2).build());
        byMax.put(3, org.paulsens.trip.model.RoomType.builder().name("Triple").minPeople(2).maxPeople(3).build());
        byMax.put(4, org.paulsens.trip.model.RoomType.builder().name("Quad").minPeople(2).maxPeople(4).build());
        byMax.put(5, org.paulsens.trip.model.RoomType.builder().name("Family").minPeople(3).maxPeople(5).build());
        pansion.getRoomTypes().addAll(byMax.values());
        for (final String[] row : PANSION_ROOMS) {
            final org.paulsens.trip.model.RoomType type = byMax.get(Integer.parseInt(row[1]));
            pansion.getRooms().add(org.paulsens.trip.model.Room.builder().roomNumber(row[0])
                    .floor(row[0].substring(0, 1)).roomTypeId(type.getId())
                    .notes(row[2].isEmpty() ? null : row[2]).build());
        }
    }

    /** The ceiling {@code ChatCommands.validate} enforces on a channel's burst/sustained limits. */
    private static final int MAX_CHAT_RATE_LIMIT = 10_000;

    /**
     * Gives the demo trips' chat channels limits no test run can trip.
     *
     * <p>The shipped burst limit is 5 messages per 10 SECONDS per person per channel, which is right for people
     * and wrong for a test suite: half a dozen classes drive the Spring Demo trip's channel as the same
     * admin, some through the REST client and some by clicking Send on the page, and the server counts them all
     * against one bucket. The API client paces itself, but it cannot see the page-driven sends, so whether a
     * class overruns depends purely on how the classes interleave -- which is why this passed locally for
     * months and then failed a deploy's integ stage, where the suite runs slower and lands differently. The
     * limiter's own behaviour is covered by {@code ChatRateLimiterTest} against explicit settings; nothing is
     * lost by keeping it out of the way here.
     *
     * <p>LOCAL MODE ONLY, like every other seed in this class: production channels keep the shipped defaults.
     */
    private static void relaxChatLimitsForFakeTrips() {
        final ChatCommands chat = new ChatCommands();
        for (final String tripId : List.of(FAKE_TRIP_ID, FAKE2_TRIP_ID)) {
            final ChatChannel channel = chat.ensureChannel(tripId, AuditActor.system());
            if (channel == null) {
                continue;   // chat disabled for this trip; nothing to relax
            }
            // Exactly the ceiling ChatCommands.validate allows ("Rate limits cannot exceed 10,000"), not a
            // number past it: the admin chat-settings page re-saves whatever it loaded, so a seeded value the
            // form would reject makes every Save on that page fail validation and go nowhere.
            DAO.getInstance().saveChatChannel(channel.withSettings(channel.getSettings().toBuilder()
                    .burstLimit(MAX_CHAT_RATE_LIMIT)
                    .sustainedLimit(MAX_CHAT_RATE_LIMIT)
                    .build()));
        }
    }

    /**
     * Seeds Ken's (user2) family through the REAL {@code FamilyCommands} path -- Trinity (user4) linked as a
     * second manager, plus "Lucy", a created member with no email and no login -- so every boot exercises the
     * create/link/sync machinery and the family page has data to show. Guarded like {@link #savePrivilege}:
     * suite tests re-run addFakeData against a shared store, and a re-seed would duplicate members.
     */
    private static void addFakeFamily() {
        final Person ken = DAO.getInstance().getPersonByEmail(localEmail("user2"), Cached.NO);
        final Person trinity = DAO.getInstance().getPersonByEmail(localEmail("user4"), Cached.NO);
        if (ken == null || trinity == null || ken.getFamilyId() != null) {
            return;
        }
        // A site-admin caller acting as Ken: adminLink needs the privilege short-circuit, and there is no
        // FacesContext at seed time, so the command bean's test seam is the honest way in.
        final org.paulsens.trip.action.FamilyCommands commands = new org.paulsens.trip.action.FamilyCommands(
                new org.paulsens.trip.action.ConfigCommands(), new org.paulsens.trip.action.AuditCommands(),
                () -> new org.paulsens.trip.action.Caller(ken.getId(), true,
                        org.paulsens.trip.audit.AuditActor.system(),
                        new org.paulsens.trip.action.PrivilegeCommands()));
        // Same loud failure the other seeds use: silently-missing fake data wastes debugging time later.
        if (!commands.adminLink(ken.getId(), trinity.getId(), true)) {
            throw new IllegalStateException("Fake family seed: could not link Trinity");
        }
        if (commands.createFamilyMember("Lucy", "Paulsen", LocalDate.of(2016, 4, 12),
                Sex.Female, null, false) == null) {
            throw new IllegalStateException("Fake family seed: could not create Lucy");
        }
    }

    /**
     * The fixed id of the seeded CFPW organization. A CONSTANT (not a minted UUID) so local mode and the
     * production migration script (`medjugorje/scripts/org-migrate.sh`) agree on the same id, and re-seeding
     * converges instead of duplicating. Same idea for the second tenant, "Acme Inc", which exists so the
     * org-isolation rules ("an Acme admin sees only Acme") are demonstrable and webtestable locally.
     */
    public static final String CFPW_ORG_ID = "5f0c2a4e-9d31-4a8e-8f34-6d2f19c7b0a1";
    public static final String ACME_ORG_ID = "a17c3b52-2e84-4d0b-9c66-08d94f2e6b73";
    /**
     * A second HOSTED org ("Beta Corp", site beta.localhost): the tenant-isolation tests need two org sites
     * to prove one never shows the other's content, while CFPW stays a shared-tier org (no site of its own)
     * exactly as in production -- the shared landing page lists CFPW by default and hosted orgs only when
     * curated in.
     */
    public static final String BETA_ORG_ID = "b2e5d7a1-4c39-4f8e-9a60-2d7c1e8f5b34";
    /** The platform's own organization (slug {@code www}): www.localhost is its site, the marketing page. */
    public static final String PLATFORM_ORG_ID = "c4f1e8d2-7a35-4b9c-8e21-5d6f0a3b7c19";
    /** The seeded Pansion (a global accommodation), fixed so re-seeds and webtests agree. */
    public static final String CFPW_ACCOMMODATION_ID = "e7a3c9d1-52b4-4f6e-8a1d-3c5b7e9f0a2d";

    /**
     * Seeds the two demo organizations through the REAL {@code OrgCommands} membership path (so the
     * org_members rows and the derived {@code Person.orgIds} lists stay honest): CFPW holds every fake
     * person, with the admin persona as its org admin; Acme Inc holds Kevin (user3) as its NON-site-admin
     * org admin -- the tenant-isolation demo account. Guarded like every other seed.
     */
    private static void addFakeOrgs() {
        final Person admin = DAO.getInstance().getPersonByEmail(localEmail("admin"), Cached.NO);
        if (admin == null
                || DAO.getInstance().getOrganization(
                        Organization.Id.from(CFPW_ORG_ID), Cached.NO).isPresent()) {
            return;
        }
        // Slugs make the org SITES reachable locally: Chromium resolves {slug}.localhost natively, so
        // http://acme.localhost:8080/ exercises the per-org subdomain path with no hosts-file edit. CFPW
        // deliberately has NO slug -- it is the shared-tier org, as in production.
        seedOrg(CFPW_ORG_ID, "CFPW", "CFPW", null, admin.getId());
        seedOrg(ACME_ORG_ID, "Acme Inc", "Acme", "acme", admin.getId());
        seedOrg(BETA_ORG_ID, "Beta Corp", "Beta", "beta", admin.getId());
        seedOrg(PLATFORM_ORG_ID, "UniteTrip", "UniteTrip", Organization.PLATFORM_SLUG, admin.getId());
        final org.paulsens.trip.action.OrgCommands commands = new org.paulsens.trip.action.OrgCommands(
                () -> new org.paulsens.trip.action.Caller(admin.getId(), true,
                        org.paulsens.trip.audit.AuditActor.system(),
                        new org.paulsens.trip.action.PrivilegeCommands()));
        for (final Person person : getFakePeople()) {
            if (!commands.addMember(CFPW_ORG_ID, person.getId())) {
                throw new IllegalStateException("Fake org seed: could not add " + person.getId() + " to CFPW");
            }
        }
        if (!commands.setOrgAdmin(CFPW_ORG_ID, admin.getId(), true)) {
            throw new IllegalStateException("Fake org seed: could not make admin a CFPW org admin");
        }
        final Person kevin = DAO.getInstance().getPersonByEmail(localEmail("user3"), Cached.NO);
        if (kevin == null || !commands.setOrgAdmin(ACME_ORG_ID, kevin.getId(), true)) {
            throw new IllegalStateException("Fake org seed: could not make Kevin the Acme org admin");
        }
        seedFakeProcessor(CFPW_PROCESSOR_ID, CFPW_ORG_ID, "CFPW Test Processor", admin.getId());
        seedFakeProcessor(ACME_PROCESSOR_ID, ACME_ORG_ID, "Acme Test Processor", kevin.getId());
        seedCfpwPaymentDefaults();
        seedBetaBranding();
        seedPlatformBranding();
        seedOrgScopedPrivileges(commands, kevin);
        seedOrgSiteEditor(commands);
        // Each org SITE gets its default home page through the same once-only seeding a slug assignment
        // triggers in production, so acme.localhost / beta.localhost show what a new tenant sees. Runs after
        // addFakeContent: the starter rows pin the starter templates' current versions.
        for (final String orgId : List.of(ACME_ORG_ID, BETA_ORG_ID)) {
            if (!commands.ensureHomePage(orgId)) {
                throw new IllegalStateException("Fake org seed: could not seed the home page of " + orgId);
            }
        }
    }

    /** Fixed id of the seeded Acme trip, so webtests and re-seeds agree (the fixed-UUID convention). */
    public static final String ACME_TRIP_ID = "3f7a9c15-6d28-4e0b-8a54-1c9e7b3d5f82";

    /*
     * The seeded trips, people and events carry FIXED, CANONICAL UUIDs -- the same shape production mints
     * (Trip.builder, Person.Id.newInstance, TripEvent all use UUID.randomUUID). They were readable strings
     * (a trip called "faketrip", an event "t1e2", the person "admin") from before ids became UUIDs, and that
     * drift was not cosmetic: a privilege scoped to a non-UUID trip id reads back as GLOBAL, so
     * Privilege.requireStorableScope refuses it, and every trip-role write on a demo trip threw instead of
     * saving (trip/edit.jsf, seen 2026-09-06).
     * Fixed rather than random so a re-seed, a bookmarked local URL and the webtests' mirrored copies
     * (medjugorje/webtest .../pw/SeededIds.java) all keep agreeing.
     */

    /** "Spring Demo Trip": the CFPW demo trip most local pages and webtests work against. */
    public static final String FAKE_TRIP_ID = "30692184-e927-4398-b8f9-a6e1eda1ea7b";
    /** "Summer Demo Trip": the legacy-shaped seed (pre-migration state the modern API cannot create). */
    public static final String FAKE2_TRIP_ID = "ba492733-ccc1-4be7-b542-d01469285f71";
    /** The public, joinable seeds behind the landing page and the registration flows. */
    public static final String PUB_EN_1_TRIP_ID = "707c4352-3f5e-498c-aac0-39859c729446";
    public static final String PUB_EN_2_TRIP_ID = "81f829e0-d44c-4dd1-9a9c-76e741e15f09";
    public static final String PUB_ES_1_TRIP_ID = "f0a65a39-3bca-4944-bc1b-87b51ffb0048";
    public static final String PUB_EXT_1_TRIP_ID = "85463bdc-e751-4c31-99da-bd682a4d92fd";
    public static final String PUB_PAST_3D_TRIP_ID = "496bfbde-cede-46fe-a533-c301cd9cf8b7";
    public static final String PUB_PAST_30D_TRIP_ID = "3d1e0db4-03b4-465f-9f80-008191693b90";
    public static final String PUB_HIDDEN_TRIP_ID = "d80b4653-b521-4547-8cf3-a0945f40314f";
    /** The seeded site administrator ("admin"/"admin"); the only fake person with a fixed id. */
    public static final String ADMIN_PERSON_ID = "0e24634e-bbd7-4ad7-9598-02e6f552c5fd";
    /** The Spring trip's LODGING event: the lodging seed hangs its offer on this one. */
    public static final String FAKE_TRIP_LODGING_EVENT_ID = "6564bba7-1b59-4431-ad41-377c019963c9";

    private static final String FAKE_TRIP_PDX_EWR_EVENT_ID = "9cded809-0f4b-45c5-88a9-bd011ec40481";
    private static final String FAKE_TRIP_CHARTER_EVENT_ID = "977c6c52-07b1-441e-9d49-4089602b9b60";
    private static final String FAKE_TRIP_SEA_EWR_EVENT_ID = "17e18119-98a5-42ef-8a5e-b2d942426950";
    private static final String FAKE_TRIP_SPU_SEA_EVENT_ID = "f981c3a4-36e4-49c6-99f8-67268ebf0cfb";
    private static final String FAKE2_SEA_LGW_EVENT_ID = "2912a4b2-fb1d-4efa-bc19-788056300960";
    private static final String FAKE2_LODGING_EVENT_ID = "8892f4d8-0bce-47cb-9284-a72d384ddb6e";
    private static final String FAKE2_DBV_KEF_EVENT_ID = "4a09a5b8-18f9-4907-874d-657d7daa63f3";

    /**
     * Matt (user6), a plain Acme member, is Acme's SITE editor: {@code contentAdmin} and {@code mediaAdmin}
     * scoped to Acme, granted through the real org grant path (Acme's allow-list admits them). He edits
     * acme.localhost's page and library and nothing on the shared site or on beta.localhost -- the
     * privilege-only org-editing fixture. Kevin, Acme's org ADMIN, deliberately holds neither: being an
     * org admin does not edit content.
     */
    private static void seedOrgSiteEditor(final org.paulsens.trip.action.OrgCommands commands) {
        final Person matt = DAO.getInstance().getPersonByEmail(localEmail("user6"), Cached.NO);
        if (matt == null) {
            throw new IllegalStateException("Fake org seed: Matt (user6) is missing");
        }
        for (final String base : List.of(org.paulsens.trip.action.PrivilegeCommands.CONTENT_ADMIN,
                org.paulsens.trip.action.PrivilegeCommands.MEDIA_ADMIN)) {
            if (!commands.grantOrgPrivilege(ACME_ORG_ID, matt.getId(), base)) {
                throw new IllegalStateException("Fake org seed: could not grant " + base + "@Acme to Matt");
            }
        }
    }

    /**
     * The org-scoped privilege fixtures (org migration, 2026-08), granted through the REAL writers so the
     * seeds exercise the same enforcement the pages do:
     * <ul><li>an Acme trip with Kevin on the roster -- the org Trips page has content, and removing Kevin
     *         from Acme demonstrates the on-an-org-trip removal guard;</li>
     *     <li>{@code emailAdmin@CFPW} for user2 and {@code peopleAdmin@CFPW} for user4 -- non-admin members
     *         holding delegated org privileges (menu entries, bounded people/mail pages);</li>
     *     <li>Matt (user6) as a plain Acme member -- the trip editor's Add Manager picker and
     *         {@code setTripRole} are org-membership-bounded, so the manager-roster fixture needs a
     *         non-admin member to appoint (Dave/user5 deliberately stays outside Acme as the negative);</li>
     *     <li>Acme's allow-list restricted to everything EXCEPT {@code paymentsAdmin} -- the site-admin
     *         restriction feature is demoable (Kevin cannot grant what Acme does not have).</li></ul>
     */
    private static void seedOrgScopedPrivileges(final org.paulsens.trip.action.OrgCommands commands,
            final Person kevin) {
        final Trip acmeTrip = Trip.builder()
                .id(ACME_TRIP_ID)
                .title("2027 Jun: Acme Retreat")
                .description("Acme Inc's demo trip (tenant-isolation fixture).")
                .startDate(LocalDateTime.of(2027, 6, 10, 9, 0))
                .endDate(LocalDateTime.of(2027, 6, 20, 17, 0))
                .people(new ArrayList<>(List.of(kevin.getId())))
                .build();
        acmeTrip.setOrgId(ACME_ORG_ID);
        try {
            if (!DAO.getInstance().saveTrip(acmeTrip)) {
                throw new IllegalStateException("Fake org seed: could not save the Acme trip");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake org seed: could not save the Acme trip", ex);
        }
        final Person user2 = DAO.getInstance().getPersonByEmail(localEmail("user2"), Cached.NO);
        final Person user4 = DAO.getInstance().getPersonByEmail(localEmail("user4"), Cached.NO);
        if (user2 == null || user4 == null) {
            throw new IllegalStateException("Fake org seed: user2/user4 personas are missing");
        }
        if (!commands.grantOrgPrivilege(CFPW_ORG_ID, user2.getId(),
                org.paulsens.trip.action.PrivilegeCommands.EMAIL_ADMIN)) {
            throw new IllegalStateException("Fake org seed: could not grant emailAdmin@CFPW to user2");
        }
        if (!commands.grantOrgPrivilege(CFPW_ORG_ID, user4.getId(),
                org.paulsens.trip.action.PrivilegeCommands.PEOPLE_ADMIN)) {
            throw new IllegalStateException("Fake org seed: could not grant peopleAdmin@CFPW to user4");
        }
        // A non-site-admin lodging admin, so the trip Lodging tab and the hotel pages are demoable as Ken.
        if (!commands.grantOrgPrivilege(CFPW_ORG_ID, user2.getId(),
                org.paulsens.trip.action.PrivilegeCommands.LODGING_ADMIN)) {
            throw new IllegalStateException("Fake org seed: could not grant lodgingAdmin@CFPW to user2");
        }
        final Person matt = DAO.getInstance().getPersonByEmail(localEmail("user6"), Cached.NO);
        if (matt == null || !commands.addMember(ACME_ORG_ID, matt.getId())) {
            throw new IllegalStateException("Fake org seed: could not add Matt to Acme");
        }
        final List<String> acmeAllowed = commands.allGrantableBases().stream()
                .filter(base -> !org.paulsens.trip.action.PrivilegeCommands.PAYMENTS_ADMIN.equals(base))
                .toList();
        if (!commands.setGrantablePrivileges(ACME_ORG_ID, acmeAllowed)) {
            throw new IllegalStateException("Fake org seed: could not restrict Acme's allow-list");
        }
    }

    /**
     * Org-level payment defaults so local mode is PAYABLE out of the box: the FAKE processor, payer-pays
     * fees (exercises the gross-up math on screen), donations on, and a from-address (test-send + the
     * confirmation render need one). Every seeded trip with the CFPW org inherits all of it.
     */
    private static void seedCfpwPaymentDefaults() {
        try {
            final Organization cfpw = DAO.getInstance()
                    .getOrganization(Organization.Id.from(CFPW_ORG_ID), Cached.NO).orElseThrow();
            cfpw.getPaymentDefaults().setProcessorConfigId(CFPW_PROCESSOR_ID);
            cfpw.getPaymentDefaults().setFeesPaidBy(org.paulsens.trip.model.FeesPaidBy.PAYER);
            cfpw.getPaymentDefaults().setDonationEnabled(Boolean.TRUE);
            cfpw.getPaymentDefaults().setMailFrom("CFPW <no-reply@example.com>");
            if (!DAO.getInstance().saveOrganization(cfpw)) {
                throw new IllegalStateException("Fake org seed: could not save CFPW payment defaults");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake org seed: could not save CFPW payment defaults", ex);
        }
    }

    /** The platform org's footer line; everything else stays the neutral default (full width is host-driven). */
    private static void seedPlatformBranding() {
        try {
            final Organization platform = DAO.getInstance()
                    .getOrganization(Organization.Id.from(PLATFORM_ORG_ID), Cached.NO).orElseThrow();
            platform.getSettingsOverrides().put("site.footer.text", "Trip sites for organizations");
            if (!DAO.getInstance().saveOrganization(platform)) {
                throw new IllegalStateException("Fake org seed: could not save the platform org's branding");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake org seed: could not save the platform org's branding", ex);
        }
    }

    /**
     * Beta Corp's org-site BRANDING overrides (the Branding section of {@code KnownSettings}, every def
     * org-only): a palette, a logo, footer, contact card, donate link and its own analytics id, so
     * beta.localhost renders the settings-driven chrome and its consent banner. Acme deliberately gets NONE
     * of these: acme.localhost exercises the neutral defaults (wordmark, {@code data:,} favicon, platform
     * theme, no analytics and so no banner). Written straight onto the row: these are fixture values that
     * the editor's save path would accept unchanged, and the seed must not depend on a signed-in caller.
     */
    private static void seedBetaBranding() {
        try {
            final Organization beta = DAO.getInstance()
                    .getOrganization(Organization.Id.from(BETA_ORG_ID), Cached.NO).orElseThrow();
            final Map<String, String> overrides = beta.getSettingsOverrides();
            overrides.put("site.theme.palette", "green");
            overrides.put("site.logo.url", "https://example.com/beta-logo.png");
            overrides.put("site.footer.title", "Beta Corp Travel");
            overrides.put("site.footer.text", "A fixture organization");
            overrides.put("site.contact.name", "Bea Tester");
            overrides.put("site.contact.phone", "555-0100");
            overrides.put("site.donate.url", "https://example.com/give");
            overrides.put("site.analytics.id", "G-BETAFIXTURE");
            if (!DAO.getInstance().saveOrganization(beta)) {
                throw new IllegalStateException("Fake org seed: could not save Beta Corp's branding");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake org seed: could not save Beta Corp's branding", ex);
        }
    }

    /** Fixed FAKE-processor config ids, so webtests and the payment flow agree across re-seeds. */
    public static final String CFPW_PROCESSOR_ID = "0c41d9b7-6e2a-4f58-9a03-8b1c5d7e2f60";
    public static final String ACME_PROCESSOR_ID = "9d82e4c6-1b5f-4a37-8c09-2e6a4f8b1d73";

    private static void seedFakeProcessor(final String id, final String orgId, final String label,
            final Person.Id creator) {
        try {
            final boolean saved = DAO.getInstance().savePaymentProcessorConfig(PaymentProcessorConfig.builder()
                    .id(PaymentProcessorConfig.Id.from(id))
                    .orgId(Organization.Id.from(orgId))
                    .label(label)
                    .type(ProcessorType.FAKE)
                    .mode(PaymentProcessorConfig.ProcessorMode.SANDBOX)
                    .enabled(true)
                    .createdBy(creator)
                    .created(LocalDateTime.now())
                    .build());
            if (!saved) {
                throw new IllegalStateException("Fake processor seed failed: " + label);
            }
            // A stored (fake) secret so the settings page's badges and the ping have data locally.
            ProcessorSecrets.getInstance().put(id, Map.of("clientSecret", "fake-secret"));
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake processor seed failed: " + label, ex);
        }
    }

    private static void seedOrg(final String id, final String name, final String abbr, final String slug,
            final Person.Id creator) {
        try {
            final Organization org = Organization.builder()
                    .id(Organization.Id.from(id))
                    .name(name)
                    .abbreviation(abbr)
                    .createdBy(creator)
                    .created(LocalDateTime.now())
                    .build();
            org.setSlug(slug);
            final boolean saved = DAO.getInstance().saveOrganization(org);
            if (!saved) {
                throw new IllegalStateException("Fake org seed: could not save " + name);
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Fake org seed: could not save " + name, ex);
        }
    }

    /**
     * The privilege ROWS the landing page's containers reference. Membership stays empty -- the fake
     * admin passes every check by site-admin role -- but the rows must EXIST: the editors chips only
     * autocomplete stored names, and the save path drops names matching no stored row, so without these a
     * local-mode container save would silently strip the seeded editorPrivileges.
     */
    private static void addFakePrivileges() {
        savePrivilege(new Privilege("contentAdmin", "Authors templates + edits every section", List.of()));
        savePrivilege(new Privilege("eventAdmin", "Edits the home-page Events section", List.of()));
        savePrivilege(new Privilege("mediaAdmin", "Edits Documents + the media library", List.of()));
    }

    private static void savePrivilege(final Privilege privilege) {
        // Conditional like saveContent: suite tests re-run addFakeData against a shared store, and a
        // re-save would wipe any membership a test granted in the meantime.
        if (DAO.getInstance().getPrivilege(privilege.getId(), Cached.NO).isEmpty()) {
            DAO.getInstance().savePrivilege(privilege);
        }
    }

    /**
     * Media rows exercising every landing-page rule: the Documents section's age window and hidden flag, and
     * the picture-album minimum count and per-photo visibility. Every photo row gets real (tiny) bytes
     * seeded into the local photo store -- see {@link #TINY_JPEG} for why broken images are not benign.
     */
    private static void addFakeMedia() {
        final LocalDateTime now = LocalDateTime.now();
        // Documents slot: one current and visible, one hidden by flag, one aged out of the window.
        saveMedia(new MediaItem("fake-doc-1", "downloads/FakeTravelGuide.pdf", "Fake Travel Guide",
                "Current, visible", "application/pdf", 2_400_000L, "home-docs", 0,
                now.minusDays(10), "fake-seed", null, null));
        saveMedia(new MediaItem("fake-doc-2", "downloads/FakeHiddenDoc.pdf", "Hidden Doc",
                "Hidden by flag", "application/pdf", 1_000_000L, "home-docs", 1,
                now.minusDays(5), "fake-seed", null, Boolean.TRUE));
        saveMedia(new MediaItem("fake-doc-3", "downloads/FakeOldSlides.pdf", "Old Meeting Slides",
                "Aged out", "application/pdf", 9_000_000L, "home-docs", 2,
                now.minusDays(130), "fake-seed", null, null));
        // A curated row in no slot, older than the docs window: the "add an existing document" picker must
        // offer it, and placing it must restart its upload clock or it would never appear on the page.
        saveMedia(new MediaItem("fake-doc-4", "downloads/FakeRegistrationForm.pdf", "Fake Registration Form",
                "Selectable, not yet placed", "application/pdf", 350_000L, null, 0,
                now.minusDays(200), "fake-seed", null, null));
        // An ORG-owned document in the same slot: discoverable on acme.localhost only -- the shared site's
        // Documents section, library and pickers must never surface it.
        saveMedia(new MediaItem("fake-acme-doc", "downloads/FakeAcmeBrochure.pdf", "Acme Brochure",
                "Acme's own document", "application/pdf", 120_000L, "home-docs", 3,
                now.minusDays(2), "fake-seed", null, null, ACME_ORG_ID));
        // The galleria trip's album: 12 photos, one hidden -> 11 visible, above the min-count of 10.
        for (int i = 1; i <= 12; i++) {
            saveMedia(new MediaItem("fake-photo-past-" + i, chatPhotoKey(PUB_PAST_3D_TRIP_ID, i),
                    "Fake photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-" + PUB_PAST_3D_TRIP_ID, 0, now.minusDays(13).plusHours(i), "fake-seed", null,
                    i == 12 ? Boolean.TRUE : null));
            seedPhotoBytes(chatPhotoKey(PUB_PAST_3D_TRIP_ID, i));
        }
        // Below the minimum: no album on the landing page.
        for (int i = 1; i <= 4; i++) {
            saveMedia(new MediaItem("fake-photo-f2-" + i, chatPhotoKey(FAKE2_TRIP_ID, i),
                    "Summer Demo photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-" + FAKE2_TRIP_ID, 0, now.minusDays(2).plusHours(i), "fake-seed", null, null));
            seedPhotoBytes(chatPhotoKey(FAKE2_TRIP_ID, i));
        }
    }

    /**
     * An 8x8 JPEG, so every seeded album row has real bytes behind its key. Rows without servable bytes are
     * not merely ugly locally: each broken img 404s into the app's error page, and error-page renders are
     * JSF views -- a dozen of them evict the landing page's own view from Mojarra's logical-view LRU, and
     * its first postback dies ViewExpiredException. See ChatPhotos.seedLocalObject.
     */
    private static final byte[] TINY_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7"
            + "Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7"
            + "Ozs7Ozs7Ozv/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
            + "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW"
            + "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi"
            + "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC"
            + "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm"
            + "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq"
            + "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwC1RRRXtHGf/9k=");

    private static void seedPhotoBytes(final String key) {
        ChatPhotos.getChatPhotos().seedLocalObject(key, TINY_JPEG, "image/jpeg");
    }

    /** The v2 landing page: every section under {@link #PAGE_KEY}, containers with children, the works. */
    public static final String PAGE_KEY = "page:trip-index";

    /**
     * Seeds the starter templates and a complete v2 page: STANDARD sections (title, intro, reflection),
     * two CONTAINER instances with deliberately guessable ids ({@code events}, {@code docs} -- they double
     * as the page's anchor targets) holding children that prove auto-hide and hidden-media filtering, and
     * PROGRAMMATIC sections for the pilgrimage listings and photo albums.
     */
    private static void addFakeContent() {
        // Idempotent: addFakeData runs once per DAO instance, but tests re-run it against a shared local
        // store, and a re-save of an existing versioned row trips the lost-update guard.
        StarterTemplates.all().stream()
                .filter(t -> DAO.getInstance().getTemplate(t.getId(), Cached.NO).isEmpty())
                .forEach(t -> DAO.getInstance().saveTemplate(t, 5));
        final LocalDateTime now = LocalDateTime.now();
        saveContent(new ContentInstance("fake-title", PAGE_KEY, "Title block",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<div style=\"text-align:center\">"
                        + "<h1 style=\"margin-bottom:0px;\">Visit Queen of Peace</h1>"
                        + "<span style=\"font-size:1.3em\">by Center for Peace West</span></div>")),
                null, 0, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-intro", PAGE_KEY, "Introduction",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<p>Welcome to Visit Queen of Peace — fake local intro.</p>")),
                null, 1, 0, null, "fake-seed"));
        // The Events container: eventAdmin may manage its CHILDREN; the container row itself lives under
        // the page key, so retitling or deleting the container stays contentAdmin-only.
        saveContent(new ContentInstance("events", PAGE_KEY, "Events",
                StarterTemplates.CONTAINER_ID, 1, new HashMap<>(),
                null, 2, 0, null, "fake-seed", List.of("eventAdmin"), null));
        saveContent(new ContentInstance("fake-event-future", "events", "Monthly Prayer Group Meeting",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "First Saturday of every month, 10am — all are welcome.")),
                now.plusDays(30), 0, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-event-past", "events", "Past Peace Mass",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "This event already happened and must not render.")),
                now.minusDays(3), 1, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-pilgrimages-en", PAGE_KEY, "English Medjugorje Pilgrimages",
                StarterTemplates.PILGRIMAGES_ID, 1,
                new HashMap<>(Map.of("language", "English", "cfpwOnly", "false")),
                null, 3, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-pilgrimages-es", PAGE_KEY,
                "Peregrinaciones españolas a Medjugorje",
                StarterTemplates.PILGRIMAGES_ID, 1,
                new HashMap<>(Map.of("language", "Spanish", "cfpwOnly", "false")),
                null, 4, 0, null, "fake-seed"));
        // Reflection is a titleless container so its CHILD's title renders as the heading (a page-level
        // STANDARD section deliberately shows no title -- the title block and intro would sprout ones).
        saveContent(new ContentInstance("reflection", PAGE_KEY, "",
                StarterTemplates.CONTAINER_ID, 1, new HashMap<>(),
                null, 5, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-reflection", "reflection", "Jan 25th, 2026 Reflection",
                StarterTemplates.YOUTUBE_VIDEO_ID, 1,
                new HashMap<>(Map.of("videoUrl", "https://www.youtube.com/watch?v=bW7s8YCJjoI",
                        "caption", "A reflection on Our Lady's monthly message.")),
                null, 0, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-albums", PAGE_KEY, "Pilgrimage Pictures",
                StarterTemplates.PHOTO_ALBUMS_ID, 1, new HashMap<>(),
                null, 6, 0, null, "fake-seed"));
        // The Documents container: File children reference media rows; fake-doc-2 is hidden media, so its
        // child must render nothing on the public page (live filtering, not duplicated state). Its
        // per-instance allow-list admits only File items, so its Add button skips the template picker.
        saveContent(new ContentInstance("docs", PAGE_KEY, "Documents",
                StarterTemplates.CONTAINER_ID, 1, new HashMap<>(),
                null, 7, 0, null, "fake-seed", List.of("mediaAdmin"), List.of(StarterTemplates.FILE_ID)));
        saveContent(new ContentInstance("fake-file-1", "docs", "Travel Guide",
                StarterTemplates.FILE_ID, 1,
                new HashMap<>(Map.of("mediaId", "fake-doc-1")),
                null, 0, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-file-hidden", "docs", "Hidden Doc Link",
                StarterTemplates.FILE_ID, 1,
                new HashMap<>(Map.of("mediaId", "fake-doc-2")),
                null, 1, 0, null, "fake-seed"));
        // The product's own page (www.localhost): the same rows the production bootstrap script installs,
        // pinned to the band templates' current versions, so the full-width layout renders real content.
        MarketingPageBootstrap.rows(FakeData::currentTemplateVersion).forEach(FakeData::saveContent);
    }

    /** The version a seeded content row pins: the template's current one (the starters were just saved). */
    private static int currentTemplateVersion(final String templateId) {
        return DAO.getInstance().getTemplate(templateId, Cached.NO).map(ContentTemplate::getVersion).orElse(1);
    }

    private static void saveMedia(final MediaItem item) {
        if (!DAO.getInstance().saveMedia(item)) {
            throw new IllegalStateException("Failed to seed fake media: " + item.getId());
        }
    }

    private static void saveContent(final ContentInstance instance) {
        if (DAO.getInstance().getContent(instance.getId(), Cached.NO).isPresent()) {
            return;     // already seeded (tests re-run addFakeData against a shared local store)
        }
        if (!DAO.getInstance().saveContent(instance, 5)) {
            throw new IllegalStateException("Failed to seed fake content: " + instance.getId());
        }
    }

    static List<Person> initFakePeople() {
        final List<Person> people = new ArrayList<>();
        people.add(new Person(null, "Joe", "Joseph", "Bob", "Smith", Sex.Male,
                LocalDate.of(1947, 2, 11), null, "u1", null, null, null, null, null, null, null, null));
        people.add(Person.builder()
                .id(Person.Id.from(ADMIN_PERSON_ID))
                .first("admin")
                .last("user")
                .email(localEmail("admin"))
                .build());
        // Every LOGIN PERSONA carries a real address, because a lot of behaviour now turns on having a
        // mailable one -- family-manager grants, the unlink email rule, the chat-digest question -- and a
        // bare persona ("user2") is a login name, not an address. Signing in is unchanged: you still type
        // "user2", and PersonDAO resolves it (local mode only). Joe Smith above keeps a bare "u1" ON
        // PURPOSE: production holds both shapes, and he is data rather than a login, so the invalid-address
        // paths stay exercised without breaking anyone's sign-in.
        people.add(new Person(null, "Ken", "Kenneth", "", "Paulsen", Sex.Male,
                LocalDate.of(1977, 12, 11), null, localEmail("user2"), null, null, null, null, null, null,
                null, null));
        people.add(new Person(null, null, "Kevin", "David", "Paulsen", Sex.Male,
                LocalDate.of(1987, 9, 27), null, localEmail("user3"), null, null, null, null, null, null,
                null, null));
        people.add(new Person(null, "Trinity", "Trinity", "Anne", "Paulsen", Sex.Female,
                LocalDate.of(1979, 12, 11), null, localEmail("user4"), null, null, null, null, null, null,
                null, null));
        people.add(new Person(null, "Dave", "David", "A", "Robinson", Sex.Male,
                LocalDate.of(1999, 1, 30), null, localEmail("user5"), null, null, null, null, null, null,
                null, null));
        people.add(new Person(null, "Matt", "Matthew", null, "Smith", Sex.Male,
                LocalDate.of(2010, 6, 1), null, localEmail("user6"), null, null, null, null, null, null,
                null, null));
        return people;
    }

    /** Where a seeded album photo lives, the same {@code chat/{tripId}/...} shape the upload servlet writes. */
    private static String chatPhotoKey(final String tripId, final int index) {
        return "chat/" + tripId + "/fake-" + index + ".jpg";
    }

    /** The mailable address a persona's seeded Person holds. Signing in still uses the bare persona. */
    static String localEmail(final String persona) {
        return persona.contains("@") ? persona : persona + LOCAL_EMAIL_DOMAIN;
    }

    private static final String LOCAL_EMAIL_DOMAIN = "@example.com";

    static List<Trip> initFakeTrips() {
        // Trip 1
        final List<Trip> trips = new ArrayList<>();
        final List<Person.Id> allPeople = getFakePeople().stream().map(Person::getId).collect(Collectors.toList());
        final List<TripEvent> events = new ArrayList<>();
        events.add(newTripEvent(FAKE_TRIP_LODGING_EVENT_ID, TripEvent.Type.LODGING, "Hotel", "Super Duper Palace",
                fakeStayStart(), fakeStayEnd(), List.of(allPeople.get(2)), null));
        // The flights are dated FROM the stay, at plausible hours: land after check-in, fly home after
        // check-out. They used to carry whatever time of day the seed ran at, which put a "departure" flight
        // hours before check-out and an arrival flight before check-in -- and left the flight-based
        // inference (getLodgingArrivalDate/getLodgingDepartureDate, and the two tests that pin it) resting
        // on the order two now() calls happened to be evaluated in.
        events.add(newTripEvent(FAKE_TRIP_SEA_EWR_EVENT_ID, TripEvent.Type.FLIGHT, "SEA -> EWR", "Alaska flight 94",
                fakeStayStart().plusDays(2).withHour(14), null, List.of(allPeople.get(2)), null));
        events.add(newTripEvent(FAKE_TRIP_PDX_EWR_EVENT_ID, TripEvent.Type.FLIGHT, "PDX -> EWR",
                // Takes off two hours before check-in; an end-less flight lands three hours later, so Kevin
                // reaches the hotel an hour after it opens -- the "arrived with the group" case.
                "Alaska flight 54", fakeStayStart().minusHours(2), null, List.of(allPeople.get(3)), null));
        events.add(newTripEvent(FAKE_TRIP_SPU_SEA_EVENT_ID, TripEvent.Type.FLIGHT, "SPU -> SEA",
                "Direct charter flight", fakeStayStart().plusDays(7).withHour(9), null,
                List.of(allPeople.get(2)), null));
        final TripEvent charter = newTripEvent(FAKE_TRIP_CHARTER_EVENT_ID, TripEvent.Type.FLIGHT, "SPU -> SEA",
                "Direct charter flight", fakeStayEnd().plusHours(3), null, null, null);
        charter.getParticipants().add(allPeople.get(2));
        charter.getParticipants().add(allPeople.get(5));
        charter.getParticipants().add(allPeople.get(3));
        charter.getParticipants().add(allPeople.get(0));
        events.add(charter);
        // Person-modeled trip staff (2026-08-24): Ken and Trinity carry REAL @example.com addresses, so
        // the registration popup's facilitator contact block has something to show locally.
        final List<Person.Id> tripStaff = getFakePeople().stream()
                .filter(person -> localEmail("user2").equals(person.getEmail())
                        || localEmail("user4").equals(person.getEmail()))
                .map(Person::getId)
                .collect(Collectors.toList());
        trips.add(Trip.builder()
                .id(FAKE_TRIP_ID)
                .title("Spring Demo Trip")
                .orgId(CFPW_ORG_ID)
                .openToPublic(false)
                .description("desc")
                .startDate(LocalDateTime.now().plusDays(48))
                .endDate(LocalDateTime.now().plusDays(60))
                .people(allPeople)
                .tripEvents(events)
                .regOptions(getDefaultOptions())
                .facilitatorIds(tripStaff)
                .directorIds(List.of(tripStaff.get(0)))
                .build());

        // Trip 2
        final List<Person.Id> somePeople = getFakePeople().stream()
                .filter(p -> !p.getLast().equals("Paulsen"))
                .map(Person::getId)
                .collect(Collectors.toList());
        final List<TripEvent> events2 = new ArrayList<>();
        events2.add(newTripEvent(FAKE2_LODGING_EVENT_ID, TripEvent.Type.LODGING, "Hotel", "Hilton",
                LocalDateTime.now().minusDays(4), LocalDateTime.now(), null, null));
        events2.add(newTripEvent(FAKE2_SEA_LGW_EVENT_ID, TripEvent.Type.FLIGHT, "SEA -> LGW", "Alaska flight 255",
                LocalDateTime.now().minusDays(5), null, null, null));
        events2.add(newTripEvent(FAKE2_DBV_KEF_EVENT_ID, TripEvent.Type.FLIGHT, "DBV -> KEF", "Trip for 1 to Iceland",
                LocalDateTime.now(), null, null, null));
        trips.add(Trip.builder()
                .id(FAKE2_TRIP_ID)
                .title("Summer Demo Trip")
                .orgId(CFPW_ORG_ID)
                .openToPublic(true)
                // Legacy free-form staff strings, NO id lists: keeps the deprecated fallback branch of
                // getFacilitators()/getDirector() rendering somewhere locally.
                .facilitators("The Legacy Organizer Team")
                .director("Fr. Legacy")
                .description("Trip Description")
                .startDate(LocalDateTime.now().minusDays(4))
                .endDate(LocalDateTime.now().plusDays(7))
                .people(somePeople)
                .tripEvents(events2)
                .regOptions(getDefaultOptions())
                .build());
        addLandingPageSeeds(trips, allPeople, tripStaff);
        return trips;
    }

    /**
     * Landing-page seeds: one trip per public-listing rule, so the data-driven home page renders every
     * branch locally (language sections, CFPW sidebar filter, countdown rules, 7-day removal, galleria).
     */
    private static void addLandingPageSeeds(final List<Trip> trips, final List<Person.Id> allPeople,
            final List<Person.Id> tripStaff) {
        trips.add(Trip.builder()
                .id(PUB_EN_1_TRIP_ID)
                .title("2026 Sep: Holy Angels Demo")     // prefixed title exercises getShortTitle
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .estimatedPrice("$3,000")
                .director("Fr. Demo Director")
                .flyerUrl("https://example.com/demo-flyer.pdf")     // the only seed with a flyer link
                .startDate(LocalDateTime.now().plusDays(30))
                .endDate(LocalDateTime.now().plusDays(40))
                .people(new ArrayList<>(List.of(allPeople.get(2))))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents(PUB_EN_1_TRIP_ID, 30))
                .build());
        trips.add(Trip.builder()
                .id(PUB_EN_2_TRIP_ID)
                .title("2027 Jan: Winter Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(120))     // beyond 60d and not next: no countdown
                .endDate(LocalDateTime.now().plusDays(130))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents(PUB_EN_2_TRIP_ID, 120))
                .build());
        trips.add(Trip.builder()
                .id(PUB_ES_1_TRIP_ID)
                .title("2026 dic: Bajo el Manto Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.Spanish)
                .startDate(LocalDateTime.now().plusDays(100))     // countdown ONLY as next-of-its-language
                .endDate(LocalDateTime.now().plusDays(110))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents(PUB_ES_1_TRIP_ID, 100))
                // The registration webtests submit against THIS trip (joinable: future start, no seeded
                // roster), so it needs facilitators for the confirmation popup's contact block.
                .facilitatorIds(tripStaff)
                .build());
        trips.add(Trip.builder()
                .id(PUB_EXT_1_TRIP_ID)
                .title("2026 Oct: External Demo")
                .openToPublic(true)
                .provider("Queen of Peace Medjugorje Centre, Toronto, Canada")
                .language(Language.English)
                .nonHostedTripUrl("https://example.com/external-pilgrimage")
                .nonHostedRegNumber(35)
                .startDate(LocalDateTime.now().plusDays(70))
                .endDate(LocalDateTime.now().plusDays(80))
                .regLimit(45)
                .tripEvents(seedEvents(PUB_EXT_1_TRIP_ID, 70))
                .build());
        trips.add(Trip.builder()
                .id(PUB_PAST_3D_TRIP_ID)
                .title("2026 Aug: Just Ended Demo")     // within the 7-day window; the galleria album trip
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(13))
                .endDate(LocalDateTime.now().minusDays(3))
                .people(allPeople)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents(PUB_PAST_3D_TRIP_ID, -13))
                .build());
        trips.add(Trip.builder()
                .id(PUB_PAST_30D_TRIP_ID)
                .title("2026 Jul: Long Gone Demo")     // past the 7-day window: must NOT be listed
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(40))
                .endDate(LocalDateTime.now().minusDays(30))
                .tripEvents(seedEvents(PUB_PAST_30D_TRIP_ID, -40))
                .build());
        trips.add(Trip.builder()
                .id(PUB_HIDDEN_TRIP_ID)
                .title("2026 Nov: Unlisted Demo")     // openToPublic=false: must NOT be listed
                .openToPublic(false)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(90))
                .endDate(LocalDateTime.now().plusDays(97))
                .tripEvents(seedEvents(PUB_HIDDEN_TRIP_ID, 90))
                .build());
    }

    /** One minimal event per seed trip (a FakeData invariant: every fake trip has at least one). */
    private static List<TripEvent> seedEvents(final String tripId, final int startOffsetDays) {
        return List.of(newTripEvent(seedEventId(tripId), TripEvent.Type.LODGING, "Hotel", "Seeded lodging",
                LocalDateTime.now().plusDays(startOffsetDays), null, null, null));
    }

    /**
     * A canonical UUID derived from the trip's, so each public seed's event id is stable across re-seeds
     * without another hand-written constant. {@code tripId + "-e1"} was neither a UUID nor able to become
     * one once the trip ids did.
     */
    private static String seedEventId(final String tripId) {
        return UUID.nameUUIDFromBytes(("trip-event:" + tripId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static TripEvent newTripEvent(
            final String id,
            final TripEvent.Type type,
            final String title,
            final String notes,
            final LocalDateTime start,
            final LocalDateTime end,
            final List<Person.Id> participants,
            final Map<Person.Id, String> privNotes) {
        return new TripEvent(id, type, title, notes, start, end, participants, privNotes);
    }

    public static List<RegistrationOption> getDefaultOptions() {
        final List<RegistrationOption> result = new ArrayList<>();
        result.add(new RegistrationOption(1, "Room Preference:",
                "Private room ($15 more per night) or shared?", true));
        result.add(new RegistrationOption(2, "Roommate request?",
                "If you are sharing a room, do you have someone in mind?", true));
        result.add(new RegistrationOption(3, "Preferred Departure Airport?",
                "What airport would you like to leave from?", true));
        result.add(new RegistrationOption(4, "Trip Insurance?",
                "Price is will be paid directly to insurance company, typically $100+.", true));
        result.add(new RegistrationOption(6, "Portugal excursion?",
                "Those interested will visit Fatima before the main trip.", true));
        result.add(new RegistrationOption(5, "Check luggage?",
                "Will you need to check luggage?", true));
        result.add(new RegistrationOption(7, "Special Requests?",
                "Do you have any special requests for this trip?", true));
        result.add(new RegistrationOption(8, "Agree to Terms?",
                "Type your full name to agree.", true));
        return result;
    }

    static Map<String, AttributeValue> getTestUserCreds(final GetItemRequest giReq) {
        final AttributeValue email = giReq.key().get(CredentialsDAO.EMAIL);
        final AttributeValue lowEmail = email.toBuilder().s(email.s().toLowerCase(Locale.ROOT)).build();
        final AttributeValue priv;
        if (lowEmail.s().startsWith("admin")) {
            priv = AttributeValue.builder().s("admin").build();
        } else if (lowEmail.s().startsWith("user")) {
            priv = AttributeValue.builder().s("user").build();
        } else {
            // Not authorized
            return null;
        }
        final Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put(CredentialsDAO.EMAIL, lowEmail);
        // Resolve the persona as typed first, then as the address it maps to: the family managers hold real
        // addresses (see initFakePeople), and both "user2" and "user2@example.com" must reach that person.
        final AttributeValue userId = Optional.ofNullable(DAO.getInstance().getPersonByEmail(lowEmail.s(), Cached.NO))
                .or(() -> Optional.ofNullable(DAO.getInstance().getPersonByEmail(localEmail(lowEmail.s()), Cached.NO)))
                .map(Person::getId).map(id -> AttributeValue.builder().s(id.getValue()).build())
                .orElse(lowEmail);
        attrs.put(CredentialsDAO.USER_ID, userId);
        attrs.put(CredentialsDAO.PRIV, priv);
        attrs.put(CredentialsDAO.PW, priv);
        // Epoch SECONDS, matching CredentialsDAO.updateLastLogin() and Audit.formatEpochSeconds(). Storing millis
        // here made the local audit log report the previous login as the year 58524.
        attrs.put(CredentialsDAO.LAST_LOGIN,
                AttributeValue.builder().n("" + ((System.currentTimeMillis() - 86_400_000L) / 1000L)).build());
        return attrs;
    }
}
