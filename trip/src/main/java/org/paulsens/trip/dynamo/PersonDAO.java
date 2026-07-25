package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
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
 * front of a Dynamo point read), by email (cached hash in front of the {@code email-index} GSI), or by prefix
 * search ({@link SearchIndex} over name/nickname/email/cell tokens).
 */
@Slf4j
public class PersonDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String PERSON_TABLE = "people";
    /** Top-level (lowercased) copy of the person's email; partition key of the {@link #EMAIL_INDEX} GSI. */
    static final String EMAIL_ATTR = "email";
    static final String EMAIL_INDEX = "email-index";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PointCache<Person> cache;
    private final SearchIndex searchIndex;

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PointCache.<Person>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PERSON_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parsePerson)
                .build();
        this.searchIndex = SearchIndex.builder()
                .cache(cacheClient)
                .key(CacheKeys.PEOPLE_SEARCH)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllSearchTokens)
                .build();
    }

    protected CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        final String email = normalizedEmail(person);
        if (email != null) {
            map.put(EMAIL_ATTR, AttributeValue.builder().s(email).build());
        }
        // The previous version drives the search-index / email-lookup diff (removing stale tokens and mappings).
        return getPerson(person.getId())
                .thenCompose(prev -> persistence.putItem(b -> b.tableName(PERSON_TABLE).item(map))
                        .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                                ? updateCachesForPerson(prev.orElse(null), person)
                                : CompletableFuture.completedFuture(false)));
    }

    protected CompletableFuture<Optional<Person>> getPerson(final Person.Id id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return cache.get(id.getValue(), this::loadPersonById);
    }

    protected CompletableFuture<Person> getPersonByEmail(final String email) {
        final String lowEmail = (email == null) ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (lowEmail.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return cacheClient.getHashFields(CacheKeys.EMAIL_IDX, List.of(lowEmail))
                .thenCompose(found -> resolveEmailMapping(lowEmail, found.get(lowEmail)))
                .thenApply(person -> person.orElse(null));
    }

    /**
     * Prefix search over name/nickname/email/cell. The first (longest) query word drives the index lookup; every
     * word must then match the resolved person, which also filters out entries stale since the last index rebuild.
     */
    protected CompletableFuture<List<Person>> searchPeople(final String query, final int limit) {
        final List<String> words = queryWords(query);
        if (words.isEmpty() || limit <= 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        final String prefix = words.stream().reduce((a, b) -> b.length() > a.length() ? b : a).get();
        return searchIndex.searchIds(prefix, limit * 3)
                .thenCompose(ids -> resolvePeople(ids))
                .thenApply(people -> people.stream()
                        .filter(person -> matchesAllWords(person, words))
                        .sorted()
                        .limit(limit)
                        .toList());
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.PERSON_PREFIX).join();
        cacheClient.removeKey(CacheKeys.EMAIL_IDX).join();
        searchIndex.invalidate().join();
    }

    /** The (normalized, non-empty) search tokens for one person. */
    static Set<String> searchTokens(final Person person) {
        if (person == null) {
            return Set.of();
        }
        final Set<String> tokens = new HashSet<>();
        Stream.of(person.getFirst(), person.getLast(), person.getNickname(), person.getEmail(), person.getCell())
                .map(SearchIndex::normalizeToken)
                .filter(token -> !token.isEmpty())
                .forEach(tokens::add);
        return tokens;
    }

    private CompletableFuture<Boolean> updateCachesForPerson(final Person prev, final Person person) {
        final String id = person.getId().getValue();
        final String prevEmail = normalizedEmail(prev);
        final String email = normalizedEmail(person);
        final CompletableFuture<Boolean> emailUpdate = updateEmailMapping(id, prevEmail, email,
                person.getDeleted() != null);
        final CompletableFuture<Boolean> entityUpdate = (person.getDeleted() == null)
                ? cache.put(id, person) : cache.remove(id);
        final Set<String> newTokens = (person.getDeleted() == null) ? searchTokens(person) : Set.of();
        final CompletableFuture<Boolean> searchUpdate = searchIndex.update(id, searchTokens(prev), newTokens);
        return CompletableFuture.allOf(emailUpdate, entityUpdate, searchUpdate)
                .thenApply(ignored -> entityUpdate.join());
    }

    private CompletableFuture<Boolean> updateEmailMapping(
            final String id, final String prevEmail, final String email, final boolean deleted) {
        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        if (prevEmail != null && (deleted || !prevEmail.equals(email))) {
            result = cacheClient.removeHashField(CacheKeys.EMAIL_IDX, prevEmail);
        }
        if (email != null && !deleted) {
            result = result.thenCompose(ignored -> cacheClient.putHashField(CacheKeys.EMAIL_IDX, email, id));
        }
        return result;
    }

    private CompletableFuture<Optional<Person>> resolveEmailMapping(final String lowEmail, final String cachedId) {
        if (cachedId != null) {
            return cache.get(cachedId, this::loadPersonById)
                    .thenCompose(person -> emailStillMatches(person, lowEmail)
                            ? CompletableFuture.completedFuture(person)
                            : queryEmailIndex(lowEmail));
        }
        return queryEmailIndex(lowEmail);
    }

    private static boolean emailStillMatches(final Optional<Person> person, final String lowEmail) {
        return person.isPresent() && lowEmail.equalsIgnoreCase(
                SearchIndex.normalizeToken(person.get().getEmail()));
    }

    private CompletableFuture<Optional<Person>> queryEmailIndex(final String lowEmail) {
        return persistence.queryAll(b -> b.tableName(PERSON_TABLE)
                        .indexName(EMAIL_INDEX)
                        .keyConditionExpression(EMAIL_ATTR + " = :e")
                        .expressionAttributeValues(Map.of(":e", persistence.toStrAttr(lowEmail)))
                        .build())
                .thenCompose(items -> cacheEmailHit(lowEmail, items.stream()
                        .map(item -> toPerson(item.get(CONTENT)))
                        .filter(p -> p != null && p.getDeleted() == null)
                        .findFirst()));
    }

    private CompletableFuture<Optional<Person>> cacheEmailHit(final String lowEmail, final Optional<Person> person) {
        if (person.isEmpty()) {
            return CompletableFuture.completedFuture(person);
        }
        final String id = person.get().getId().getValue();
        return cache.put(id, person.get())
                .thenCompose(ignored -> cacheClient.putHashField(CacheKeys.EMAIL_IDX, lowEmail, id))
                .thenApply(ignored -> person);
    }

    private CompletableFuture<Person> loadPersonById(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return persistence.getItem(b -> b.key(key).tableName(PERSON_TABLE).build())
                .thenApply(resp -> toPerson(resp.item().get(CONTENT)))
                .thenApply(person -> (person == null || person.getDeleted() != null) ? null : person);
    }

    private CompletableFuture<List<Person>> resolvePeople(final List<String> ids) {
        final List<CompletableFuture<Optional<Person>>> futures = ids.stream()
                .map(id -> cache.get(id, this::loadPersonById))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    final List<Person> people = new ArrayList<>();
                    futures.forEach(f -> f.join().ifPresent(people::add));
                    return people;
                });
    }

    private static List<String> queryWords(final String query) {
        final String normalized = SearchIndex.normalizeToken(query);
        return Stream.of(normalized.split("[\\s,]+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    private static boolean matchesAllWords(final Person person, final List<String> words) {
        final Set<String> tokens = searchTokens(person);
        return words.stream().allMatch(word -> tokens.stream().anyMatch(token -> token.contains(word)));
    }

    private CompletableFuture<Map<String, Set<String>>> loadAllSearchTokens() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(2000).tableName(PERSON_TABLE).build())
                .thenApply(items -> {
                    final Map<String, Set<String>> tokens = new HashMap<>();
                    items.stream()
                            .map(it -> toPerson(it.get(CONTENT)))
                            .filter(p -> p != null && p.getDeleted() == null)
                            .forEach(p -> tokens.put(p.getId().getValue(), searchTokens(p)));
                    return tokens;
                });
    }

    private static String normalizedEmail(final Person person) {
        if (person == null) {
            return null;
        }
        final String email = SearchIndex.normalizeToken(person.getEmail());
        return email.isEmpty() ? null : email;
    }

    private Person toPerson(final AttributeValue content) {
        return (content == null) ? null : parsePerson(content.s());
    }

    private Person parsePerson(final String json) {
        try {
            return mapper.readValue(json, Person.class);
        } catch (final IOException ex) {
            log.error("Unable to parse person record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Person person) {
        try {
            return mapper.writeValueAsString(person);
        } catch (final IOException ex) {
            log.error("Unable to serialize person: " + person.getId(), ex);
            return null;
        }
    }
}
