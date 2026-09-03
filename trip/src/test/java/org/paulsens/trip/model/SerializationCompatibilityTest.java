package org.paulsens.trip.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.PaymentCommands;
import org.paulsens.trip.action.PrivacyView;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.action.SupportChatCommands;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Golden-stream compatibility: an object serialized by YESTERDAY's class must still read under today's.
 *
 * <p>Sessions live in Valkey and Redisson writes them with Kryo's {@code JavaSerializer} -- plain Java
 * serialization of everything in sessionScope and viewScope. {@link ModelSerializationTest} proves each class
 * can round-trip with itself; that says nothing about a stream written before the class changed, which is
 * what every live session is at the moment a deploy lands. So this test keeps one JDK-serialized instance per
 * session-borne type in {@code src/test/resources/serialized/<SimpleName>.ser}, written by the generator
 * below, and reads every one of them back on every build. Identity fields are compared with what the factory
 * wrote, so a stream that deserializes but reads garbage fails just like one that throws.
 *
 * <p><b>THE RULE, when this test fails after you changed a class:</b>
 * <ul>
 *   <li>If the change is <em>compatible</em> -- a new field with a safe default, a removed field -- keep the
 *       class's {@code serialVersionUID} and regenerate this class's fixture.</li>
 *   <li>If objects written before the change would be <em>UNSAFE</em> to read -- a field that must never be
 *       null, a changed meaning -- bump the class's {@code serialVersionUID} AND regenerate. The bump is a
 *       deliberate one-time re-login for every session holding that class, handled by
 *       {@code SessionRecoveryFilter}.</li>
 * </ul>
 *
 * <p>Regenerate (from {@code trip/trip}, then commit the {@code .ser} files with the change):
 * <pre>{@code mvn -q test -Dtest=SerializationCompatibilityTest -Dserialization.regenerate=true}</pre>
 *
 * <p>The pinning that introduced this test (2026-09-02) shifted every computed UID once, so the deploy that
 * shipped it was itself a one-time re-login for everyone -- see {@code docs/caching.md}.
 */
public class SerializationCompatibilityTest {

    /** System property that turns the {@link #regenerateIfAsked() generator} on; off by default. */
    static final String REGENERATE_PROPERTY = "serialization.regenerate";

    /** Relative to the module dir, which is surefire's working directory. */
    static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "serialized");

    static final String REGENERATE_COMMAND = "cd trip/trip && mvn -q test -Dtest=SerializationCompatibilityTest"
            + " -D" + REGENERATE_PROPERTY + "=true";

    static final String RULE = "If the change is compatible (a new field with a safe default, a removed field)"
            + " keep the serialVersionUID and regenerate this class's fixture. If objects written before the change"
            + " would be UNSAFE to read (a field that must never be null, a changed meaning), bump the class's"
            + " serialVersionUID AND regenerate: the bump is a deliberate one-time re-login for every session"
            + " holding that class, handled by SessionRecoveryFilter. Regenerate with: " + REGENERATE_COMMAND;

    /**
     * One session-borne type: the reference instance the fixture holds, and the identity a read-back must
     * reproduce. The factory must be deterministic in the fields the identity reads.
     */
    record Fixture<T>(Class<T> type, Supplier<T> factory, Function<T, Object> identity) {

        String fileName() {
            return type.getSimpleName() + ".ser";
        }

        Object identityOf(final Object value) {
            return identity.apply(type.cast(value));
        }

        @Override
        public String toString() {
            return type.getSimpleName();
        }
    }

    private static <T> Fixture<T> of(final Class<T> type, final Supplier<T> factory,
            final Function<T, Object> identity) {
        return new Fixture<>(type, factory, identity);
    }

    private static final Instant WHEN = Instant.parse("2026-09-02T12:00:00Z");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 2, 12, 0);
    private static final Person.Id ADA = Person.Id.from("person-ada");
    private static final Person.Id BOB = Person.Id.from("person-bob");

    /**
     * Every type a page parks in viewScope or sessionScope, directly or inside another entry. Add a line here
     * for any new one (and run the regenerate command); the {@link ModelSerializationTest} pin ratchet catches
     * a missing {@code serialVersionUID}, this list is what catches an incompatible reshape.
     */
    private static final List<Fixture<?>> FIXTURES = List.of(
            of(AuditEvent.class, SerializationCompatibilityTest::auditEvent, AuditEvent::getActorEmail),
            of(AuditPage.class, SerializationCompatibilityTest::auditPage, p -> p.getEvents().get(0).getActorId()),
            of(AuditQuery.class, SerializationCompatibilityTest::auditQuery, AuditQuery::getActor),
            of(AuditScope.class, () -> AuditScope.org("acme"), AuditScope::orgId),
            of(Person.class, SerializationCompatibilityTest::person, p -> p.getId().getValue()),
            of(Trip.class, () -> Trip.builder().id("trip-rome").build(), Trip::getId),
            of(TripEvent.class, SerializationCompatibilityTest::tripEvent, TripEvent::getId),
            of(Registration.class, () -> new Registration("trip-rome", ADA, AT, Registration.Status.PENDING, null),
                    r -> r.getUserId().getValue()),
            of(Transaction.class, SerializationCompatibilityTest::transaction, t -> t.getUserId().getValue()),
            of(Organization.class, SerializationCompatibilityTest::organization, Organization::getName),
            of(Family.class, SerializationCompatibilityTest::family, Family::getId),
            of(ContentInstance.class, SerializationCompatibilityTest::contentInstance, ContentInstance::getId),
            of(ContentTemplate.class, SerializationCompatibilityTest::contentTemplate, ContentTemplate::getId),
            of(MediaItem.class, SerializationCompatibilityTest::mediaItem, MediaItem::getId),
            of(BadgeImage.class, () -> new BadgeImage("badgeImages/trip-rome/1.jpg", "Rome"), BadgeImage::getKey),
            of(Payment.class, SerializationCompatibilityTest::payment, Payment::getOrderRef),
            of(Privilege.class, () -> new Privilege("peopleAdmin", "People admin", List.of(ADA)), Privilege::getId),
            of(TodoItem.class, SerializationCompatibilityTest::todoItem, TodoItem::getDataId),
            of(ChatPage.class, SerializationCompatibilityTest::chatPage, ChatPage::getCursor),
            of(RegistrationCommands.RegRow.class,
                    () -> new RegistrationCommands.RegRow("person-ada", AT, "Pending", "party-1"),
                    RegistrationCommands.RegRow::getUserId),
            of(ChatCommands.ChatSummary.class, SerializationCompatibilityTest::chatSummary,
                    s -> s.channel().getId().getValue()),
            of(PrivacyView.class, SerializationCompatibilityTest::privacyView, PrivacyView::getEmail),
            of(PaymentCommands.Quote.class, PaymentCommands.Quote::new, PaymentCommands.Quote::getProcessorLabel),
            of(PaymentCommands.Completion.class, PaymentCommands.Completion::new,
                    PaymentCommands.Completion::getDryRunLines),
            of(ProfilePhotos.Slot.class, () -> new ProfilePhotos.Slot(1, "profile/person-ada/1-3.jpg", "/u/1"),
                    ProfilePhotos.Slot::key),
            of(BrandingPhotos.Version.class,
                    () -> new BrandingPhotos.Version("org/acme/branding/logo-2.png", "/u/logo", "logo", 2L, "today"),
                    BrandingPhotos.Version::key),
            of(SupportChatCommands.SupportMessage.class,
                    () -> new SupportChatCommands.SupportMessage("Ada", "2026-09-02 12:00", "Hello"),
                    SupportChatCommands.SupportMessage::author));

    // ------------------------------------------------------------------ factories (deterministic)

    private static AuditEvent auditEvent() {
        return new AuditEvent(WHEN, AuditAction.LOGIN, AuditOutcome.SUCCESS, "ada@example.com", "person-ada",
                "person", "bob@example.com", "person-bob", "signed in", null);
    }

    private static AuditPage auditPage() {
        return new AuditPage(List.of(auditEvent()), LocalDate.of(2026, 9, 1), true, 0);
    }

    private static AuditQuery auditQuery() {
        return AuditQuery.builder().before(WHEN).actor("ada@example.com").action(AuditAction.LOGIN)
                .outcome(AuditOutcome.SUCCESS).scope(AuditScope.org("acme")).build();
    }

    private static Person person() {
        return Person.builder().id(ADA).first("Ada").last("Lovelace").build();
    }

    private static TripEvent tripEvent() {
        return new TripEvent("event-1", null, "Arrival", "Gate B", AT, AT.plusHours(2), new ArrayList<>(List.of(ADA)),
                new HashMap<>(Map.of(ADA, "confirmed")));
    }

    private static Transaction transaction() {
        return new Transaction(null, ADA, "group-1", Transaction.Type.Tx, Transaction.TransactionType.Payment, AT,
                12.5f, "deposit", "first payment");
    }

    private static Organization organization() {
        return Organization.builder().id(Organization.Id.from("org-acme")).name("Acme Inc").abbreviation("Acme")
                .contactEmail("info@acme.example").adminIds(List.of(ADA)).createdBy(ADA).created(AT).version(3L)
                .build();
    }

    private static Family family() {
        return Family.builder().id(Family.Id.from("family-1")).memberIds(List.of(ADA, BOB)).managerIds(List.of(ADA))
                .build();
    }

    private static ContentInstance contentInstance() {
        return new ContentInstance("content-1", "home.events", "Title", "template-1", 3,
                new HashMap<>(Map.of("a", "v")), AT, 2, 1, AT, "person-ada");
    }

    private static ContentTemplate contentTemplate() {
        return new ContentTemplate("template-1", 3, "Name", "Desc", "<p>{{a}}</p>",
                List.of(new Placeholder("a", Placeholder.Type.RICH_TEXT, "A", "hint", true)), AT, "person-ada");
    }

    private static MediaItem mediaItem() {
        return new MediaItem("media-1", "downloads/x.pdf", "T", "D", "application/pdf", 5L, "home-docs", 0, AT,
                "person-ada", null, Boolean.FALSE);
    }

    private static Payment payment() {
        return Payment.builder().tripId("trip-rome").orgId("org-acme").payerId(ADA).processorConfigId("cfg-1")
                .processorType(ProcessorType.PAYPAL).allocations(List.of(new Payment.Allocation(BOB, 47500L)))
                .donationCents(100000L).feesPaidBy(FeesPaidBy.PAYER).creditFeeCents(7500L).donationFeeCents(5000L)
                .totalChargedCents(250000L).sandbox(true).orderRef("ORDER-1").captureId("CAP-1")
                .capturedGrossCents(250000L).actualFeeCents(12500L).status(Payment.Status.CAPTURED).createdAt(AT)
                .capturedAt(AT.plusMinutes(5)).txIds(List.of("t1", "t2")).build();
    }

    private static TodoItem todoItem() {
        return TodoItem.builder().tripId("trip-rome").dataId(DataId.from("todo-1")).description("Pack").build();
    }

    private static ChatPage chatPage() {
        return new ChatPage(List.of(), Map.of(), ChatMessage.Id.of(1_700_000_000_000L), 3L, 7L, false, false,
                Map.of(), WHEN);
    }

    private static ChatCommands.ChatSummary chatSummary() {
        final ChatChannel channel = new ChatChannel(ChatChannel.Id.forTrip("trip-rome"), "trip-rome",
                ChatChannel.Kind.TRIP, "Rome 2027", null, null, null, WHEN, "person-ada", null, null);
        return new ChatCommands.ChatSummary(channel, "Rome 2027", WHEN.toEpochMilli(), true, false);
    }

    private static PrivacyView privacyView() {
        return PrivacyView.of(person(), person(), false);
    }

    // ------------------------------------------------------------------ the generator

    /**
     * Writes every fixture into {@link #FIXTURE_DIR} when {@code -Dserialization.regenerate=true}; a no-op
     * otherwise. Runs before the reads so one invocation both regenerates and verifies.
     */
    @BeforeClass
    public void regenerateIfAsked() throws IOException {
        if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
            writeAll(FIXTURE_DIR);
        }
    }

    static void writeAll(final Path dir) throws IOException {
        Files.createDirectories(dir);
        for (final Fixture<?> fixture : FIXTURES) {
            try (OutputStream file = Files.newOutputStream(dir.resolve(fixture.fileName()));
                    ObjectOutputStream out = new ObjectOutputStream(file)) {
                out.writeObject(fixture.factory().get());
            }
        }
    }

    static Object read(final Path file) throws IOException, ClassNotFoundException {
        try (InputStream in = Files.newInputStream(file); ObjectInputStream objects = new ObjectInputStream(in)) {
            return objects.readObject();
        }
    }

    // ------------------------------------------------------------------ the tests

    @DataProvider
    public Object[][] fixtures() {
        final Object[][] rows = new Object[FIXTURES.size()][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Object[] {FIXTURES.get(i)};
        }
        return rows;
    }

    @Test(dataProvider = "fixtures")
    public void aStreamWrittenByAnEarlierBuildStillReads(final Fixture<?> fixture) throws Exception {
        final Path file = FIXTURE_DIR.resolve(fixture.fileName());
        Assert.assertTrue(Files.isRegularFile(file),
                "No fixture for " + fixture + " at " + file + ". Generate it with: " + REGENERATE_COMMAND);
        final Object revived;
        try {
            revived = read(file);
        } catch (final IOException | ClassNotFoundException | RuntimeException e) {
            throw new AssertionError(fixture + ": a stream written by an earlier build no longer reads ("
                    + e + "). " + RULE, e);
        }
        Assert.assertNotNull(revived, fixture + " read back as null");
        Assert.assertEquals(revived.getClass(), fixture.type(), fixture + " read back as the wrong type");
        final Object expected = fixture.identityOf(fixture.factory().get());
        Assert.assertNotNull(expected, fixture + ": the factory must set the identity field");
        Assert.assertEquals(fixture.identityOf(revived), expected,
                fixture + ": the stream deserialized but its identity fields read back wrong. " + RULE);
    }

    @Test
    public void fixtureFileNamesAreUnique() {
        final Set<String> names = new HashSet<>();
        for (final Fixture<?> fixture : FIXTURES) {
            Assert.assertTrue(names.add(fixture.fileName()), "Two fixtures would share " + fixture.fileName());
        }
    }

    /** The generator is the other half of the contract; a broken one would silently orphan every fixture. */
    @Test
    public void theGeneratorWritesEveryFixtureAndEachReadsBack() throws Exception {
        final Path dir = Files.createTempDirectory("serialized");
        try {
            writeAll(dir);
            for (final Fixture<?> fixture : FIXTURES) {
                final Path file = dir.resolve(fixture.fileName());
                Assert.assertTrue(Files.size(file) > 0, fixture + " was not written");
                Assert.assertEquals(fixture.identityOf(read(file)), fixture.identityOf(fixture.factory().get()));
            }
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Test
    public void theRuleNamesBothOutcomesAndTheCommand() {
        Assert.assertTrue(RULE.contains("keep the serialVersionUID"));
        Assert.assertTrue(RULE.contains("bump the class's serialVersionUID AND regenerate"));
        Assert.assertTrue(RULE.contains("SessionRecoveryFilter"));
        Assert.assertTrue(RULE.contains("-D" + REGENERATE_PROPERTY + "=true"));
    }
}
