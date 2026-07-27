package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheConfig;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.cache.ValkeyCacheClient;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.security.Pepper;

@Slf4j
public class DAO {
    @Getter
    private final ObjectMapper mapper;
    private final CacheClient cacheClient;
    private final PersonDAO personDao;
    private final TripEventDAO tripEventDao;
    private final TripDAO tripDao;
    private final RegistrationDAO regDao;
    private final TransactionDAO txDao;
    private final CredentialsDAO credDao;
    private final TodoDAO todoDao;
    private final PersonDataValueDAO pdvDao;
    private final PrivilegesDAO privDao;
    private final ConfigDAO configDao;
    private final MediaDAO mediaDao;
    private final AuditDAO auditDao;
    private final BindingDAO bindingDao;

    // This flag is set in the web.xml
    private static DAO inst;

    private DAO() {
        this(createTripPersistence(), createCacheClient());
    }

    private DAO(final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = createObjectMapper();
        this.cacheClient = cacheClient;
        this.personDao = new PersonDAO(mapper, persistence, cacheClient);
        this.tripEventDao = new TripEventDAO(mapper, persistence, cacheClient);
        this.tripDao = new TripDAO(mapper, persistence, tripEventDao, cacheClient);
        this.regDao = new RegistrationDAO(mapper, persistence, cacheClient);
        this.txDao = new TransactionDAO(mapper, persistence, cacheClient);
        this.credDao = new CredentialsDAO(persistence, personDao, createPasswordHasher());
        this.todoDao = new TodoDAO(mapper, persistence, cacheClient);
        this.pdvDao = new PersonDataValueDAO(mapper, persistence, cacheClient);
        this.privDao = new PrivilegesDAO(mapper, persistence, cacheClient);
        this.configDao = new ConfigDAO(mapper, persistence, cacheClient);
        this.mediaDao = new MediaDAO(mapper, persistence, cacheClient);
        // No cacheClient: the audit table is write-heavy and read rarely, so caching it would cost
        // invalidation traffic to speed up something nobody does -- and a stale audit view is worse than a slow one.
        this.auditDao = new AuditDAO(mapper, persistence);
        this.bindingDao = new BindingDAO(persistence, cacheClient);
    }

    public static DAO getInstance() {
        if (inst == null) {
            FakeData.initFakeData();
            inst = new DAO();
            FakeData.addFakeData();
        }
        return inst;
    }

    /**
     * Selects the shared-cache client. Outside local mode the {@link CacheConfig} mode decides (valkey / memory /
     * off), so any external client using this library resolves identically and a write with
     * {@code TRIP_VALKEY_URI} set is immediately visible to every running instance.
     *
     * <p>Local mode uses the zero-dependency in-memory client, so unit tests and laptop runs need no daemon.
     * Faking the datastore and faking the cache are separate choices, though, and setting
     * {@value #LOCAL_USE_CONFIGURED_CACHE} opts local mode into whatever {@link CacheConfig} resolves. That
     * combination -- fake persistence behind a real Valkey -- is what lets the functional tests exercise
     * {@link ValkeyCacheClient} itself: its serialization, key layout, loaded-sentinels and TTLs, none of which
     * the in-memory client models.</p>
     */
    private static CacheClient createCacheClient() {
        return (FakeData.isLocal() && !localModeUsesConfiguredCache())
                ? new InMemoryCacheClient()
                : createConfiguredCacheClient();
    }

    /**
     * Opts local mode into the configured cache client.
     *
     * <p>Deliberately a system property with <em>no</em> environment-variable fallback, unlike every other cache
     * setting. {@link FakeData#isLocal()} is true whenever there is no {@code FacesContext}, which includes
     * {@code mvn test} -- so if this were inferred from {@code TRIP_VALKEY_URI}, running the unit tests in a shell
     * that had sourced the production CLI environment would silently point them at the live cache. A property only
     * a test harness passes cannot be switched on by an ambient variable.</p>
     */
    private static boolean localModeUsesConfiguredCache() {
        return Boolean.parseBoolean(System.getProperty(LOCAL_USE_CONFIGURED_CACHE));
    }

    static final String LOCAL_USE_CONFIGURED_CACHE = "trip.cache.local.useConfigured";

    private static CacheClient createConfiguredCacheClient() {
        final CacheConfig config = CacheConfig.resolve();
        log.info("Shared cache mode: {}", config.getMode());
        return switch (config.getMode()) {
            case VALKEY -> new ValkeyCacheClient(config);
            case MEMORY -> new InMemoryCacheClient();
            case OFF -> new NoopCacheClient();
        };
    }

    // People
    public CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        return personDao.savePerson(person);
    }
    public CompletableFuture<List<Person>> searchPeople(final String query, final int limit) {
        return personDao.searchPeople(query, limit);
    }
    public CompletableFuture<Optional<Person>> getPerson(final Person.Id id) {
        return personDao.getPerson(id);
    }
    public CompletableFuture<Person> getPersonByEmail(final String email) {
        return personDao.getPersonByEmail(email);
    }

    // Trips
    public CompletableFuture<Boolean> saveTrip(final Trip trip) throws IOException {
        return tripDao.saveTrip(trip);
    }
    public CompletableFuture<Optional<Trip>> getTrip(final String id) {
        return tripDao.getTrip(id);
    }
    public CompletableFuture<List<Trip>> getActiveTrips(final LocalDateTime cutoff) {
        return tripDao.getActiveTrips(cutoff);
    }
    public CompletableFuture<List<Trip>> getInactiveTrips(final LocalDateTime cutoff, final int limit) {
        return tripDao.getInactiveTrips(cutoff, limit);
    }
    public CompletableFuture<List<Trip>> getRecentTrips(final int limit) {
        return tripDao.getRecentTrips(limit);
    }
    public CompletableFuture<List<Trip>> getTripsForUser(final Person.Id userId) {
        return tripDao.getTripsForUser(userId);
    }

    // Trip Events
    public CompletableFuture<TripEvent> getTripEvent(final String id) {
        return tripEventDao.getTripEvent(id);
    }
    public CompletableFuture<Boolean> saveTripEvent(final TripEvent te) {
        return tripEventDao.saveTripEvent(te);
    }
    public CompletableFuture<Boolean> saveAllTripEvents(final Trip trip) {
        return tripEventDao.saveAllTripEvents(trip);
    }

    // Registrations
    public CompletableFuture<Boolean> saveRegistration(final Registration reg) throws IOException {
        return regDao.saveRegistration(reg);
    }
    public CompletableFuture<List<Registration>> getRegistrations(final String tripId) {
        return regDao.getRegistrations(tripId);
    }
    public CompletableFuture<Optional<Registration>> getRegistration(final String tripId, final Person.Id userId) {
        return regDao.getRegistration(tripId, userId);
    }

    // Transactions
    public CompletableFuture<List<Transaction>> getTransactions(final Person.Id userId) {
        return txDao.getTransactions(userId);
    }
    public CompletableFuture<Optional<Transaction>> getTransaction(final Person.Id userId, final String txId) {
        return txDao.getTransaction(userId, txId);
    }
    public CompletableFuture<Boolean> saveTransaction(final Transaction tx) throws IOException {
        return txDao.saveTransaction(tx);
    }

    // Credentials
    public CompletableFuture<Creds> adminGetCredsByEmail(final String email) {
        return credDao.adminGetCredsByEmail(email);
    }
    public CompletableFuture<Creds> getCredsByEmailAndPass(final String email, final String pass) {
        return credDao.getCredsByEmailAndPass(email, pass);
    }
    public CompletableFuture<Creds> getCredsByEmailAdminOnly(final String email, final Person.Id id) {
        return credDao.getCredsByEmailAdminOnly(email, id);
    }
    public Long updateLastLogin(final Creds creds) {
        return credDao.updateLastLogin(creds);
    }
    public Optional<Creds> createCreds(final String email) {
        return credDao.createCreds(email);
    }
    public CompletableFuture<Boolean> saveCreds(final Creds creds) {
        return credDao.saveCreds(creds);
    }
    public CompletableFuture<Boolean> removeCreds(final String email) {
        return credDao.removeCreds(email);
    }

    // Todos
    public CompletableFuture<Boolean> saveTodo(final TodoItem todo) throws IOException {
        return todoDao.saveTodo(todo);
    }
    public CompletableFuture<List<TodoItem>> getTodoItems(final String tripId) {
        return todoDao.getTodoItems(tripId);
    }
    public CompletableFuture<Optional<TodoItem>> getTodoItem(final String tripId, final DataId pdvId){
        return todoDao.getTodoItem(tripId, pdvId);
    }

    // Per-User Stored Data
    public CompletableFuture<Boolean> savePersonDataValue(final PersonDataValue pdv) throws IOException {
        return pdvDao.savePersonDataValue(pdv);
    }
    public CompletableFuture<Map<DataId, PersonDataValue>> getPersonDataValues(final Person.Id pid) {
        return pdvDao.getPersonDataValues(pid);
    }
    public CompletableFuture<Optional<PersonDataValue>> getPersonDataValue(final Person.Id pid, final DataId pdvId) {
        return pdvDao.getPersonDataValue(pid, pdvId);
    }

    // Privileges
    public CompletableFuture<Boolean> savePrivilege(final Privilege priv) {
        return privDao.savePrivilege(priv);
    }
    // Managed media metadata (see MediaDAO); the bytes live in S3.
    public CompletableFuture<Optional<MediaItem>> getMedia(final String id) {
        return mediaDao.getMedia(id);
    }
    public CompletableFuture<List<MediaItem>> getAllMedia() {
        return mediaDao.getAllMedia();
    }
    public CompletableFuture<List<MediaItem>> getMediaInSlot(final String slot) {
        return mediaDao.getMediaInSlot(slot);
    }
    public CompletableFuture<Boolean> saveMedia(final MediaItem item) {
        return mediaDao.saveMedia(item);
    }
    public CompletableFuture<Boolean> deleteMedia(final String id) {
        return mediaDao.deleteMedia(id);
    }
    /**
     * Drops the cached media inventory so the next read re-loads it from the table.
     *
     * <p>Needed whenever rows are written WITHOUT going through this DAO -- a maintenance script, a console
     * edit, a bulk import. The cache holds the whole table under one 'loaded' marker and lives in Valkey, so
     * it survives deploys and task restarts: rows written behind its back stay invisible until the soft TTL
     * expires a day later. That is exactly what happened after the media reconcile (2026-07-27): 362 rows in
     * the table, 30 on the page.
     */
    public void clearMediaCache() {
        mediaDao.clearCache();
    }

    // Runtime settings (see ConfigDAO). Reads never throw; callers always supply a default.
    public CompletableFuture<Optional<Config>> getConfig(final String name) {
        return configDao.getConfig(name);
    }
    public CompletableFuture<List<Config>> getAllConfig() {
        return configDao.getAllConfig();
    }
    public CompletableFuture<Boolean> saveConfig(final Config config) {
        return configDao.saveConfig(config);
    }

    // Audit trail (see AuditDAO). Deliberately uncached: written constantly, read only by the admin page.
    public CompletableFuture<Boolean> saveAuditEvent(final AuditEvent event) {
        return auditDao.saveAuditEvent(event);
    }
    public CompletableFuture<AuditPage> getAuditEvents(final AuditQuery query) {
        return auditDao.getAuditEvents(query);
    }
    public CompletableFuture<List<AuditEvent>> exportAuditEvents(final AuditQuery query) {
        return auditDao.exportAuditEvents(query);
    }

    public CompletableFuture<Optional<Privilege>> getPrivilege(final String name) {
        return privDao.getPrivilege(name);
    }
    public CompletableFuture<List<Privilege>> getGlobalPrivileges() {
        return privDao.getGlobalPrivileges();
    }
    public CompletableFuture<List<Privilege>> getTripPrivileges(final String tripId) {
        return privDao.getTripPrivileges(tripId);
    }

    // Bindings
    public CompletableFuture<Boolean> saveBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean bidirectionalBindings) {
        return bindingDao.saveBinding(id, type, destId, destType, bidirectionalBindings);
    }
    public CompletableFuture<List<String>> getBindings(
            final String name, final BindingType type, BindingType destType) {
        return bindingDao.getBindings(name, type, destType);
    }
    public CompletableFuture<Boolean> removeBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean bidirectionalBindings) {
        return bindingDao.removeBinding(id, type, destId, destType, bidirectionalBindings);
    }

    /**
     * Drops every entry in the shared cache's data namespace (never a full FLUSH -- on ElastiCache Serverless that
     * would also wipe the distributed sessions). Used by tests and the admin "clear all caches" action; with the
     * shared cache this now flushes for every running instance at once.
     */
    public void clearAllCaches() {
        cacheClient.clearNamespace(CacheKeys.FORMAT_VERSION).join();
    }

    private ObjectMapper createObjectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Forward/backward compatibility: instances of different versions share the same Valkey cache blobs
        // (and DynamoDB rows), so reads must tolerate JSON written by a newer (or older) schema. Unknown JSON
        // properties are ignored rather than failing the read -- required for safe rolling deploys.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private static Persistence createTripPersistence() {
        final Persistence result;
        if (FakeData.isLocal()) {
            // Local development only -- don't talk to dynamo
            result = FakeData.createFakePersistence();
        } else {
            // The real deal
            result = new DynamoPersistence();
        }
        return result;
    }

    // In local mode the fake credentials are plaintext and nothing is persisted, so hashing needs no pepper -- and
    // resolving one would reach AWS Secrets Manager, which local dev and `mvn test` must never do (the same
    // isolation principle behind the cache's local opt-in). Only the real deployment resolves the configured pepper.
    private static PasswordHasher createPasswordHasher() {
        return FakeData.isLocal() ? new PasswordHasher(Pepper.none()) : PasswordHasher.getInstance();
    }
}
