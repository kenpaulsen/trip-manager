package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

@Slf4j
public class DAO {
    @Getter
    private final ObjectMapper mapper;
    private final PersonDAO personDao;
    private final TripEventDAO tripEventDao;
    private final TripDAO tripDao;
    private final RegistrationDAO regDao;
    private final TransactionDAO txDao;
    private final CredentialsDAO credDao;
    private final TodoDAO todoDao;
    private final PersonDataValueDAO pdvDao;
    private final PrivilegesDAO privDao;
    private final BindingDAO bindingDao;

    // This flag is set in the web.xml
    private static final DAO INSTANCE = new DAO();

    private DAO() {
        final Persistence persistence = createTripPersistence();
        this.mapper = createObjectMapper();
        this.personDao = new PersonDAO(mapper, persistence);
        this.tripEventDao = new TripEventDAO(mapper, persistence);
        this.tripDao = new TripDAO(mapper, persistence, tripEventDao);
        this.regDao = new RegistrationDAO(mapper, persistence);
        this.txDao = new TransactionDAO(mapper, persistence);
        this.credDao = new CredentialsDAO(persistence, personDao);
        this.todoDao = new TodoDAO(mapper, persistence);
        this.pdvDao = new PersonDataValueDAO(mapper, persistence);
        this.privDao = new PrivilegesDAO(mapper, persistence);
        this.bindingDao = new BindingDAO(persistence);
        FakeData.addFakeData(personDao, tripDao);
    }

    public static DAO getInstance() {
        return INSTANCE;
    }

    // People
    public CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        return personDao.savePerson(person);
    }
    public CompletableFuture<List<Person>> getPeople() {
        return personDao.getPeople();
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
    public CompletableFuture<List<Trip>> getTrips() {
        return tripDao.getTrips();
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
    public CompletableFuture<Optional<Privilege>> getPrivilege(final String name) {
        return privDao.getPrivilege(name);
    }
    public CompletableFuture<List<Privilege>> getPrivileges() {
        return privDao.getPrivileges();
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

    /* Package-private for testing */
    public void clearAllCaches() {
        personDao.clearCache();
        tripDao.clearCache();
        tripEventDao.clearCache();
        regDao.clearCache();
        txDao.clearCache();
        todoDao.clearCache();
        pdvDao.clearCache();
        privDao.clearCache();
        bindingDao.clearCache();
    }

    private ObjectMapper createObjectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private Persistence createTripPersistence() {
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
}
