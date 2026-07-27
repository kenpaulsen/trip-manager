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
                offenders.add(type.getSimpleName());
            }
        }
        Assert.assertEquals(offenders, List.of(),
                "These models are not Serializable. Any one of them placed in a JSF scope breaks the session "
                        + "save for that user, and every later request they make returns 500: " + offenders);
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

    /** Every class in the model package, read off the classpath rather than listed by hand. */
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
            final File[] files = dir.listFiles((d, name) -> name.endsWith(".class"));
            if (files == null) {
                continue;
            }
            for (final File file : files) {
                final String name = file.getName().replace(".class", "");
                found.add(Class.forName(pkg + "." + name));
            }
        }
        return found;
    }
}
