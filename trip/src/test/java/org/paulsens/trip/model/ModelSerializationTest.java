package org.paulsens.trip.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
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
                .build();

        final AuditQuery revived = roundTrip(query);

        Assert.assertEquals(revived.getActor(), "someone@example.com");
        Assert.assertEquals(revived.getAction(), AuditAction.LOGIN);
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
