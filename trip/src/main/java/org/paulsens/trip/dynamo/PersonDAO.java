package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.FullTableCache;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Slf4j
public class PersonDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String PERSON_TABLE = "people";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final FullTableCache<Person.Id, Person> cache;

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = FullTableCache.<Person.Id, Person>builder()
                .cache(cacheClient)
                .key(CacheKeys.PEOPLE)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(Person::getId)
                .idFormatter(Person.Id::getValue)
                .serializer(this::toJson)
                .deserializer(this::parsePerson)
                .order(Comparator.naturalOrder())
                .build();
    }

    protected CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        return persistence.putItem(b -> b.tableName(PERSON_TABLE).item(map))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        ? updateCacheForPerson(person)
                        : CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> updateCacheForPerson(final Person person) {
        return (person.getDeleted() == null) ? cache.put(person) : cache.remove(person.getId());
    }

    protected CompletableFuture<List<Person>> getPeople() {
        return cache.getAll(this::loadPeople);
    }

    protected CompletableFuture<Optional<Person>> getPerson(final Person.Id id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return cache.getOne(id, this::loadPeople);
    }

    protected CompletableFuture<Person> getPersonByEmail(final String email) {
        final String lowEmail = email.toLowerCase(Locale.getDefault());
        return getPeople()
                .thenApply(people -> people.stream()
                        .filter(person -> lowEmail.equalsIgnoreCase(person.getEmail()))
                        .findAny()
                        .orElse(null));
    }

    public void clearCache() {
        cache.invalidate().join();
    }

    private CompletableFuture<List<Person>> loadPeople() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(2000).tableName(PERSON_TABLE).build())
                .thenApply(items -> items.stream()
                        .map(it -> toPerson(it.get(CONTENT)))
                        .filter(p -> p != null && p.getDeleted() == null)
                        .toList());
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
