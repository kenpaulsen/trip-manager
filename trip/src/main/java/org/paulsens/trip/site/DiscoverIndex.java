package org.paulsens.trip.site;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.IsoCountries;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.api.mapper.DiscoverTripMapper;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;

/**
 * Everything Discover may list (medjugorje#155), already in its public shape: an in-JVM snapshot of the trips
 * that pass {@link ListingScope#discoverable}, each mapped ONCE to its {@link DiscoverTripDto}. The
 * {@link SiteIndex} pattern, and for its reason: Discover is open to anyone on the internet, so answering a
 * request must cost a map lookup and a pass over a few dozen entries, never a table read or a scan of its own.
 *
 * <p>What a rebuild costs, and when it runs: it reads the (already-cached) active trips and organizations, each
 * LISTED trip's registrations (the mapper's open/full/closed phase) and each listing organization's logo
 * setting. It runs when a request finds the snapshot {@link #REFRESH_INTERVAL} old, or on the first request
 * after {@link #invalidate} whatever its age, which is after every trip or organization save on this server.
 * While people are registering (a registration saves its trip) that is once per save, not once a minute.
 *
 * <p>Two things follow from holding only mapped entries. Search and filters see exactly what a response may
 * say and nothing else, so a word cannot find a trip by something Discover would not show. And a trip that is
 * not discoverable is simply absent: by id, in a list and in search it is the same miss as a trip that never
 * existed.
 *
 * <p><b>Staleness.</b> A trip or an organization saved on THIS server marks the snapshot stale at once
 * ({@link #invalidate}, called from {@code DAO.saveTrip}, {@code deleteTrip} and {@code saveOrganization}), so the
 * next request rebuilds. Another server learns of it at its next timed rebuild, so an organization that turns
 * Shared sites off, or a trip taken off its homepage, can stay listed elsewhere for up to the refresh interval
 * plus the entity cache's own lag ({@code docs/discover-tenancy-review.md}). The rebuild is lazy, never done
 * inside the save: saves run during startup seeding and on background threads, where the beans the mapper needs
 * do not exist.
 */
@Slf4j
public final class DiscoverIndex {

    /** How stale the snapshot may get before a request rebuilds it (the cross-server convergence bound). */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
    /** Retry pace after a failed rebuild: quick enough to heal, slow enough not to hammer a down cache. */
    static final Duration FAILURE_RETRY = Duration.ofSeconds(5);

    private static final DiscoverIndex INSTANCE = new DiscoverIndex(
            () -> DAO.getInstance().getActiveTrips(LocalDate.now().atStartOfDay(), Cached.YES),
            () -> DAO.getInstance().getOrganizations(Cached.YES),
            DiscoverIndex::liveMapper, LocalDate::now, System::currentTimeMillis);

    private final Supplier<List<Trip>> tripLoader;
    private final Supplier<List<Organization>> orgLoader;
    private final Supplier<BiFunction<Trip, Organization, DiscoverTripDto>> mappers;
    private final Supplier<LocalDate> today;
    private final Supplier<Long> clock;
    /** Bumped by every {@link #invalidate}: a rebuild that raced a save can tell its data may predate it. */
    private final AtomicLong generation = new AtomicLong();
    private volatile Snapshot snapshot;

    /**
     * One listed trip: its public shape, plus what search and the orders need, worked out once per rebuild.
     * {@code countryLabels} are the English names of the trip's countries, side by side with its codes;
     * {@code countryKey} is what the country order sorts it by.
     */
    public record Entry(DiscoverTripDto trip, String haystack, List<String> countryLabels, String countryKey,
            YearMonth month, String titleKey, String orgKey, String etag) {

        /**
         * Everything searchable and sortable is derived from the DTO alone, never from the {@code Trip} it was
         * mapped from: what a response cannot say, a search cannot match on.
         */
        static Entry of(final DiscoverTripDto trip) {
            final List<String> labels = trip.countries().stream().map(Entry::englishNameOf).toList();
            final Locale own = localeOf(trip.language());
            final String haystack = DiscoverQuery.normalize(String.join(" ", text(trip.title()),
                    text(trip.destination()), String.join(" ", trip.countries()), String.join(" ", labels),
                    String.join(" ", trip.countries().stream().map(code -> countryLabelOf(code, own)).toList()),
                    String.join(" ", trip.countries().stream().flatMap(code -> aliasesOf(code).stream()).toList()),
                    text(trip.orgName()), String.join(" ", trip.tagLabels())));
            return new Entry(trip, haystack, labels, countryKeyOf(labels),
                    trip.startDate() == null ? null : YearMonth.from(trip.startDate()),
                    sortKey(trip.title()), sortKey(trip.orgName()), fingerprintOf(trip.toString()));
        }

        private static String text(final String value) {
            return value == null ? "" : value;
        }

        /** One line of normalized text: an order's key travels inside a cursor, whose parts a newline divides. */
        private static String sortKey(final String value) {
            return DiscoverQuery.normalize(value).replaceAll("\\s+", " ");
        }

        /**
         * What the country order sorts a trip by: the alphabetically FIRST of its countries' names, whatever
         * order the trip lists them in, so a trip to France and Portugal sits with France. Null for a trip that
         * names no country, which that order puts last.
         */
        private static String countryKeyOf(final List<String> labels) {
            return labels.stream().map(Entry::sortKey).min(String::compareTo).orElse(null);
        }

        /** The country's English name, as the trip editor's menu spells it; the code itself when it has none. */
        private static String englishNameOf(final String code) {
            final String name = IsoCountries.nameOf(code);
            return name.isBlank() ? code : name;
        }

        /** The country's name in another language: a Spanish trip to Peru is searched for as "Perú" too. */
        private static String countryLabelOf(final String code, final Locale in) {
            final String name = Locale.of("", code).getDisplayCountry(in);
            return name.isBlank() ? code : name;
        }

        /** The English names people type that the JDK does not spell ("Turkey"): search text, never shown. */
        private static List<String> aliasesOf(final String code) {
            return IsoCountries.aliasesOf(code);
        }

        /** The trip's own language, so a Spanish trip to Peru is also found as "Perú"; English when unknown. */
        private static Locale localeOf(final String language) {
            return Language.named(language).map(Language::getLocale).orElse(Locale.ENGLISH);
        }
    }

    /**
     * The listed trips at one moment, in no particular order. {@code fingerprint} changes exactly when what they
     * would say changes, so an unchanged rebuild keeps every {@code ETag} it has handed out.
     */
    public record Snapshot(List<Entry> entries, Map<String, Entry> byId, String fingerprint, long staleAt) {

        /** The listed trip with this id; empty for an unlisted trip and for one that does not exist, alike. */
        public Optional<Entry> find(final String id) {
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }
    }

    /** The application-wide index; construction stores only suppliers, so class load touches no data. */
    public static DiscoverIndex getInstance() {
        return INSTANCE;
    }

    /**
     * An index over explicit sources, apart from the application's own: the test seam. A factory rather than a
     * visible constructor, so the class keeps one way in for production ({@link #getInstance}).
     */
    static DiscoverIndex over(final Supplier<List<Trip>> tripLoader, final Supplier<List<Organization>> orgLoader,
            final Supplier<BiFunction<Trip, Organization, DiscoverTripDto>> mappers, final Supplier<LocalDate> today,
            final Supplier<Long> clock) {
        return new DiscoverIndex(tripLoader, orgLoader, mappers, today, clock);
    }

    private DiscoverIndex(final Supplier<List<Trip>> tripLoader, final Supplier<List<Organization>> orgLoader,
            final Supplier<BiFunction<Trip, Organization, DiscoverTripDto>> mappers, final Supplier<LocalDate> today,
            final Supplier<Long> clock) {
        this.tripLoader = tripLoader;
        this.orgLoader = orgLoader;
        this.mappers = mappers;
        this.today = today;
        this.clock = clock;
    }

    /** The current snapshot, rebuilt first when it is stale. Never throws and never returns null. */
    public Snapshot current() {
        final Snapshot snap = snapshot;
        if (snap != null && clock.get() < snap.staleAt()) {
            return snap;
        }
        return rebuild();
    }

    /**
     * Marks the snapshot stale: the next request rebuilds it. Cheap and safe from anywhere (a save during startup
     * seeding, a background thread): it reads nothing and builds nothing.
     */
    public void invalidate() {
        generation.incrementAndGet();
        final Snapshot snap = snapshot;
        if (snap != null) {
            snapshot = new Snapshot(snap.entries(), snap.byId(), snap.fingerprint(), 0L);
        }
    }

    private static BiFunction<Trip, Organization, DiscoverTripDto> liveMapper() {
        return DiscoverTripMapper.live()::map;
    }

    /**
     * Synchronized rebuild; concurrent requests wait for the one in flight. A FAILED rebuild keeps serving the
     * previous snapshot (an empty one on a cold start) and retries soon: a list a minute old beats a 500 on a
     * public endpoint. One trip that cannot be mapped is left out and logged; it never costs the others.
     */
    private synchronized Snapshot rebuild() {
        final Snapshot snap = snapshot;
        if (snap != null && clock.get() < snap.staleAt()) {
            return snap;    // another request won the race and already rebuilt
        }
        final long builtFrom = generation.get();
        try {
            final List<Entry> entries = listedEntries();
            final Map<String, Entry> byId = new HashMap<>();
            entries.forEach(entry -> byId.put(entry.trip().id(), entry));
            final Snapshot fresh = new Snapshot(List.copyOf(entries), Map.copyOf(byId),
                    fingerprintOf(entries.stream().map(Entry::etag).sorted().toList().toString()),
                    clock.get() + REFRESH_INTERVAL.toMillis());
            snapshot = fresh;
            if (generation.get() != builtFrom) {
                // A save landed while this was reading and may not be in what was read. Checked AFTER
                // publishing, so it and invalidate() cannot both miss: the next request reads again
                // rather than serving the old answer for a whole interval.
                invalidate();
            }
            return fresh;
        } catch (final RuntimeException ex) {
            log.warn("Discover index rebuild failed; serving the previous snapshot", ex);
            final Snapshot fallback = new Snapshot(snap != null ? snap.entries() : List.of(),
                    snap != null ? snap.byId() : Map.of(), snap != null ? snap.fingerprint() : "",
                    clock.get() + FAILURE_RETRY.toMillis());
            snapshot = fallback;
            return fallback;
        }
    }

    private List<Entry> listedEntries() {
        final Map<String, Organization> orgsById = new HashMap<>();
        for (final Organization org : orgLoader.get()) {
            orgsById.put(org.getId().getValue(), org);
        }
        final LocalDate day = today.get();
        final BiFunction<Trip, Organization, DiscoverTripDto> mapper = mappers.get();
        final List<Entry> entries = new ArrayList<>();
        for (final Trip trip : tripLoader.get()) {
            final Optional<Organization> owner = Optional.ofNullable(orgsById.get(trip.getOrgId()));
            if (ListingScope.discoverable(trip, owner, day)) {
                addMapped(entries, trip, owner.get(), mapper);
            }
        }
        return entries;
    }

    private static void addMapped(final List<Entry> entries, final Trip trip, final Organization owner,
            final BiFunction<Trip, Organization, DiscoverTripDto> mapper) {
        try {
            entries.add(Entry.of(mapper.apply(trip, owner)));
        } catch (final RuntimeException ex) {
            log.warn("Discover could not map trip {}; it is left out of this snapshot", trip.getId(), ex);
        }
    }

    /** A short, stable digest of some text: the part of an {@code ETag} that follows what a response says. */
    static String fingerprintOf(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", ex);
        }
    }
}
