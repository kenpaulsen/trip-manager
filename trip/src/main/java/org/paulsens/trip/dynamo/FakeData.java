package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Getter;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
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

public class FakeData {

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
            relaxChatLimitsForFakeTrips();
        }
    }

    /**
     * Gives the demo trips' chat channels limits no test run can trip.
     *
     * <p>The shipped burst limit is 5 messages per 10 SECONDS per person per channel, which is right for people
     * and wrong for a test suite: half a dozen classes drive the same {@code faketrip} channel as the same
     * admin, some through the REST client and some by clicking Send on the page, and the server counts them all
     * against one bucket. The API client paces itself, but it cannot see the page-driven sends, so whether a
     * class overruns depends purely on how the classes interleave -- which is why this passed locally for
     * months and then failed a deploy's integ stage, where the suite runs slower and lands differently. The
     * limiter's own behaviour is covered by {@code ChatRateLimiterTest} against explicit settings; nothing is
     * lost by keeping it out of the way here.
     *
     * <p>LOCAL MODE ONLY, like every other seed in this class: production channels keep the shipped defaults.
     */
    /** The ceiling {@code ChatCommands.validate} enforces on a channel's burst/sustained limits. */
    private static final int MAX_CHAT_RATE_LIMIT = 10_000;

    private static void relaxChatLimitsForFakeTrips() {
        final ChatCommands chat = new ChatCommands();
        for (final String tripId : List.of("faketrip", "Fake2")) {
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
        seedOrg(CFPW_ORG_ID, "CFPW", "CFPW", admin.getId());
        seedOrg(ACME_ORG_ID, "Acme Inc", "Acme", admin.getId());
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

    private static void seedOrg(final String id, final String name, final String abbr, final Person.Id creator) {
        try {
            final boolean saved = DAO.getInstance().saveOrganization(Organization.builder()
                    .id(Organization.Id.from(id))
                    .name(name)
                    .abbreviation(abbr)
                    .createdBy(creator)
                    .created(LocalDateTime.now())
                    .build());
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
        // The galleria trip's album: 12 photos, one hidden -> 11 visible, above the min-count of 10.
        for (int i = 1; i <= 12; i++) {
            saveMedia(new MediaItem("fake-photo-past-" + i, "chat/pub-past-3d/fake-" + i + ".jpg",
                    "Fake photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-pub-past-3d", 0, now.minusDays(13).plusHours(i), "fake-seed", null,
                    i == 12 ? Boolean.TRUE : null));
            seedPhotoBytes("chat/pub-past-3d/fake-" + i + ".jpg");
        }
        // Below the minimum: no album on the landing page.
        for (int i = 1; i <= 4; i++) {
            saveMedia(new MediaItem("fake-photo-f2-" + i, "chat/Fake2/fake-" + i + ".jpg",
                    "Fake2 photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-Fake2", 0, now.minusDays(2).plusHours(i), "fake-seed", null, null));
            seedPhotoBytes("chat/Fake2/fake-" + i + ".jpg");
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
                .id(Person.Id.from("admin"))
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
        events.add(newTripEvent("t1e2", TripEvent.Type.LODGING, "Hotel", "Super Duper Palace",
                LocalDateTime.now().plusDays(48), LocalDateTime.now().plusDays(60), List.of(allPeople.get(2)), null));
        events.add(newTripEvent("t1e4", TripEvent.Type.FLIGHT, "SEA -> EWR", "Alaska flight 94",
                LocalDateTime.now().plusDays(50), null, List.of(allPeople.get(2)), null));
        events.add(newTripEvent("t1e1", TripEvent.Type.FLIGHT, "PDX -> EWR", "Alaska flight 54",
                LocalDateTime.now().plusDays(48), null, List.of(allPeople.get(3)), null));
        events.add(newTripEvent("t1e5", TripEvent.Type.FLIGHT, "SPU -> SEA", "Direct charter flight",
                LocalDateTime.now().plusDays(55), null, List.of(allPeople.get(2)), null));
        final TripEvent charter = newTripEvent("t1e3", TripEvent.Type.FLIGHT, "SPU -> SEA", "Direct charter flight",
                LocalDateTime.now().plusDays(60), null, null, null);
        charter.getParticipants().add(allPeople.get(2));
        charter.getParticipants().add(allPeople.get(5));
        charter.getParticipants().add(allPeople.get(3));
        charter.getParticipants().add(allPeople.get(0));
        events.add(charter);
        trips.add(Trip.builder()
                .id("faketrip")
                .title("Spring Demo Trip")
                .orgId(CFPW_ORG_ID)
                .openToPublic(false)
                .description("desc")
                .startDate(LocalDateTime.now().plusDays(48))
                .endDate(LocalDateTime.now().plusDays(60))
                .people(allPeople)
                .tripEvents(events)
                .regOptions(getDefaultOptions())
                .build());

        // Trip 2
        final List<Person.Id> somePeople = getFakePeople().stream()
                .filter(p -> !p.getLast().equals("Paulsen"))
                .map(Person::getId)
                .collect(Collectors.toList());
        final List<TripEvent> events2 = new ArrayList<>();
        events2.add(newTripEvent("t2e2", TripEvent.Type.LODGING, "Hotel", "Hilton",
                LocalDateTime.now().minusDays(4), LocalDateTime.now(), null, null));
        events2.add(newTripEvent("t2e1", TripEvent.Type.FLIGHT, "SEA -> LGW", "Alaska flight 255",
                LocalDateTime.now().minusDays(5), null, null, null));
        events2.add(newTripEvent("t2e3", TripEvent.Type.FLIGHT, "DBV -> KEF", "Trip for 1 to Iceland",
                LocalDateTime.now(), null, null, null));
        trips.add(Trip.builder()
                .id("Fake2")
                .title("Summer Demo Trip")
                .orgId(CFPW_ORG_ID)
                .openToPublic(true)
                .description("Trip Description")
                .startDate(LocalDateTime.now().minusDays(4))
                .endDate(LocalDateTime.now().plusDays(7))
                .people(somePeople)
                .tripEvents(events2)
                .regOptions(getDefaultOptions())
                .build());
        // Landing-page seeds: one trip per public-listing rule, so the data-driven home page renders every
        // branch locally (language sections, CFPW sidebar filter, countdown rules, 7-day removal, galleria).
        trips.add(Trip.builder()
                .id("pub-en-1")
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
                .tripEvents(seedEvents("pub-en-1", 30))
                .build());
        trips.add(Trip.builder()
                .id("pub-en-2")
                .title("2027 Jan: Winter Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(120))     // beyond 60d and not next: no countdown
                .endDate(LocalDateTime.now().plusDays(130))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-en-2", 120))
                .build());
        trips.add(Trip.builder()
                .id("pub-es-1")
                .title("2026 dic: Bajo el Manto Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.Spanish)
                .startDate(LocalDateTime.now().plusDays(100))     // countdown ONLY as next-of-its-language
                .endDate(LocalDateTime.now().plusDays(110))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-es-1", 100))
                .build());
        trips.add(Trip.builder()
                .id("pub-ext-1")
                .title("2026 Oct: External Demo")
                .openToPublic(true)
                .provider("Queen of Peace Medjugorje Centre, Toronto, Canada")
                .language(Language.English)
                .nonHostedTripUrl("https://example.com/external-pilgrimage")
                .nonHostedRegNumber(35)
                .startDate(LocalDateTime.now().plusDays(70))
                .endDate(LocalDateTime.now().plusDays(80))
                .regLimit(45)
                .tripEvents(seedEvents("pub-ext-1", 70))
                .build());
        trips.add(Trip.builder()
                .id("pub-past-3d")
                .title("2026 Aug: Just Ended Demo")     // within the 7-day window; the galleria album trip
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(13))
                .endDate(LocalDateTime.now().minusDays(3))
                .people(allPeople)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-past-3d", -13))
                .build());
        trips.add(Trip.builder()
                .id("pub-past-30d")
                .title("2026 Jul: Long Gone Demo")     // past the 7-day window: must NOT be listed
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(40))
                .endDate(LocalDateTime.now().minusDays(30))
                .tripEvents(seedEvents("pub-past-30d", -40))
                .build());
        trips.add(Trip.builder()
                .id("pub-hidden")
                .title("2026 Nov: Unlisted Demo")     // openToPublic=false: must NOT be listed
                .openToPublic(false)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(90))
                .endDate(LocalDateTime.now().plusDays(97))
                .tripEvents(seedEvents("pub-hidden", 90))
                .build());
        return trips;
    }

    /** One minimal event per seed trip (a FakeData invariant: every fake trip has at least one). */
    private static List<TripEvent> seedEvents(final String tripId, final int startOffsetDays) {
        return List.of(newTripEvent(tripId + "-e1", TripEvent.Type.LODGING, "Hotel", "Seeded lodging",
                LocalDateTime.now().plusDays(startOffsetDays), null, null, null));
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