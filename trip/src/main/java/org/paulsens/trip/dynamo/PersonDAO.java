package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.cache.SearchIndex;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * People are point entries -- there is deliberately no "load all people" path. Lookups are by id (point cache in
 * front of a Dynamo point read), by email (cached hash in front of the {@code email-index} GSI), or by search
 * ({@link SearchIndex} over the {@link PersonMatch} tokens: name and cell-digit suffixes, whole email and cell).
 */
public class PersonDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    static final String PERSON_TABLE = "people";
    /** Top-level (lowercased) copy of the person's email; partition key of the {@link #EMAIL_INDEX} GSI. */
    static final String EMAIL_ATTR = "email";
    static final String EMAIL_INDEX = "email-index";

    /** Most of one search's cache misses that read DynamoDB at once ({@link #resolvePeople}). */
    private static final int RESOLVE_LOADS = 16;

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PointCache<Person> cache;
    private final SearchIndex searchIndex;

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PointCache.<Person>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PERSON_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(Person.class, json))
                .build();
        this.searchIndex = SearchIndex.builder()
                .cache(cacheClient)
                .key(CacheKeys.PEOPLE_SEARCH)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllSearchTokens)
                .build();
    }

    protected Boolean savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        final String email = normalizedEmail(person);
        if (email != null) {
            map.put(EMAIL_ATTR, AttributeValue.builder().s(email).build());
        }
        // The previous version drives the search-index / email-lookup diff (removing stale tokens and mappings).
        final Person prev = cache.get(person.getId().getValue(), this::loadPersonById).orElse(null);
        final boolean saved = persistence.putItem(b -> b.tableName(PERSON_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return saved && updateCachesForPerson(prev, person);
    }

    protected Optional<Person> getPerson(final Person.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.get(id.getValue(), this::loadPersonById);
    }

    protected Person getPersonByEmail(final String email) {
        final String lowEmail = (email == null) ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (lowEmail.isEmpty()) {
            return null;
        }
        final Person found = lookupByEmail(lowEmail);
        return (found == null) ? localPersonaFallback(lowEmail) : found;
    }

    private Person lookupByEmail(final String lowEmail) {
        final String cachedId = cacheClient.getHashFields(CacheKeys.EMAIL_IDX, List.of(lowEmail))
                .get(lowEmail);
        return resolveEmailMapping(lowEmail, cachedId).orElse(null);
    }

    /**
     * LOCAL MODE ONLY: resolves the bare persona ({@code user2}) to the seeded person whose real address is
     * {@code user2@example.com}.
     *
     * <p>Local logins are typed as personas, not addresses -- the quickstart, every webtest, and years of
     * muscle memory all use {@code user2} / {@code admin}. The seeded family managers, though, must hold
     * MAILABLE addresses, because a family manager without one is refused. That leaves the persona and the
     * person's email deliberately different, and every path that resolves a typed login to a person
     * ({@code setPass}, {@code createCreds}, the fake credential store) goes through here -- so this is the
     * one place the two are reconciled. Production never reaches it: real logins are real addresses, and a
     * miss is a miss.
     */
    private Person localPersonaFallback(final String lowEmail) {
        if (!LocalMode.isLocal() || lowEmail.contains("@")) {
            return null;
        }
        return lookupByEmail(FakeData.localEmail(lowEmail));
    }

    /**
     * People search ({@link PersonMatch} is the rule: names and cell digits match anywhere, email by prefix). The
     * longest query word drives the index lookup -- the index holds suffixes, so a prefix lookup there is a
     * contains lookup -- and every word must then match the resolved person, which also filters out entries
     * stale since the last index rebuild. {@code reach} says which withheld fields this viewer may search by.
     *
     * <p>The index is read in TOKEN order, a window of {@code limit * 3} ids, so a short word common across the
     * whole site can fill the window before every match is seen: {@link Search#cut} reports that, and a page must
     * then say "narrow the search" rather than present the rows as the whole answer.</p>
     */
    protected List<Person> searchPeople(final String query, final int limit) {
        return searchPeople(query, limit, PersonMatch.SHARED_FIELDS).people();
    }

    /** {@link #searchPeople(String, int)} for a viewer with the given {@code reach}, saying whether it was cut. */
    protected Search searchPeople(final String query, final int limit, final PersonMatch.Reach reach) {
        final List<String> words = PersonMatch.queryWords(query);
        final String lookup = PersonMatch.lookupWord(words);
        if (lookup.length() < PersonMatch.MIN_QUERY_LENGTH || limit <= 0) {
            return new Search(List.of(), false);
        }
        final int window = limit * 3;
        final List<String> ids = searchIndex.searchIds(lookup, window);
        final List<Person> matched = resolvePeople(ids).stream()
                .filter(person -> PersonMatch.matchesAll(person, words, reach))
                .sorted()
                .toList();
        return new Search(matched.size() > limit ? matched.subList(0, limit) : matched,
                ids.size() >= window || matched.size() > limit);
    }

    /**
     * A directory search's answer: up to the asked-for number of people, and whether more may exist (the index
     * window filled, or more matched than were asked for).
     */
    public record Search(List<Person> people, boolean cut) {
        public Search {
            people = List.copyOf(people);
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.PERSON_PREFIX);
        cacheClient.removeKey(CacheKeys.EMAIL_IDX);
        searchIndex.invalidate();
    }

    private boolean updateCachesForPerson(final Person prev, final Person person) {
        final String id = person.getId().getValue();
        final String prevEmail = normalizedEmail(prev);
        final String email = normalizedEmail(person);
        updateEmailMapping(id, prevEmail, email, person.getDeleted() != null);
        final boolean entityUpdate = (person.getDeleted() == null)
                ? cache.put(id, person) : cache.remove(id);
        final Set<String> newTokens = (person.getDeleted() == null) ? PersonMatch.indexTokens(person) : Set.of();
        searchIndex.update(id, PersonMatch.indexTokens(prev), newTokens);
        return entityUpdate;
    }

    private void updateEmailMapping(
            final String id, final String prevEmail, final String email, final boolean deleted) {
        if (prevEmail != null && (deleted || !prevEmail.equals(email))) {
            cacheClient.removeHashField(CacheKeys.EMAIL_IDX, prevEmail);
        }
        if (email != null && !deleted) {
            cacheClient.putHashField(CacheKeys.EMAIL_IDX, email, id);
        }
    }

    private Optional<Person> resolveEmailMapping(final String lowEmail, final String cachedId) {
        if (cachedId != null) {
            final Optional<Person> person = cache.get(cachedId, this::loadPersonById);
            return emailStillMatches(person, lowEmail) ? person : queryEmailIndex(lowEmail);
        }
        return queryEmailIndex(lowEmail);
    }

    private static boolean emailStillMatches(final Optional<Person> person, final String lowEmail) {
        return person.isPresent() && lowEmail.equalsIgnoreCase(
                SearchIndex.normalizeToken(person.get().getEmail()));
    }

    private Optional<Person> queryEmailIndex(final String lowEmail) {
        return cacheEmailHit(lowEmail, persistence.queryAll(b -> b.tableName(PERSON_TABLE)
                        .indexName(EMAIL_INDEX)
                        .keyConditionExpression(EMAIL_ATTR + " = :e")
                        .expressionAttributeValues(Map.of(":e", persistence.toStrAttr(lowEmail)))
                        .build())
                .stream()
                .map(item -> rows.parseItem(Person.class, item))
                .filter(p -> p != null && p.getDeleted() == null)
                .findFirst());
    }

    private Optional<Person> cacheEmailHit(final String lowEmail, final Optional<Person> person) {
        if (person.isEmpty()) {
            return person;
        }
        final String id = person.get().getId().getValue();
        cache.put(id, person.get());
        cacheClient.putHashField(CacheKeys.EMAIL_IDX, lowEmail, id);
        return person;
    }

    private Person loadPersonById(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final Person person = rows.get(persistence, PERSON_TABLE, key, Person.class);
        return (person == null || person.getDeleted() != null) ? null : person;
    }

    /**
     * The people behind a search's ids, in order, skipping ids that name nobody. Forked per id so the cache reads
     * go out together and Lettuce pipelines them on its connection: awaiting each GET before sending the next cost
     * one round trip per row (measured 2026-11-24 on a local 3-shard cluster, 180 ids: 9.0 ms one at a time, 0.9
     * ms concurrent). An MGET was measured too and is no faster: the cluster client splits it into one command per
     * hash slot anyway. Only the misses that fall through to DynamoDB are bounded ({@link #RESOLVE_LOADS}), since
     * its client pools 200 connections and an unbounded fan-out there fails as nulls.
     */
    private List<Person> resolvePeople(final List<String> ids) {
        final Semaphore loads = new Semaphore(RESOLVE_LOADS);
        try (var scope = StructuredTaskScope.open()) {
            final List<StructuredTaskScope.Subtask<Optional<Person>>> tasks = new ArrayList<>(ids.size());
            for (final String id : ids) {
                tasks.add(scope.fork(() -> cache.get(id, key -> loadBounded(loads, key))));
            }
            scope.join();
            return tasks.stream().map(StructuredTaskScope.Subtask::get).flatMap(Optional::stream).toList();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resolving people", ex);
        }
    }

    private Person loadBounded(final Semaphore loads, final String id) {
        loads.acquireUninterruptibly();
        try {
            return loadPersonById(id);
        } finally {
            loads.release();
        }
    }

    private Map<String, Set<String>> loadAllSearchTokens() {
        final Map<String, Set<String>> tokens = new HashMap<>();
        persistence.scanAll(b -> b.consistentRead(false).limit(2000).tableName(PERSON_TABLE).build())
                .stream()
                .map(it -> rows.parseItem(Person.class, it))
                .filter(p -> p != null && p.getDeleted() == null)
                .forEach(p -> tokens.put(p.getId().getValue(), PersonMatch.indexTokens(p)));
        return tokens;
    }

    private static String normalizedEmail(final Person person) {
        if (person == null) {
            return null;
        }
        final String email = SearchIndex.normalizeToken(person.getEmail());
        return email.isEmpty() ? null : email;
    }
}
