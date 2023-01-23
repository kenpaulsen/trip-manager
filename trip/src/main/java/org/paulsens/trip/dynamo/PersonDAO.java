package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Slf4j
public class PersonDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String PERSON_TABLE = "people";

    private final Map<Person.Id, Person> peopleCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected PersonDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        return persistence.putItem(b -> b.tableName(PERSON_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ?
                        persistence.cacheOne(peopleCache, person, person.getId(), true) :
                        persistence.clearCache(peopleCache, false));
    }

    protected CompletableFuture<List<Person>> getPeople() {
        if (!peopleCache.isEmpty()) {
            return CompletableFuture.completedFuture(
                    peopleCache.values().stream()
                            .sorted()
                            .toList());
        }
        return persistence.scan(b -> b.consistentRead(false).limit(1000).tableName(PERSON_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toPerson(it.get(CONTENT)))
                        .sorted()
                        .toList())
                .thenApply(list -> persistence.cacheAll(peopleCache, list, Person::getId));
    }

    protected CompletableFuture<Optional<Person>> getPerson(final Person.Id id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Person person = peopleCache.get(id);
        if (person != null) {
            return CompletableFuture.completedFuture(Optional.of(person));
        }
        return getPeople().thenApply(people -> Optional.ofNullable(peopleCache.get(id))); // Load all the people
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
        peopleCache.clear();
    }

    private Person toPerson(final AttributeValue content) {
        if (content == null) {
            return null;
        }
        try {
            return mapper.readValue(content.s(), Person.class);
        } catch (final IOException ex) {
            log.error("Unable to parse person record: " + content, ex);
            return null;
        }
    }
}
