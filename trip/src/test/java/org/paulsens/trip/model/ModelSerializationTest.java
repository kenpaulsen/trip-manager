package org.paulsens.trip.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map; // used by chatPageSurvivesARoundTrip via Map.of
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Every model must survive Java serialization.
 *
 * <p>This is not a theoretical property. JSF scopes live in the HTTP session, the session is stored in Valkey,
 * and Redisson serializes it on EVERY request. A non-serializable object placed in {@code viewScope} does not
 * break the page that created it -- it breaks {@code RedissonSession.save()}, so every subsequent request on
 * that session returns 500 regardless of which page it asks for. One admin opening one page takes their whole
 * session down, and it looks like a site-wide outage.
 *
 * <p>That is exactly what {@link AuditPage} did in production on 2026-07-27. Nothing caught it: the web tests
 * run with in-memory sessions, which never serialize, and the only tests that exercise the Valkey path are
 * skipped unless a real Redis is available. A plain unit test is the right guard -- it needs no infrastructure
 * and it fails at the moment the class is written.
 */
public class ModelSerializationTest {

    /**
     * Classes exempt from the sweep below, with the reason. Keep this list SHORT and justified: every entry is
     * a class that must never reach a scope.
     */
    private static final List<String> EXEMPT = List.of(
            // Nested Lombok builders are transient construction helpers, never stored.
            "Builder",
            // Jackson (de)serializers are stateless machinery that lives on the ObjectMapper, not in a scope.
            "Serializer", "Deserializer");

    @Test
    public void auditPageSurvivesARoundTrip() throws Exception {
        // The specific regression: this is what the admin page parks in viewScope.
        final AuditEvent event = new AuditEvent(Instant.parse("2026-07-27T12:00:00Z"), AuditAction.LOGIN,
                AuditOutcome.SUCCESS, "a@example.com", "id-1", "person", "b@example.com", "id-2", "msg", null);
        final AuditPage page = new AuditPage(List.of(event), LocalDate.parse("2026-07-01"), true, 0);

        final AuditPage revived = roundTrip(page);

        Assert.assertEquals(revived.getEvents().size(), 1);
        Assert.assertEquals(revived.getEvents().get(0).getActorEmail(), "a@example.com");
        Assert.assertEquals(revived.getSearchedBackTo(), LocalDate.parse("2026-07-01"));
        Assert.assertEquals(revived.getFailedPartitions(), 0);
    }

    @Test
    public void auditQuerySurvivesARoundTrip() throws Exception {
        final AuditQuery query = AuditQuery.builder()
                .before(Instant.parse("2026-07-27T12:00:00Z"))
                .actor("someone@example.com")
                .action(AuditAction.LOGIN)
                .outcome(AuditOutcome.FAILURE)
                .scope(AuditScope.org("acme"))
                .build();

        final AuditQuery revived = roundTrip(query);

        Assert.assertEquals(revived.getActor(), "someone@example.com");
        Assert.assertEquals(revived.getAction(), AuditAction.LOGIN);
        Assert.assertTrue(revived.getScope().admits("acme"), "the tenancy boundary survives the session");
        Assert.assertFalse(revived.getScope().admits("beta"));
    }

    @Test
    public void everyModelClassIsSerializable() throws Exception {
        final List<String> offenders = new ArrayList<>();
        for (final Class<?> type : modelClasses()) {
            if (type.isInterface() || type.isEnum() || type.isAnonymousClass() || type.isSynthetic()) {
                continue;   // enums are Serializable by definition; interfaces hold no state
            }
            if (EXEMPT.stream().anyMatch(name -> type.getSimpleName().endsWith(name))) {
                continue;
            }
            if (!Serializable.class.isAssignableFrom(type)) {
                offenders.add(type.getName());
            }
        }
        Assert.assertEquals(offenders, List.of(),
                "These models are not Serializable. Any one of them placed in a JSF scope breaks the session "
                        + "save for that user, and every later request they make returns 500: " + offenders);
    }

    /**
     * The sweep above only walks {@code org.paulsens.trip.model}, and not everything parked in viewScope lives
     * there. {@code chats.xhtml} stashes {@code chat.myChats(userId)} -- a list of
     * {@link org.paulsens.trip.action.ChatCommands.ChatSummary}, declared in the <em>action</em> package -- so
     * the record sat outside the guard and was not Serializable. Under the Valkey session store that broke the
     * session save for anyone who opened the page, taking out every later request they made.
     *
     * <p>Found on 2026-08-03 by the Playwright webtest suite in redis mode, where the aborted responses showed
     * up as ~18 unrelated {@code /trip/*} pages failing to render. Add a case here for any other action-package
     * type a page puts in a scope.
     */
    @Test
    public void viewScopeTypesDeclaredOutsideTheModelPackageAreSerializable() throws Exception {
        final Class<?> summary = org.paulsens.trip.action.ChatCommands.ChatSummary.class;
        Assert.assertTrue(Serializable.class.isAssignableFrom(summary),
                summary.getName() + " is placed in viewScope by chats.xhtml, so it must be Serializable or the "
                        + "session save throws and every later request on that session fails");
    }

    @Test
    public void modelSweepDiscoversChatSubPackage() throws Exception {
        // Guard against silent escape: a non-recursive dir.listFiles would drop model.chat entirely.
        final boolean found = modelClasses().stream()
                .anyMatch(c -> c.getName().equals(org.paulsens.trip.model.chat.ChatPage.class.getName()));
        Assert.assertTrue(found,
                "ModelSerializationTest must discover model.chat classes recursively; "
                        + "otherwise chat models can leave viewScope without a Serializable check.");
    }

    @Test
    public void chatPageSurvivesARoundTrip() throws Exception {
        final org.paulsens.trip.model.chat.ChatMessage.Id msgId =
                org.paulsens.trip.model.chat.ChatMessage.Id.of(1_700_000_000_000L);
        final org.paulsens.trip.model.chat.ChatPage page = new org.paulsens.trip.model.chat.ChatPage(
                List.of(), Map.of(), msgId, 3L, 7L, false, false, Map.of(),
                Instant.parse("2026-07-29T12:00:00Z"));
        final org.paulsens.trip.model.chat.ChatPage revived = roundTrip(page);
        Assert.assertEquals(revived.getCursor(), msgId);
        Assert.assertEquals(revived.getServerTime(), Instant.parse("2026-07-29T12:00:00Z"));
        // Both counters must survive: they are the only mechanism by which a reaction, an edit or a delete reaches
        // a client that already holds the message, so losing them silently disables all three.
        Assert.assertEquals(revived.getReactionsVersion(), 3L);
        Assert.assertEquals(revived.getMutationsVersion(), 7L);
    }

    /**
     * Every Serializable class pins {@code serialVersionUID}. Without the pin the JVM derives the UID from
     * the class shape, so adding one field makes every session written before the deploy unreadable -- the
     * 2026-08-14 outage ({@code Trip} gained {@code badgeImages}). The sweep covers the model package
     * recursively AND the action/chat packages, because pages park rows declared there (RegRow, ChatSummary,
     * PrivacyView, the payment Quote) in viewScope too. {@link SerializationCompatibilityTest} says when a
     * pinned value must be bumped.
     */
    @Test
    public void everySerializableClassPinsItsSerialVersionUID() throws Exception {
        final List<Class<?>> candidates = new ArrayList<>(modelClasses());
        candidates.addAll(classesUnder("org.paulsens.trip.action"));
        candidates.addAll(classesUnder("org.paulsens.trip.chat"));
        final List<String> offenders = new ArrayList<>();
        for (final Class<?> type : candidates) {
            if (needsPin(type) && !declaresSerialVersionUID(type)) {
                offenders.add(type.getName());
            }
        }
        Assert.assertEquals(offenders, List.of(),
                "These Serializable classes do not pin serialVersionUID, so any field change silently invalidates "
                        + "every live session. Add to each: `private static final long serialVersionUID = 1L; "
                        + "// Pinned: SerializationCompatibilityTest says when to bump.` -- " + offenders);
    }

    @Test
    public void thePinRatchetReachesTheSweptPackages() throws Exception {
        // Guard against the sweep silently finding nothing: the two outside-the-model rows that exist today.
        final List<Class<?>> swept = classesUnder("org.paulsens.trip.action");
        Assert.assertTrue(swept.contains(org.paulsens.trip.action.RegistrationCommands.RegRow.class));
        Assert.assertTrue(swept.contains(org.paulsens.trip.action.ChatCommands.ChatSummary.class));
        Assert.assertTrue(classesUnder("org.paulsens.trip.chat")
                .contains(org.paulsens.trip.chat.ChatNotification.class));
    }

    @Test
    public void thePinCheckSeesTheFieldAndNothingElse() throws Exception {
        Assert.assertTrue(declaresSerialVersionUID(Trip.class), "Trip pins by hand");
        Assert.assertTrue(declaresSerialVersionUID(AuditScope.class), "records take the field too");
        Assert.assertFalse(declaresSerialVersionUID(WithoutPin.class), "the negative must fail the ratchet");
        Assert.assertFalse(declaresSerialVersionUID(WrongShape.class), "an instance or non-long field is no pin");
        Assert.assertTrue(needsPin(WithoutPin.class));
        Assert.assertFalse(needsPin(Serializable.class), "interfaces hold no state");
        Assert.assertFalse(needsPin(AuditAction.class), "enums are stream-invariant by name");
        Assert.assertFalse(needsPin(String.class), "only our own classes are swept");
    }

    /** Test-only negative for the ratchet's own check: Serializable, unpinned. Never reaches a scope. */
    private static final class WithoutPin implements Serializable {
    }

    /** Test-only negative: the name is there but neither static nor final nor a long. */
    private static final class WrongShape implements Serializable {
        @SuppressWarnings("unused")
        private int serialVersionUID;
    }

    private static boolean needsPin(final Class<?> type) {
        return Serializable.class.isAssignableFrom(type) && !type.isInterface() && !type.isEnum()
                && !type.isAnonymousClass() && !type.isSynthetic()
                && type.getName().startsWith("org.paulsens.trip.")
                // Lombok builders and Jackson (de)serializers never reach a scope; the sweep above exempts them.
                && EXEMPT.stream().noneMatch(name -> type.getSimpleName().endsWith(name));
    }

    private static boolean declaresSerialVersionUID(final Class<?> type) {
        try {
            final Field field = type.getDeclaredField("serialVersionUID");
            final int mods = field.getModifiers();
            return field.getType() == long.class && Modifier.isStatic(mods) && Modifier.isFinal(mods);
        } catch (final NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * Every class under {@code pkg} (recursively) WITHOUT initializing it: the action package has beans whose
     * static state reaches the DAO, and this sweep only needs their declared fields.
     */
    private static List<Class<?>> classesUnder(final String pkg) throws IOException, ClassNotFoundException {
        final List<Class<?>> found = new ArrayList<>();
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        final Enumeration<URL> roots = loader.getResources(pkg.replace('.', '/'));
        while (roots.hasMoreElements()) {
            final File dir = new File(roots.nextElement().getFile());
            if (dir.getPath().contains("test-classes")) {
                continue;
            }
            collectClassNames(dir, pkg, found, loader);
        }
        return found;
    }

    private static void collectClassNames(final File dir, final String pkg, final List<Class<?>> found,
            final ClassLoader loader) throws ClassNotFoundException {
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (file.isDirectory()) {
                collectClassNames(file, pkg + "." + file.getName(), found, loader);
            } else if (file.getName().endsWith(".class")) {
                found.add(Class.forName(pkg + "." + file.getName().replace(".class", ""), false, loader));
            }
        }
    }

    private static <T> T roundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked")
            final T revived = (T) in.readObject();
            return revived;
        }
    }

    /**
     * Every class under the model package <em>and its sub-packages</em>, read off the classpath rather than
     * listed by hand. Must stay recursive: chat models live in {@code model.chat}, and a single-directory
     * sweep would drop them silently.
     */
    private static List<Class<?>> modelClasses() throws IOException, ClassNotFoundException {
        final String pkg = AuditPage.class.getPackageName();
        final List<Class<?>> found = new ArrayList<>();
        final Enumeration<URL> roots = Thread.currentThread().getContextClassLoader()
                .getResources(pkg.replace('.', '/'));
        while (roots.hasMoreElements()) {
            final File dir = new File(roots.nextElement().getFile());
            // Main classes only. test-classes is on the same classpath and its contents are not models.
            if (dir.getPath().contains("test-classes")) {
                continue;
            }
            collectClasses(dir, pkg, found);
        }
        return found;
    }

    private static void collectClasses(final File dir, final String pkg, final List<Class<?>> found)
            throws ClassNotFoundException {
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (file.isDirectory()) {
                collectClasses(file, pkg + "." + file.getName(), found);
            } else if (file.getName().endsWith(".class")) {
                final String name = file.getName().replace(".class", "");
                // Skip nested class files that use '$' — Class.forName still loads them via the outer name
                // when needed; the simple form is enough for top-level and we also load nested via $ names.
                found.add(Class.forName(pkg + "." + name));
            }
        }
    }
}
