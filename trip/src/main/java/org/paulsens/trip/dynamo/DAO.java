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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheConfig;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NearCacheClient;
import org.paulsens.trip.cache.NearCacheContext;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.cache.ValkeyCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RememberToken;
import org.paulsens.trip.model.TemplateRecord;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.security.Pepper;

@Slf4j
public class DAO {
    @Getter
    private final ObjectMapper mapper;
    private final CacheClient cacheClient;
    private final PersonDAO personDao;
    private final FamilyDAO familyDao;
    private final TripEventDAO tripEventDao;
    private final TripDAO tripDao;
    private final RegistrationDAO regDao;
    private final TransactionDAO txDao;
    private final CredentialsDAO credDao;
    private final RememberMeDAO rememberMeDao;
    private final PasskeyDAO passkeyDao;
    private final TodoDAO todoDao;
    private final PersonDataValueDAO pdvDao;
    private final PrivilegesDAO privDao;
    private final ConfigDAO configDao;
    private final MediaDAO mediaDao;
    private final TemplateDAO templateDao;
    private final ContentDAO contentDao;
    private final AuditDAO auditDao;
    private final BindingDAO bindingDao;
    private final ChatDAO chatDao;
    private final ChatInviteDAO chatInviteDao;

    // This flag is set in the web.xml
    private static DAO inst;

    private DAO() {
        this(createTripPersistence(), createCacheClient());
    }

    private DAO(final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = createObjectMapper();
        this.cacheClient = cacheClient;
        this.personDao = new PersonDAO(mapper, persistence, cacheClient);
        this.familyDao = new FamilyDAO(mapper, persistence, cacheClient);
        this.tripEventDao = new TripEventDAO(mapper, persistence, cacheClient);
        this.tripDao = new TripDAO(mapper, persistence, tripEventDao, cacheClient);
        this.regDao = new RegistrationDAO(mapper, persistence, cacheClient);
        this.txDao = new TransactionDAO(mapper, persistence, cacheClient);
        this.credDao = new CredentialsDAO(persistence, personDao, createPasswordHasher());
        // No cacheClient: remember-me rows authenticate, so a stale read is a security bug (see the DAO).
        this.rememberMeDao = new RememberMeDAO(persistence);
        this.passkeyDao = new PasskeyDAO(persistence);
        this.todoDao = new TodoDAO(mapper, persistence, cacheClient);
        this.pdvDao = new PersonDataValueDAO(mapper, persistence, cacheClient);
        this.privDao = new PrivilegesDAO(mapper, persistence, cacheClient);
        this.configDao = new ConfigDAO(mapper, persistence, cacheClient);
        this.mediaDao = new MediaDAO(mapper, persistence, cacheClient);
        this.templateDao = new TemplateDAO(mapper, persistence, cacheClient);
        this.contentDao = new ContentDAO(mapper, persistence, cacheClient);
        // No cacheClient: the audit table is write-heavy and read rarely, so caching it would cost
        // invalidation traffic to speed up something nobody does -- and a stale audit view is worse than a slow one.
        this.auditDao = new AuditDAO(mapper, persistence);
        this.bindingDao = new BindingDAO(persistence, cacheClient);
        this.chatDao = new ChatDAO(mapper, persistence, cacheClient);
        // No cacheClient: invite rows authorize, so a stale read is a security bug (a revoked link must die now).
        this.chatInviteDao = new ChatInviteDAO(persistence);
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
     * The shared cache client this DAO resolved, for collaborators that need the cache directly rather than
     * through a DAO -- today {@code ChatRateLimiter}, whose counters must be visible to every task.
     *
     * <p>Exposed deliberately, and read-only. The alternative in use before this existed was reflection into the
     * private field, which silently fell back to a private in-memory client whenever it failed, quietly turning
     * shared rate limits into per-instance ones.
     */
    public CacheClient getCacheClient() {
        return cacheClient;
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
     * setting. {@code mvn test} runs in local mode ({@link LocalMode}, switched on by the surefire configuration),
     * so if this were inferred from {@code TRIP_VALKEY_URI}, running the unit tests in a shell that had sourced
     * the production CLI environment would silently point them at the live cache. A property only a test harness
     * passes cannot be switched on by an ambient variable.</p>
     */
    private static boolean localModeUsesConfiguredCache() {
        return Boolean.parseBoolean(System.getProperty(LOCAL_USE_CONFIGURED_CACHE));
    }

    static final String LOCAL_USE_CONFIGURED_CACHE = "trip.cache.local.useConfigured";

    private static CacheClient createConfiguredCacheClient() {
        final CacheConfig config = CacheConfig.resolve();
        log.info("Shared cache mode: {}", config.getMode());
        return switch (config.getMode()) {
            // Only the real shared cache gets the near-cache in front of it: the in-memory client IS the
            // local datastore (wrapping it would double the invalidation surface for no round trips saved),
            // and CacheSupport's instanceof check must keep seeing it directly.
            case VALKEY -> new NearCacheClient(new ValkeyCacheClient(config), DAO::readNearCacheTuning);
            case MEMORY -> new InMemoryCacheClient();
            case OFF -> new NoopCacheClient();
        };
    }

    /**
     * The near-cache's settings reader: {@code {ttlSeconds, checkSeconds}} through the normal DAL with
     * {@link Cached#NO}. Static and lazy on purpose -- it is handed to the client as a supplier before this
     * singleton exists, and only ever invoked from the client's tuning re-sync (admin save or its lazy
     * background check), never from the cache read path (the ConfigDAO-sits-on-these-caches cycle).
     */
    private static long[] readNearCacheTuning() {
        return new long[] {
                settingSeconds(KnownSettings.CACHE_NEAR_TTL_SECONDS),
                settingSeconds(KnownSettings.CACHE_NEAR_CHECK_SECONDS)};
    }

    private static long settingSeconds(final SettingDef def) {
        final String raw = getInstance().getConfig(def.getName(), Cached.NO)
                .map(Config::getValue).orElse(def.getDefaultValue());
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ex) {
            return def.longDefault();
        }
    }

    // People
    public Boolean savePerson(final Person person) throws IOException {
        return personDao.savePerson(person);
    }
    public List<Person> searchPeople(final String query, final int limit) {
        return personDao.searchPeople(query, limit);
    }
    public List<Person> searchPeople(final String query, final int limit, final Cached cached) {
        return NearCacheContext.call(cached, () -> personDao.searchPeople(query, limit));
    }
    public Optional<Person> getPerson(final Person.Id id) {
        return personDao.getPerson(id);
    }
    public Optional<Person> getPerson(final Person.Id id, final Cached cached) {
        return NearCacheContext.call(cached, () -> personDao.getPerson(id));
    }
    public Person getPersonByEmail(final String email) {
        return personDao.getPersonByEmail(email);
    }
    public Person getPersonByEmail(final String email, final Cached cached) {
        return NearCacheContext.call(cached, () -> personDao.getPersonByEmail(email));
    }

    // Families
    /**
     * Conditionally saves this family (optimistic version guard). Throws
     * {@link software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException} on a lost race --
     * the caller must re-read the family and recompute its change against the winning row.
     */
    public Boolean saveFamily(final Family family) throws IOException {
        return familyDao.saveFamily(family);
    }
    public Optional<Family> getFamily(final Family.Id id) {
        return familyDao.getFamily(id);
    }
    public Optional<Family> getFamily(final Family.Id id, final Cached cached) {
        return NearCacheContext.call(cached, () -> familyDao.getFamily(id));
    }
    public Boolean deleteFamily(final Family.Id id) {
        return familyDao.deleteFamily(id);
    }

    // Trips
    public Boolean saveTrip(final Trip trip) throws IOException {
        return tripDao.saveTrip(trip);
    }
    public Optional<Trip> getTrip(final String id) {
        return tripDao.getTrip(id);
    }
    public Optional<Trip> getTrip(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripDao.getTrip(id));
    }
    public List<Trip> getActiveTrips(final LocalDateTime cutoff) {
        return tripDao.getActiveTrips(cutoff);
    }
    public List<Trip> getActiveTrips(final LocalDateTime cutoff, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripDao.getActiveTrips(cutoff));
    }
    public List<Trip> getInactiveTrips(final LocalDateTime cutoff, final int limit) {
        return tripDao.getInactiveTrips(cutoff, limit);
    }
    public List<Trip> getInactiveTrips(final LocalDateTime cutoff, final int limit, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripDao.getInactiveTrips(cutoff, limit));
    }
    public List<Trip> getRecentTrips(final int limit) {
        return tripDao.getRecentTrips(limit);
    }
    public List<Trip> getRecentTrips(final int limit, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripDao.getRecentTrips(limit));
    }
    public List<Trip> getTripsForUser(final Person.Id userId) {
        return tripDao.getTripsForUser(userId);
    }
    public List<Trip> getTripsForUser(final Person.Id userId, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripDao.getTripsForUser(userId));
    }

    // Trip Events
    public TripEvent getTripEvent(final String id) {
        return tripEventDao.getTripEvent(id);
    }
    public TripEvent getTripEvent(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> tripEventDao.getTripEvent(id));
    }
    public Boolean saveTripEvent(final TripEvent te) {
        return tripEventDao.saveTripEvent(te);
    }
    public Boolean saveAllTripEvents(final Trip trip) {
        return tripEventDao.saveAllTripEvents(trip);
    }

    // Registrations
    public Boolean saveRegistration(final Registration reg) throws IOException {
        return regDao.saveRegistration(reg);
    }
    public List<Registration> getRegistrations(final String tripId) {
        return regDao.getRegistrations(tripId);
    }
    public List<Registration> getRegistrations(final String tripId, final Cached cached) {
        return NearCacheContext.call(cached, () -> regDao.getRegistrations(tripId));
    }
    public Optional<Registration> getRegistration(final String tripId, final Person.Id userId) {
        return regDao.getRegistration(tripId, userId);
    }
    public Optional<Registration> getRegistration(final String tripId, final Person.Id userId, final Cached cached) {
        return NearCacheContext.call(cached, () -> regDao.getRegistration(tripId, userId));
    }

    // Transactions
    public List<Transaction> getTransactions(final Person.Id userId) {
        return txDao.getTransactions(userId);
    }
    public List<Transaction> getTransactions(final Person.Id userId, final Cached cached) {
        return NearCacheContext.call(cached, () -> txDao.getTransactions(userId));
    }
    public Optional<Transaction> getTransaction(final Person.Id userId, final String txId) {
        return txDao.getTransaction(userId, txId);
    }
    public Optional<Transaction> getTransaction(final Person.Id userId, final String txId, final Cached cached) {
        return NearCacheContext.call(cached, () -> txDao.getTransaction(userId, txId));
    }
    public Boolean saveTransaction(final Transaction tx) throws IOException {
        return txDao.saveTransaction(tx);
    }

    // Credentials
    public Creds adminGetCredsByEmail(final String email) {
        return credDao.adminGetCredsByEmail(email);
    }
    public Creds adminGetCredsByEmail(final String email, final Cached cached) {
        return NearCacheContext.call(cached, () -> credDao.adminGetCredsByEmail(email));
    }
    public Creds getCredsByEmailAndPass(final String email, final String pass) {
        return credDao.getCredsByEmailAndPass(email, pass);
    }
    public Creds getCredsByEmailAndPass(final String email, final String pass, final Cached cached) {
        return NearCacheContext.call(cached, () -> credDao.getCredsByEmailAndPass(email, pass));
    }
    public Creds getCredsByEmailAdminOnly(final String email, final Person.Id id) {
        return credDao.getCredsByEmailAdminOnly(email, id);
    }
    public Creds getCredsByEmailAdminOnly(final String email, final Person.Id id, final Cached cached) {
        return NearCacheContext.call(cached, () -> credDao.getCredsByEmailAdminOnly(email, id));
    }
    /** No password check -- ONLY after the caller has verified identity via an email one-time code. */
    public Creds getCredsForCodeLogin(final String email) {
        return credDao.getCredsForCodeLogin(email);
    }
    public Creds getCredsForCodeLogin(final String email, final Cached cached) {
        return NearCacheContext.call(cached, () -> credDao.getCredsForCodeLogin(email));
    }
    public Long updateLastLogin(final Creds creds) {
        return credDao.updateLastLogin(creds);
    }
    public Optional<Creds> createCreds(final String email) {
        return credDao.createCreds(email);
    }
    public Boolean saveCreds(final Creds creds) {
        return credDao.saveCreds(creds);
    }
    public Boolean removeCreds(final String email) {
        return credDao.removeCreds(email);
    }

    // Remember-me tokens
    public Optional<RememberToken> getRememberToken(final String selector) {
        return rememberMeDao.getToken(selector);
    }
    public Optional<RememberToken> getRememberToken(final String selector, final Cached cached) {
        return NearCacheContext.call(cached, () -> rememberMeDao.getToken(selector));
    }
    public Boolean saveRememberToken(final RememberToken token) {
        return rememberMeDao.saveToken(token);
    }
    public Boolean deleteRememberToken(final String selector) {
        return rememberMeDao.deleteToken(selector);
    }
    public int deleteRememberTokensForUser(final Person.Id userId) {
        return rememberMeDao.deleteAllForUser(userId);
    }

    // Passkeys (WebAuthn credentials)
    public Optional<PasskeyCredential> getPasskey(final String credentialId) {
        return passkeyDao.getPasskey(credentialId);
    }
    public Optional<PasskeyCredential> getPasskey(final String credentialId, final Cached cached) {
        return NearCacheContext.call(cached, () -> passkeyDao.getPasskey(credentialId));
    }
    public List<PasskeyCredential> getPasskeysForUser(final Person.Id userId) {
        return passkeyDao.getPasskeysForUser(userId);
    }
    public List<PasskeyCredential> getPasskeysForUser(final Person.Id userId, final Cached cached) {
        return NearCacheContext.call(cached, () -> passkeyDao.getPasskeysForUser(userId));
    }
    public List<PasskeyCredential> getPasskeysForEmailAndRp(final String email, final String rpId) {
        return passkeyDao.getPasskeysForEmailAndRp(email, rpId);
    }
    public List<PasskeyCredential> getPasskeysForEmailAndRp(
            final String email, final String rpId, final Cached cached) {
        return NearCacheContext.call(cached, () -> passkeyDao.getPasskeysForEmailAndRp(email, rpId));
    }
    public Boolean savePasskey(final PasskeyCredential passkey) {
        return passkeyDao.savePasskey(passkey);
    }
    public Boolean deletePasskey(final String credentialId, final Person.Id owner) {
        return passkeyDao.deletePasskey(credentialId, owner);
    }

    // Todos
    public Boolean saveTodo(final TodoItem todo) throws IOException {
        return todoDao.saveTodo(todo);
    }
    public List<TodoItem> getTodoItems(final String tripId) {
        return todoDao.getTodoItems(tripId);
    }
    public List<TodoItem> getTodoItems(final String tripId, final Cached cached) {
        return NearCacheContext.call(cached, () -> todoDao.getTodoItems(tripId));
    }
    public Optional<TodoItem> getTodoItem(final String tripId, final DataId pdvId){
        return todoDao.getTodoItem(tripId, pdvId);
    }
    public Optional<TodoItem> getTodoItem(final String tripId, final DataId pdvId, final Cached cached) {
        return NearCacheContext.call(cached, () -> todoDao.getTodoItem(tripId, pdvId));
    }

    // Per-User Stored Data
    public Boolean savePersonDataValue(final PersonDataValue pdv) throws IOException {
        return pdvDao.savePersonDataValue(pdv);
    }
    public Map<DataId, PersonDataValue> getPersonDataValues(final Person.Id pid) {
        return pdvDao.getPersonDataValues(pid);
    }
    public Map<DataId, PersonDataValue> getPersonDataValues(final Person.Id pid, final Cached cached) {
        return NearCacheContext.call(cached, () -> pdvDao.getPersonDataValues(pid));
    }
    public Optional<PersonDataValue> getPersonDataValue(final Person.Id pid, final DataId pdvId) {
        return pdvDao.getPersonDataValue(pid, pdvId);
    }
    public Optional<PersonDataValue> getPersonDataValue(final Person.Id pid, final DataId pdvId, final Cached cached) {
        return NearCacheContext.call(cached, () -> pdvDao.getPersonDataValue(pid, pdvId));
    }

    // Privileges
    public Boolean savePrivilege(final Privilege priv) {
        return privDao.savePrivilege(priv);
    }
    // Managed media metadata (see MediaDAO); the bytes live in S3.
    public Optional<MediaItem> getMedia(final String id) {
        return mediaDao.getMedia(id);
    }
    public Optional<MediaItem> getMedia(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> mediaDao.getMedia(id));
    }
    public List<MediaItem> getAllMedia() {
        return mediaDao.getAllMedia();
    }
    public List<MediaItem> getAllMedia(final Cached cached) {
        return NearCacheContext.call(cached, () -> mediaDao.getAllMedia());
    }
    public List<MediaItem> getMediaInSlot(final String slot) {
        return mediaDao.getMediaInSlot(slot);
    }
    public List<MediaItem> getMediaInSlot(final String slot, final Cached cached) {
        return NearCacheContext.call(cached, () -> mediaDao.getMediaInSlot(slot));
    }
    public Boolean saveMedia(final MediaItem item) {
        return mediaDao.saveMedia(item);
    }
    public Boolean deleteMedia(final String id) {
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

    // Content templates (see TemplateDAO). saveTemplate assigns the next version and trims history to retain.
    public Boolean saveTemplate(final ContentTemplate template, final int retain) {
        return templateDao.saveTemplate(template, retain);
    }
    public List<ContentTemplate> getAllTemplates() {
        return templateDao.getAllTemplates();
    }
    public List<ContentTemplate> getAllTemplates(final Cached cached) {
        return NearCacheContext.call(cached, () -> templateDao.getAllTemplates());
    }
    public Optional<ContentTemplate> getTemplate(final String id) {
        return templateDao.getTemplate(id);
    }
    public Optional<ContentTemplate> getTemplate(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> templateDao.getTemplate(id));
    }
    public Optional<ContentTemplate> getTemplate(final String id, final int version) {
        return templateDao.getTemplate(id, version);
    }
    public Optional<ContentTemplate> getTemplate(final String id, final int version, final Cached cached) {
        return NearCacheContext.call(cached, () -> templateDao.getTemplate(id, version));
    }
    public Optional<TemplateRecord> getTemplateRecord(final String id) {
        return templateDao.getTemplateRecord(id);
    }
    public Optional<TemplateRecord> getTemplateRecord(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> templateDao.getTemplateRecord(id));
    }
    public Boolean deleteTemplate(final String id) {
        return templateDao.deleteTemplate(id);
    }
    /** Same escape hatch as {@link #clearMediaCache()}, for rows written behind the DAO's back. */
    public void clearTemplateCache() {
        templateDao.clearCache();
    }

    // Template-driven page content (see ContentDAO).
    public Boolean saveContent(final ContentInstance instance, final int retain) {
        return contentDao.saveContent(instance, retain);
    }
    public List<ContentInstance> getContentForSection(final String section) {
        return contentDao.getContentForSection(section);
    }
    public List<ContentInstance> getContentForSection(final String section, final Cached cached) {
        return NearCacheContext.call(cached, () -> contentDao.getContentForSection(section));
    }
    public Optional<ContentInstance> getContent(final String id) {
        return contentDao.getContent(id);
    }
    public Optional<ContentInstance> getContent(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> contentDao.getContent(id));
    }
    public Optional<ContentRecord> getContentRecord(final String id) {
        return contentDao.getContentRecord(id);
    }
    public Optional<ContentRecord> getContentRecord(final String id, final Cached cached) {
        return NearCacheContext.call(cached, () -> contentDao.getContentRecord(id));
    }
    public List<ContentRecord> getAllContentRecords() {
        return contentDao.getAllContentRecords();
    }
    public List<ContentRecord> getAllContentRecords(final Cached cached) {
        return NearCacheContext.call(cached, () -> contentDao.getAllContentRecords());
    }
    public Boolean deleteContent(final String id) {
        return contentDao.deleteContent(id);
    }
    public Boolean reorderContent(final String section, final List<String> orderedIds) {
        return contentDao.reorderContent(section, orderedIds);
    }
    /** Same escape hatch as {@link #clearMediaCache()}, for rows written behind the DAO's back. */
    public void clearContentCache() {
        contentDao.clearCache();
    }

    // Runtime settings (see ConfigDAO). Reads never throw; callers always supply a default.
    public Optional<Config> getConfig(final String name) {
        return configDao.getConfig(name);
    }
    public Optional<Config> getConfig(final String name, final Cached cached) {
        return NearCacheContext.call(cached, () -> configDao.getConfig(name));
    }
    public List<Config> getAllConfig() {
        return configDao.getAllConfig();
    }
    public List<Config> getAllConfig(final Cached cached) {
        return NearCacheContext.call(cached, () -> configDao.getAllConfig());
    }
    public Boolean saveConfig(final Config config) {
        return configDao.saveConfig(config);
    }

    // Audit trail (see AuditDAO). Deliberately uncached: written constantly, read only by the admin page.
    public Boolean saveAuditEvent(final AuditEvent event) {
        return auditDao.saveAuditEvent(event);
    }
    public AuditPage getAuditEvents(final AuditQuery query) {
        return auditDao.getAuditEvents(query);
    }
    public AuditPage getAuditEvents(final AuditQuery query, final Cached cached) {
        return NearCacheContext.call(cached, () -> auditDao.getAuditEvents(query));
    }
    public List<AuditEvent> exportAuditEvents(final AuditQuery query) {
        return auditDao.exportAuditEvents(query);
    }
    public List<AuditEvent> exportAuditEvents(final AuditQuery query, final Cached cached) {
        return NearCacheContext.call(cached, () -> auditDao.exportAuditEvents(query));
    }

    public Optional<Privilege> getPrivilege(final String name) {
        return privDao.getPrivilege(name);
    }
    public Optional<Privilege> getPrivilege(final String name, final Cached cached) {
        return NearCacheContext.call(cached, () -> privDao.getPrivilege(name));
    }
    public List<Privilege> getGlobalPrivileges() {
        return privDao.getGlobalPrivileges();
    }
    public List<Privilege> getGlobalPrivileges(final Cached cached) {
        return NearCacheContext.call(cached, () -> privDao.getGlobalPrivileges());
    }
    public List<Privilege> getTripPrivileges(final String tripId) {
        return privDao.getTripPrivileges(tripId);
    }
    public List<Privilege> getTripPrivileges(final String tripId, final Cached cached) {
        return NearCacheContext.call(cached, () -> privDao.getTripPrivileges(tripId));
    }

    // Chat (see ChatDAO). Ships dark in P0; user-visible in P1.
    public Boolean saveChatChannel(final ChatChannel channel) {
        return chatDao.saveChannel(channel);
    }
    public Optional<ChatChannel> getChatChannel(final ChatChannel.Id id) {
        return chatDao.getChannel(id);
    }
    public Optional<ChatChannel> getChatChannel(final ChatChannel.Id id, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.getChannel(id));
    }
    public Boolean saveChatMembership(final ChatMembership member) {
        return chatDao.saveMembership(member);
    }
    public Optional<ChatMembership> getChatMembership(
            final ChatChannel.Id channelId, final Person.Id personId) {
        return chatDao.getMembership(channelId, personId);
    }
    public Optional<ChatMembership> getChatMembership(
            final ChatChannel.Id channelId, final Person.Id personId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.getMembership(channelId, personId));
    }
    public List<ChatMembership> listChatMembers(final ChatChannel.Id channelId) {
        return chatDao.listMembers(channelId);
    }
    public List<ChatMembership> listChatMembers(final ChatChannel.Id channelId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.listMembers(channelId));
    }
    public Optional<ChatMessage> saveChatMessage(
            final ChatMessage draft, final ChatChannel channel, final Trip trip) {
        return chatDao.saveMessage(draft, channel, trip);
    }
    public Optional<ChatMessage> getChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId) {
        return chatDao.getMessage(channelId, msgId);
    }
    public Optional<ChatMessage> getChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.getMessage(channelId, msgId));
    }
    public Optional<ChatMessage> getVisibleChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now) {
        return chatDao.getVisibleMessage(channelId, msgId, member, channel, trip, now);
    }
    public Optional<ChatMessage> getVisibleChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now, final Cached cached) {
        return NearCacheContext.call(cached,
                () -> chatDao.getVisibleMessage(channelId, msgId, member, channel, trip, now));
    }
    public Optional<ChatMessage> tombstoneChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId, final String deletedBy) {
        return chatDao.tombstoneMessage(channelId, msgId, deletedBy);
    }
    public Optional<ChatMessage> removeChatAttachment(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId,
            final String s3Key, final String deletedBy) {
        return chatDao.removeAttachment(channelId, msgId, s3Key, deletedBy);
    }
    public ChatPage getChatMessagesSince(
            final ChatChannel.Id channelId, final ChatMessage.Id since, final int limit,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now) {
        return chatDao.getMessagesSince(channelId, since, limit, member, channel, trip, now);
    }
    public ChatPage getChatMessagesSince(
            final ChatChannel.Id channelId, final ChatMessage.Id since, final int limit,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now, final Cached cached) {
        return NearCacheContext.call(cached,
                () -> chatDao.getMessagesSince(channelId, since, limit, member, channel, trip, now));
    }
    public ChatPage getChatMessagesBefore(
            final ChatChannel.Id channelId, final ChatMessage.Id before, final int limit,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now) {
        return chatDao.getMessagesBefore(channelId, before, limit, member, channel, trip, now);
    }
    public ChatPage getChatMessagesBefore(
            final ChatChannel.Id channelId, final ChatMessage.Id before, final int limit,
            final ChatMembership member, final ChatChannel channel, final Trip trip,
            final java.time.Instant now, final Cached cached) {
        return NearCacheContext.call(cached,
                () -> chatDao.getMessagesBefore(channelId, before, limit, member, channel, trip, now));
    }
    public Boolean putChatReaction(final ChatReaction reaction) {
        return chatDao.putReaction(reaction);
    }
    public Boolean deleteChatReaction(
            final ChatChannel.Id channelId, final ChatMessage.Id targetMessageId,
            final Person.Id personId, final String emoji) {
        return chatDao.deleteReaction(channelId, targetMessageId, personId, emoji);
    }
    public Map<ChatMessage.Id, ChatReactionSummary> getChatReactionSummaries(
            final ChatChannel.Id channelId, final List<ChatMessage> messages) {
        return chatDao.summariesForMessages(channelId, messages);
    }
    public Map<ChatMessage.Id, ChatReactionSummary> getChatReactionSummaries(
            final ChatChannel.Id channelId, final List<ChatMessage> messages, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.summariesForMessages(channelId, messages));
    }
    public Map<ChatMessage.Id, ChatReactionSummary> getChatReactionWindow(
            final ChatChannel.Id channelId, final ChatMessage.Id oldest, final ChatMessage.Id newest) {
        return chatDao.summariesForWindow(channelId, oldest, newest);
    }
    public Map<ChatMessage.Id, ChatReactionSummary> getChatReactionWindow(
            final ChatChannel.Id channelId, final ChatMessage.Id oldest, final ChatMessage.Id newest,
            final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.summariesForWindow(channelId, oldest, newest));
    }
    public Long getChatReactionsVersion(final ChatChannel.Id channelId) {
        return chatDao.currentReactionsVersion(channelId);
    }
    public Long getChatReactionsVersion(final ChatChannel.Id channelId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.currentReactionsVersion(channelId));
    }
    public Optional<ChatMessage> editChatMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId, final String newBody) {
        return chatDao.editMessage(channelId, msgId, newBody);
    }
    public Boolean saveChatCursor(
            final ChatChannel.Id channelId, final Person.Id personId, final ChatMessage.Id cursor) {
        return chatDao.saveCursor(channelId, personId, cursor);
    }
    public Optional<ChatMessage.Id> getChatCursor(
            final ChatChannel.Id channelId, final Person.Id personId) {
        return chatDao.getCursor(channelId, personId);
    }
    public Optional<ChatMessage.Id> getChatCursor(
            final ChatChannel.Id channelId, final Person.Id personId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.getCursor(channelId, personId));
    }
    public Map<String, String> getChatLastActivity() {
        return chatDao.lastActivity();
    }
    public Map<String, String> getChatLastActivity(final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.lastActivity());
    }
    public Map<String, PhotoChatMeta> getPhotoChatMeta(final List<String> s3Keys) {
        return chatDao.photoMeta(s3Keys);
    }
    public Map<String, PhotoChatMeta> getPhotoChatMeta(final List<String> s3Keys, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.photoMeta(s3Keys));
    }
    /** Raw newest-first rows, unfiltered by visibility — plumbing for photo parent resolution, never display. */
    public List<ChatMessage> getRawChatMessagesBefore(
            final ChatChannel.Id channelId, final ChatMessage.Id before, final int limit) {
        return chatDao.rawMessagesBefore(channelId, before, limit);
    }
    public List<ChatMessage> getRawChatMessagesBefore(
            final ChatChannel.Id channelId, final ChatMessage.Id before, final int limit, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.rawMessagesBefore(channelId, before, limit));
    }
    public Boolean invalidatePhotoChatMeta(final String s3Key) {
        return chatDao.invalidatePhotoMeta(s3Key);
    }
    public Boolean rollupPhotoToParent(final ChatChannel photoChannel) {
        return chatDao.rollupToParent(photoChannel);
    }
    public Optional<ChatChannel> purgeChatChannel(final ChatChannel.Id id) {
        return chatDao.purgeChannel(id);
    }
    public Boolean addGuestChatChannel(final Person.Id personId, final ChatChannel.Id channelId) {
        return chatDao.addGuestChannel(personId, channelId);
    }
    public List<ChatChannel.Id> getGuestChatChannelIds(final Person.Id personId) {
        return chatDao.listGuestChannelIds(personId);
    }
    public List<ChatChannel.Id> getGuestChatChannelIds(final Person.Id personId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatDao.listGuestChannelIds(personId));
    }

    // Chat invites (see ChatInviteDAO)
    public Boolean saveChatInvite(final ChatInvite invite) {
        return chatInviteDao.saveInvite(invite);
    }
    public Optional<ChatInvite> getChatInvite(final ChatChannel.Id channelId, final String selector) {
        return chatInviteDao.getInvite(channelId, selector);
    }
    public Optional<ChatInvite> getChatInvite(
            final ChatChannel.Id channelId, final String selector, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatInviteDao.getInvite(channelId, selector));
    }
    public List<ChatInvite> listChatInvites(final ChatChannel.Id channelId) {
        return chatInviteDao.listInvites(channelId);
    }
    public List<ChatInvite> listChatInvites(final ChatChannel.Id channelId, final Cached cached) {
        return NearCacheContext.call(cached, () -> chatInviteDao.listInvites(channelId));
    }
    public Boolean deleteChatInvite(final ChatChannel.Id channelId, final String selector) {
        return chatInviteDao.deleteInvite(channelId, selector);
    }
    public void recordChatInviteUse(final ChatInvite invite) {
        chatInviteDao.recordUse(invite);
    }

    // Bindings
    public Boolean saveBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean bidirectionalBindings) {
        return bindingDao.saveBinding(id, type, destId, destType, bidirectionalBindings);
    }
    public List<String> getBindings(
            final String name, final BindingType type, BindingType destType) {
        return bindingDao.getBindings(name, type, destType);
    }
    public List<String> getBindings(
            final String name, final BindingType type, BindingType destType, final Cached cached) {
        return NearCacheContext.call(cached, () -> bindingDao.getBindings(name, type, destType));
    }
    public Boolean removeBinding(final String id, final BindingType type,
            final String destId, final BindingType destType, final boolean bidirectionalBindings) {
        return bindingDao.removeBinding(id, type, destId, destType, bidirectionalBindings);
    }

    /**
     * Drops every entry in the shared cache's data namespace (never a full FLUSH -- on ElastiCache Serverless that
     * would also wipe the distributed sessions). Used by tests and the admin "clear all caches" action; with the
     * shared cache this now flushes for every running instance at once.
     */
    public void clearAllCaches() {
        cacheClient.clearNamespace(CacheKeys.FORMAT_VERSION);
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
